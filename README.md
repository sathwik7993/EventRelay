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

## Architecture

```
Producer -POST /events-> API -(single txn)-> Postgres (events + outbox)
                                                   |
                         +-------------- eventrelay-dispatcher --------------+
                         |  OutboxRelay (SKIP LOCKED) -> deliveries table    |
                         |  DeliveryScheduler (due rows) -> publish          |
                         |                                   |               |
                         |                               AWS SQS             |
                         |                                   |               |
                         |  DeliveryWorker (consumer) <------+               |
                         |     HMAC-signed HTTP POST -> subscriber           |
                         |     2xx        -> DELIVERED                        |
                         |     5xx/timeout-> RETRYING (backoff + jitter)      |
                         |     4xx/exhaust-> DLQ -> replay                    |
                         +---------------------------------------------------+
```

Two keystones:
- **Transactional outbox** — an event and its outbox row are written in one DB
  transaction, so an accepted event is never lost before the delivery pipeline
  (no dual-write problem).
- **DB-driven retry scheduling** — retry timing lives in `deliveries.next_attempt_at`,
  not in the SQS message, so backoff isn't capped by the SQS 15-minute delay limit.
  SQS is pure work distribution.

### Modules

| Module | Deployable | Responsibility |
|--------|-----------|----------------|
| `eventrelay-common` | library | Crypto (API-key hashing, **HMAC signing**), **backoff calculator** |
| `eventrelay-core`   | library | JPA domain model, repositories, core services, Flyway schema |
| `eventrelay-api`    | Spring Boot app (`:8080`) | REST API: ingestion, subscriptions, DLQ/replay, Redis idempotency |
| `eventrelay-dispatcher` | Spring Boot app (`:8081`) | Outbox relay, SQS scheduler + consumer, HMAC-signed delivery, retries, DLQ |

---

## Milestone status

- [x] **M1 — Core pipeline.** Multi-module build, Postgres schema (Flyway),
      API-key auth, tenant & subscription management, event ingestion with
      idempotency, transactional outbox, `SELECT FOR UPDATE SKIP LOCKED` poller,
      HTTP delivery, delivery-attempt audit trail.
- [x] **M2 — Reliability.** Separate `eventrelay-dispatcher` deployable; AWS SQS
      (LocalStack locally); per-(event×subscription) delivery state machine;
      exponential backoff + jitter; dead-letter queue with list + replay;
      HMAC-SHA256 request signing; Redis-backed idempotency fast-path;
      lease-based reclaim of stuck in-flight deliveries.
- [x] **M3 — Production polish.** Per-tenant token-bucket rate limiting (Redis Lua);
      DB-backed per-subscription circuit breaker; Prometheus metrics on both services
      with a provisioned Prometheus + Grafana stack; structured JSON logging with
      correlation ids; a Testcontainers integration test; and an automated chaos test
      proving zero event loss across a `kill -9` mid-flight.
- [ ] **M4 — Deploy & showcase.** Dockerfiles, GitHub Actions CI, deploy to a
      DigitalOcean droplet (+ real AWS SQS), Terraform/ECS configs as artifacts.

### Deliberate simplifications (revisited later)
- Status columns are `VARCHAR + CHECK` rather than native PG `ENUM` types (cleaner JPA mapping).
- `events` / `delivery_attempts` are not range-partitioned yet (added in M3).
- Tenant creation is unauthenticated (bootstrap/admin action).
- The API service owns Flyway migrations; the dispatcher validates the schema (`ddl-auto=validate`).

---

## Run it locally

### Prerequisites
JDK 17, Maven 3.8+, Docker Desktop.

### 1. Start infrastructure
```bash
docker compose up -d postgres redis localstack

# optional: Prometheus (:9090) + Grafana (:3000, anonymous admin) with a
# pre-provisioned EventRelay dashboard
docker compose --profile observability up -d prometheus grafana
```

### 2. Build
```bash
mvn -DskipTests clean install
```

### 3. Run both services (two terminals)
```bash
# Terminal 1 — API (runs DB migrations on startup)
java -jar eventrelay-api/target/eventrelay-api.jar          # http://localhost:8080

# Terminal 2 — dispatcher (creates the SQS queue, starts workers)
java -jar eventrelay-dispatcher/target/eventrelay-dispatcher.jar   # http://localhost:8081
```
Start the API first so the schema exists before the dispatcher validates it.

