# EventRelay — Replay Engine

This document details the design, logic, and API endpoints of the EventRelay Replay Engine. This subsystem allows developers and automated scripts to re-dispatch historical events or recover messages from the Dead-Letter Queue (DLQ).

---

## 1. Replay Mechanisms

EventRelay support three types of event replay:

```
                  ┌────────────────────────┐
                  │ Replay Trigger Request │
                  └───────────┬────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         ▼                    ▼                    ▼
   [ Single Replay ]    [ Batch Replay ]     [ Route Override ]
   Re-queues one        Re-queues matching   Sends event to a
   event by ID.         filters (date, type) new destination URL.
```

1. **Single Event Replay**: Re-dispatches a single event using its `event_id` or `dlq_id`.
2. **Batch Event Replay**: Re-dispatches multiple events matching specific criteria (e.g., all events for `tenant_id` which failed between `09:00` and `10:00` with status `401 Unauthorized`).
3. **Route Override Replay**: Allows sending an event to a new target URL (e.g., if a tenant migrated their endpoint or set up a debugging webhook catcher).

---

## 2. Process Flow & Replay Isolation

Replaying events must not interfere with real-time ingestion traffic. To guarantee isolation:

1. **Dedicated Replay Queue**: Replayed events are *not* published to the primary SQS queue. Instead, they are routed to a dedicated low-priority SQS queue (`eventrelay-delivery-replay`).
2. **Low-Priority Worker Thread Pool**: Dispatcher workers dedicate only $20\%$ of their thread capacity to the replay queue, ensuring a bulk replay of 100,000 events does not introduce latency for incoming live events.
3. **Sequence of Operations**:
   ```
   [ Client Replay Request ]
              │
              ▼
   [ Fetch Event from Log/DLQ ]
              │
              ▼
   [ Verify Authorization (Tenant match) ]
              │
              ▼
   [ Create Replay Audit Log Entry ]
              │
              ▼
   [ Enqueue to SQS Replay Queue ]
              │
              ▼
   [ Dispatcher picks up message ] ──► [ HMAC Sign with active secret ] ──► [ Target URL ]
   ```

---

## 3. Database Audit & Schema

Every replay action is logged to guarantee audit compliance (crucial for SOC 2 audits):

```sql
CREATE TABLE replay_audit_logs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    triggered_by VARCHAR(255) NOT NULL, -- User ID, API Key hash, or SYSTEM
    target_type VARCHAR(50) NOT NULL,    -- SINGLE, BATCH
    filter_criteria JSONB,               -- The parameters used if BATCH
    event_count INTEGER NOT NULL,
    status VARCHAR(50) DEFAULT 'PROCESSING' NOT NULL, -- PROCESSING, COMPLETED, FAILED
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE replayed_events_mapping (
    id UUID PRIMARY KEY,
    audit_log_id UUID NOT NULL REFERENCES replay_audit_logs(id) ON DELETE CASCADE,
    original_event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    new_event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL
);
```

---

## 4. Replay Code Implementation (Spring Boot Service)

The core replay service handles database retrieval, message construction, and SQS publishing:

```java
@Service
@RequiredArgsConstructor
public class ReplayService {

    private final EventLogRepository eventRepository;
    private final DeadLetterRepository dlqRepository;
    private final SqsTemplate sqsTemplate;

    @Transactional
    public ReplayResponse triggerSingleReplay(UUID eventId, ReplayRequest request) {
        // 1. Fetch original event details
        EventLog event = eventRepository.findById(eventId)
            .orElseThrow(() -> new EventNotFoundException(eventId));

        // 2. Determine target URL (original or overridden)
        String targetUrl = request.getOverrideUrl() != null ? 
            request.getOverrideUrl() : event.getSubscription().getTargetUrl();

        // 3. Construct SQS Replay payload
        Map<String, Object> messageHeaders = Map.of(
            "original-event-id", event.getId().toString(),
            "target-url", targetUrl,
            "attempt-count", "0",
            "is-replay", "true"
        );

        // 4. Send to Low-Priority Replay Queue
        sqsTemplate.send("eventrelay-delivery-replay", 
            event.getPayload(), 
            messageHeaders
        );

        // 5. Update DLQ status if triggered from a DLQ source
        dlqRepository.findByEventId(eventId).ifPresent(dlq -> {
            dlq.setStatus(DlqStatus.REPLAYED);
            dlq.setReplayedAt(Instant.now());
            dlq.setReplayCount(dlq.getReplayCount() + 1);
            dlqRepository.save(dlq);
        });

        return new ReplayResponse(eventId, "QUEUED_FOR_DELIVERY");
    }
}
```

---

## 5. Replay Rate Limiting

To prevent accidental denial of service (DoS) on webhook receivers or internal SQS capacity:
- Replay requests are limited to **500 events per minute per tenant**.
- Large batch requests (>10,000 events) are split into smaller chunks (batches of 100) and queued with a progressive delay (e.g., 5 seconds between batches) to distribute the network load.
