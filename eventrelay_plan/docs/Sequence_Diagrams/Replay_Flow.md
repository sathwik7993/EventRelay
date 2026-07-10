# Sequence Diagram — Event Replay Flow

This document details the sequence of operations for triggering event replays from the Dead-Letter Queue (DLQ).

---

## 1. Sequence Diagram (Mermaid)

```mermaid
sequenceDiagram
    autonumber
    participant U as User / API Client
    participant A as Ingestion API / Admin controller
    participant DB as PostgreSQL DB
    participant RQ as SQS Replay Queue (Low Priority)
    participant W as Dispatcher Workers

    U->>A: POST /api/v1/events/{id}/replay (Replay request)
    A->>DB: Fetch original event and verify tenant permissions
    DB-->>A: Return Event data
    A->>DB: Write Replay Audit Log entry (status: PROCESSING)
    A->>RQ: Publish Event to SQS Replay Queue (delay = 0)
    A-->>U: Return HTTP 202 Accepted (Replay Queued)
    
    W->>RQ: Dequeue Replay Event
    W->>W: Process delivery (sign, rate limit check)
    W->>U: HTTP POST to target endpoint
    W->>DB: Update Replay Audit Log (status: COMPLETED)
```
---

## 2. Replay Performance Guarantees

- **Low Priority Workers**: SQS Replay queue is processed by a smaller subset of worker threads, ensuring live traffic is never blocked.
- **Audit Trails**: Every replay execution creates a mapping log entry tracking who triggered the replay, when, and the delivery result.
