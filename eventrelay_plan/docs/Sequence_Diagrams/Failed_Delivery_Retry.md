# Sequence Diagram — Failed Delivery and SQS Retry Routing

This document provides a sequence diagram detailing how EventRelay handles failed HTTP delivery attempts, executes backoff calculations, and schedules retries.

---

## 1. Sequence Diagram (Mermaid)

```mermaid
sequenceDiagram
    autonumber
    participant S as AWS SQS Queue
    participant W as Dispatcher Worker
    participant DB as PostgreSQL DB
    participant R as Webhook Receiver

    S->>W: Dequeue Event Message
    W->>R: HTTP POST Webhook Request (Attempt 1)
    Note over R: Receiver is down or returning errors
    R-->>W: HTTP Status 503 Service Unavailable
    
    rect rgb(240, 240, 240)
        Note over W: Execute Backoff Engine
        W->>W: Calculate backoff: Base 1s * 2^1 = 2s + Jitter
    end

    W->>DB: Log attempt in delivery_attempts (status: FAILED, attempt: 1)
    
    rect rgb(230, 240, 250)
        Note over W: Reschedule Delivery
        W->>S: Publish Event to SQS with delaySeconds = 2
        W->>S: Delete current message (ACK)
    end
```
---

## 2. Dynamic Redrive Window

- If the receiver returns a retryable status code (such as `500`, `503`, `504`, `428`, or connection timeout), the event is re-queued with a delay.
- Non-retryable statuses (such as `400`, `401`, `403`, `404`) bypass the retry loop and move directly to the Dead-Letter Queue (DLQ).
