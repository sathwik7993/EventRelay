# EventRelay

**A reliable webhook delivery platform** — guarantees that every event reaches its
destination, with at-least-once delivery, retries, dead-lettering, HMAC signing,
and full delivery observability.

Think Stripe/Svix-style webhook infrastructure: you `POST` an event, EventRelay
persists it durably and takes responsibility for delivering it to every matching
subscriber — retrying through failures and surfacing what happened.

> Full design docs live in [`eventrelay_plan/`](./eventrelay_plan). This README
> tracks what is actually built.

---

## Architecture (target)

```
Producer ──POST /events──> Ingest API ──(single txn)──> Postgres (events + outbox)
                                                              │
                                                     Outbox Poller (SKIP LOCKED)
                                                              │
                                                          AWS SQS            ← Milestone 2
                                                              │
                                                     Dispatcher Workers
                                                              │
                                          HMAC-signed HTTP POST ──> Subscriber
                                                              │
                                             retries → DLQ → replay          ← Milestone 2
```

The keystone is the **transactional outbox pattern**: an event and its outbox row
are written in one database transaction, so an accepted event can never be lost
between the API and the delivery pipeline (no dual-write problem).

### Modules

| Module | Deployable | Responsibility |
|--------|-----------|----------------|
| `eventrelay-common` | library | Crypto (API-key hashing), ID/secret generation |
| `eventrelay-core`   | library | JPA domain model, repositories, core services, Flyway schema |
| `eventrelay-api`    | Spring Boot app | REST API + (M1) outbox poller & HTTP dispatcher |

---

## Milestone status

- [x] **M1 — Core pipeline (done).** Multi-module build, Postgres schema (Flyway),
      API-key auth, tenant & subscription management, event ingestion with
      idempotency, transactional outbox, `SELECT FOR UPDATE SKIP LOCKED` poller,
      single-attempt HTTP delivery, delivery-attempt audit trail.
- [ ] **M2 — Reliability.** Extract `eventrelay-dispatcher`, AWS SQS (LocalStack),
      exponential backoff + jitter, DLQ + replay, HMAC-SHA256 signing, Redis idempotency.
- [ ] **M3 — Production polish.** Per-tenant rate limiting, circuit breakers,
      Prometheus/Grafana, Testcontainers integration tests, a chaos test proving zero loss.
- [ ] **M4 — Deploy & showcase.** Dockerfiles, GitHub Actions CI, deploy to a
      DigitalOcean droplet (+ real AWS SQS), Terraform/ECS configs as artifacts.

### M1 deliberate simplifications (revisited later)
- Status columns are `VARCHAR + CHECK` rather than native PG `ENUM` types (cleaner JPA mapping).
- `events` / `delivery_attempts` are not range-partitioned yet (added in M3).
- The poller and HTTP dispatcher run inside `eventrelay-api`; they become a
  separate SQS-driven worker in M2.
- Tenant creation is unauthenticated (bootstrap/admin action).

---

## Run it locally

### Prerequisites
JDK 17, Maven 3.8+, Docker Desktop.

### 1. Start infrastructure
```bash
docker compose up -d postgres
# redis + localstack are defined too, used from Milestone 2
```

### 2. Build & run the API
```bash
mvn -DskipTests clean install
java -jar eventrelay-api/target/eventrelay-api.jar
# API on http://localhost:8080  (health: /actuator/health)
```

### 3. Try the pipeline
```bash
# Create a tenant (returns an API key — shown once)
curl -s -X POST http://localhost:8080/api/v1/tenants \
  -H 'Content-Type: application/json' \
  -d '{"name":"Acme","slug":"acme"}'

KEY=er_live_...   # from the response above

# Create a subscription
curl -s -X POST http://localhost:8080/api/v1/subscriptions \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"targetUrl":"https://your-endpoint.example/webhooks","eventTypes":["payment.completed"]}'

# Ingest an event (delivered asynchronously by the poller)
curl -s -X POST http://localhost:8080/api/v1/events \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: idk-001' \
  -d '{"eventType":"payment.completed","data":{"amount":9999,"currency":"USD"}}'

# Inspect delivery attempts for the event
curl -s http://localhost:8080/api/v1/events/{eventId}/deliveries \
  -H "Authorization: Bearer $KEY"
```

---

## API (Milestone 1)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/v1/tenants` | — | Create tenant, returns API key once |
| `POST` | `/api/v1/subscriptions` | Bearer | Create a subscription |
| `GET`  | `/api/v1/subscriptions` | Bearer | List subscriptions |
| `POST` | `/api/v1/events` | Bearer | Ingest an event (`202` accepted, `200` if idempotent replay) |
| `GET`  | `/api/v1/events/{id}` | Bearer | Get an event |
| `GET`  | `/api/v1/events/{id}/deliveries` | Bearer | Delivery-attempt history |
| `GET`  | `/actuator/health` | — | Health check |