### 4. Try the pipeline
```bash
# Create a tenant (returns an API key — shown once)
curl -s -X POST http://localhost:8080/api/v1/tenants \
  -H 'Content-Type: application/json' -d '{"name":"Acme","slug":"acme"}'

KEY=er_live_...   # from the response above

# Subscribe (http allowed locally; https enforced in production)
curl -s -X POST http://localhost:8080/api/v1/subscriptions \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"targetUrl":"https://your-endpoint.example/webhooks","eventTypes":["payment.completed"]}'

# Ingest an event (delivered asynchronously, HMAC-signed)
curl -s -X POST http://localhost:8080/api/v1/events \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: idk-001' \
  -d '{"eventType":"payment.completed","data":{"amount":9999,"currency":"USD"}}'

# Inspect delivery attempts, or the dead-letter queue
curl -s http://localhost:8080/api/v1/events/{eventId}/deliveries -H "Authorization: Bearer $KEY"
curl -s http://localhost:8080/api/v1/dead-letter -H "Authorization: Bearer $KEY"
```

### Verifying the HMAC signature (receiver side)
Each delivery carries `X-EventRelay-Signature: v1=<hex>` and `X-EventRelay-Timestamp`.
Recompute `HMAC_SHA256(signing_secret, timestamp + "." + rawBody)` and compare in
constant time; reject if the timestamp is more than 5 minutes old.

---

## API

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/v1/tenants` | — | Create tenant, returns API key once |
| `POST` | `/api/v1/subscriptions` | Bearer | Create a subscription (returns signing secret) |
| `GET`  | `/api/v1/subscriptions` | Bearer | List subscriptions |
| `POST` | `/api/v1/events` | Bearer | Ingest an event (`202` accepted, `200` idempotent replay) |
| `GET`  | `/api/v1/events/{id}` | Bearer | Get an event |
| `GET`  | `/api/v1/events/{id}/deliveries` | Bearer | Delivery-attempt history |
| `POST` | `/api/v1/events/{id}/replay` | Bearer | Re-queue dead-lettered deliveries for an event |
| `GET`  | `/api/v1/dead-letter` | Bearer | List dead-lettered events (paginated) |
| `GET`  | `/actuator/health` | — | Health check (both services) |

## Operational features (M3)

| Feature | How it works |
|---------|-------------|
| **Rate limiting** | Per-tenant token bucket in Redis via an atomic Lua script. Over-limit ingests get `429`. Default rate is configurable; overridable per tenant via `settings.rate_limit_rps`. |
| **Circuit breaker** | Per-subscription, DB-backed (`failure_count` / `last_failure_at`) so it is shared across all dispatcher instances. Opens after N consecutive failures; while open, deliveries are recorded `SKIPPED` and deferred (without consuming retries), protecting a struggling endpoint. Closes on the next success. |
| **Metrics** | Micrometer → `/actuator/prometheus` on both services: `eventrelay_events_ingested_total`, `eventrelay_ingest_rate_limited_total`, `eventrelay_delivery_attempts_total{result}`, `eventrelay_delivery_duration_seconds`, `eventrelay_delivery_retries_total`, `eventrelay_delivery_skipped_total`, `eventrelay_deliveries_dead_lettered_total`. |
| **Dashboards** | `observability/` provisions Prometheus scrape config + a Grafana datasource and EventRelay dashboard. |
| **Structured logging** | JSON logs (logstash-logback-encoder) with a `requestId` correlation id (API) / `deliveryId` (dispatcher) in the MDC. |

### Tests
- `mvn test` runs a Testcontainers integration test (`eventrelay-core`) that boots a
  real PostgreSQL and verifies outbox atomicity, idempotency, retry→DLQ, and the
  circuit breaker. It self-skips when Docker is not reachable by Testcontainers.
- **Chaos / zero-loss:** the system survives a `kill -9` of the dispatcher with
  deliveries in flight — every event is still delivered (at-least-once) once the
  worker restarts; nothing is lost. SQS visibility-timeout redelivery plus a
  lease-based reclaim of stuck in-flight rows guarantee recovery.

### Webhook delivery headers
```
Content-Type: application/json
User-Agent: EventRelay/1.0
X-Event-ID: <event uuid>
X-Event-Type: <event type>
X-EventRelay-Attempt: <n>
X-EventRelay-Timestamp: <unix seconds>
X-EventRelay-Signature: v1=<hex hmac-sha256>
```
