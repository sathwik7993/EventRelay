# EventRelay — API Contract Testing

This document details the Contract Testing strategy and implementation used in EventRelay to prevent breaking updates between services and ensure event payload backward compatibility.

---

## 1. Why Contract Testing?

In event-driven architectures, breaking schema updates are a major failure mode. If a service team updates the format of an event payload (e.g., changing `user_id` to `userId`) without notifying downstream consumers, processing will fail.
- EventRelay uses **Consumer-Driven Contract Testing** (via Spring Cloud Contract or Pact) to lock in API schemas before deployment.
- Outbound webhook payloads are subject to strict JSON Schema contracts to prevent breaking changes.

---

## 2. Ingest API Contract Definition (Groovy DSL)

The contract defines the expected API request and response for event submissions:

```groovy
org.springframework.cloud.contract.spec.Contract.make {
    description("Verify Event Ingestion API Response")
    request {
        method 'POST'
        url '/api/v1/events'
        headers {
            header('X-EventRelay-Key', 'er_live_7d5e6a8b1c2d3e4f5g6h7i8j9k0l1m2n')
            header('X-Idempotency-Key', 'idemp-challenge-102')
            contentType(applicationJson())
        }
        body([
            event_type: 'payment.succeeded',
            payload: [
                id: 'evt_123',
                amount: 1000,
                currency: 'USD'
            ]
        ])
    }
    response {
        status ACCEPTED()
        headers {
            contentType(applicationJson())
        }
        body([
            event_id: $(consumer(regex('[a-f0-9-]{36}')), provider('a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d')),
            status: 'QUEUED'
        ])
    }
}
```

---

## 3. Webhook Payload Schema Contracts

To guarantee that webhook consumers do not receive breaking payloads, EventRelay enforces **JSON Schema validation** on outgoing events:

- **Schema Registry**: Webhook payloads are registered in an internal schema registry (JSON Schema Draft-07 format).
- **Validation in Pipeline**: The dispatcher worker runs schema validation before delivering the webhook. If the payload violates the schema, the event is marked as `FAILED` (with diagnostic detail) instead of delivering a corrupted payload.
- **Rules for Evolution**:
  - Only additive changes are allowed (new fields).
  - Removing fields or changing data types is forbidden without creating a new API version.
