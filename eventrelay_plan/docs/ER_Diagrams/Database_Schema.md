# EventRelay — Database Entity Relationship (ER) Diagram

This document details the schema definitions, relational constraints, and index layouts for the EventRelay PostgreSQL database.

---

## 1. Database Schema Diagram (Mermaid ER)

```mermaid
erDiagram
    tenants ||--o{ api_keys : possesses
    tenants ||--o{ subscriptions : subscribes
    tenants ||--o{ events : owns
    subscriptions ||--o{ events : contains
    events ||--o{ delivery_attempts : triggers
    events ||--o| dead_letter_events : escalates

    tenants {
        uuid id PK
        varchar name
        varchar rate_limit
        varchar active_secret
        timestamp created_at
    }

    api_keys {
        uuid id PK
        uuid tenant_id FK
        varchar api_key_hash
        varchar scope
        timestamp expires_at
    }

    subscriptions {
        uuid id PK
        uuid tenant_id FK
        varchar name
        varchar target_url
        varchar status
        varchar signing_secret
    }

    events {
        uuid id PK
        uuid tenant_id FK
        uuid subscription_id FK
        varchar event_type
        jsonb payload
        varchar idempotency_key
        timestamp created_at
    }

    delivery_attempts {
        uuid id PK
        uuid event_id FK
        integer attempt_number
        varchar status
        integer http_status_code
        text error_message
        integer duration_ms
        timestamp created_at
    }

    dead_letter_events {
        uuid id PK
        uuid tenant_id FK
        uuid subscription_id FK
        uuid event_id FK
        varchar event_type
        jsonb payload
        text last_error_message
        timestamp dead_lettered_at
    }
```
---

## 2. High-Performance Indices

To maintain fast query response times under high write loads:
- **Composite Index for Outbox**: `CREATE INDEX idx_outbox_poll ON outbox(status, created_at)`.
- **Tenant Isolation Index**: `CREATE INDEX idx_events_tenant ON events(tenant_id, created_at DESC)`.
- **Deduplication Constraint**: `ALTER TABLE events ADD CONSTRAINT unique_idempotency UNIQUE(tenant_id, idempotency_key)`.
