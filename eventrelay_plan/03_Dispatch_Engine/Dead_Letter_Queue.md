# EventRelay — Dead-Letter Queue (DLQ) Design

This document details the architecture, schemas, and operational workflows of the Dead-Letter Queue (DLQ) in EventRelay, which handles delivery failures that have exhausted all retry attempts.

---

## 1. The DLQ Lifecycle

When an event fails to deliver, it goes through a structured escalation path before entering the DLQ:

```
[ Ingested Event ] ──► [ Max Retries (e.g. 5) ] ──► [ DLQ Route Trigger ]
                                                           │
                                             ┌─────────────┴─────────────┐
                                             ▼                           ▼
                                     [ SQS DLQ Queue ]           [ PostgreSQL DB ]
                                     (eventrelay-dlq)          (dead_letter_events)
```

1. **Failure Trigger**: An event fails delivery due to a permanent failure (e.g., HTTP `400 Bad Request`, `401 Unauthorized`, SSRF block) or because it has exceeded the maximum retry limit (default 5 attempts).
2. **Dual-Path Routing**:
   - **Database Log**: The worker updates the event's status to `DEAD_LETTERED` in the `events` table and creates a detailed entry in the `dead_letter_events` table containing diagnostic metadata.
   - **Queue Isolation**: The message is acknowledged (deleted) from the main SQS queue, and a new message is sent to the dedicated SQS DLQ (`eventrelay-dlq`).
3. **Audit and Inspection**: The administrator or tenant uses the frontend dashboard to view the payload, error code, attempt history, and response body snippets.

---

## 2. Database Schema

The `dead_letter_events` table preserves complete context about the failure for developer diagnostics:

```sql
CREATE TABLE dead_letter_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    subscription_id UUID NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    event_type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    
    -- Diagnostics
    last_error_message TEXT,
    last_http_status INTEGER,
    attempt_count INTEGER NOT NULL,
    original_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    dead_lettered_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    -- Status
    status VARCHAR(50) DEFAULT 'PENDING_REVIEW' NOT NULL, -- PENDING_REVIEW, REPLAYED, DISCARDED
    replayed_at TIMESTAMP WITH TIME ZONE,
    replay_count INTEGER DEFAULT 0 NOT NULL
);

-- Indexes for Dashboard queries
CREATE INDEX idx_dlq_tenant_status ON dead_letter_events(tenant_id, status);
CREATE INDEX idx_dlq_created_at ON dead_letter_events(dead_lettered_at DESC);
```

---

## 3. Metadata Enrichment

To simplify analysis, SQS DLQ messages are enriched with standard **Message Attributes**:

| Message Attribute | Data Type | Purpose |
|-------------------|-----------|---------|
| `TenantId` | String | The unique ID of the tenant owning the event. |
| `SubscriptionId` | String | The subscription that failed. |
| `EventId` | String | The reference ID to link back to the main event log. |
| `FailureReason` | String | Excerpts from the final exception or HTTP response snippet (e.g., `502 Bad Gateway`). |
| `FinalAttemptTimestamp` | String | ISO 8601 string of when the final dispatch failed. |

---

## 4. Retention Policy & Cleanup

To prevent storage inflation, DLQ data is subject to strict cleanup rules:

- **Database Retention**: DLQ rows in the `dead_letter_events` table are preserved for **30 days**. An automated daily database cron job deletes rows older than 30 days.
- **SQS Retention**: SQS standard DLQ messages are configured with a **14-day message retention period** (the maximum supported by AWS SQS).
- **Archival**: Before deletion, an automated AWS Glue job or Spring Task exports deleted database entries to a secure, compressed AWS S3 bucket (`eventrelay-archive-dlq`) for compliance audits.

---

## 5. Monitoring and Alerts

DLQ growth is one of the most critical operational metrics. EventRelay configures two alarms:

1. **Warning Alarm (Slack)**: Triggered when `ApproximateNumberOfMessagesVisible` in `eventrelay-dlq` > 50 messages. This suggests a specific customer endpoint is down or returning errors.
2. **Critical Alarm (PagerDuty)**: Triggered when `ApproximateNumberOfMessagesVisible` in `eventrelay-dlq` > 500 messages, or if the rate of messages entering the DLQ exceeds 10 per minute. This indicates systemic infrastructure issues (e.g., a broken worker build, database failure, or global network partition).
