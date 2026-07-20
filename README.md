# EventRelay

[![CI](https://github.com/sathwik7993/EventRelay/actions/workflows/ci.yml/badge.svg)](https://github.com/sathwik7993/EventRelay/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue)
![AWS SQS](https://img.shields.io/badge/AWS-SQS-ff9900)

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

## Measured performance

| Metric | Result |
|---|---|
| Ingest throughput | **1,117 events/s** (50 concurrent, 0% errors) |
| Ingest latency | p95 **75.6 ms**, p99 **89.5 ms** |
| Per-delivery cost | **4.09 ms** |
| Zero event loss under `kill -9` mid-flight | ✅ 150/150 delivered |

Load testing surfaced two bottlenecks that functional tests never would:
**bcrypt on every request** (21 → 1,117 req/s, 53×) and **unbatched SQS publishes**
(4.4× on end-to-end drain). Full methodology, before/after numbers, and honest
caveats in **[BENCHMARKS.md](./BENCHMARKS.md)**.

---

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
| `eventrelay-dashboard` | React + Vite (`:5173` dev, nginx in prod) | Delivery console: live stats, event log, delivery attempts, DLQ replay, subscriptions |

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
- [x] **M4 — Deploy & showcase.** Multi-stage Docker build for both services,
      single-host production stack (`docker-compose.prod.yml`), GitHub Actions CI
      (build + Testcontainers tests + image builds), a Terraform ECS Fargate
      reference architecture, and a deployment guide.
- [x] **M5 — Standards, hardening & benchmarks.** SSRF protection on target URLs,
      [Standard Webhooks](https://www.standardwebhooks.com/) signature compliance,
      OpenAPI/Swagger UI, and measured k6 benchmarks that uncovered and fixed two
      real bottlenecks (see [BENCHMARKS.md](./BENCHMARKS.md)).
- [x] **M6 — Tracing & console.** OpenTelemetry distributed tracing whose context
      survives the async boundary (one trace spans ingest → SQS → delivery), plus a
      React delivery console.

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

## Delivery console

A React + Vite dashboard for the people who operate this thing: live delivery stats,
the event log, per-attempt delivery history, one-click DLQ replay, and subscription
management. Designed as **liquid glass over claymorphism** in a light theme —
translucent blurred surfaces with specular edges floating above soft, puffy,
tactile controls.

```bash
cd eventrelay-dashboard
npm install
npm run dev        # http://localhost:5173, proxies /api to :8080
```

Paste the tenant API key on first load (kept in `localStorage`, sent as a bearer
token). In production the same app is built into an nginx image that proxies `/api`
to the API container, so the browser stays same-origin and no CORS config exists
anywhere in the stack.

## Distributed tracing

Metrics and logs answer "how many" and "what happened"; traces answer **"where did
the time go for *this* event"**. The hard part is that ingest and delivery happen in
different processes, minutes (and retries) apart — so the trace context is persisted
onto the event at ingest and resumed by the dispatcher:

```
trace 9f4d0223…
 ├─ [eventrelay-api]        http post /api/v1/events
 └─ [eventrelay-dispatcher] webhook.delivery   event.type=order.created
                                               delivery.attempt=1  http.status_code=200
```

The `traceparent` is also forwarded to the subscriber, so consumers can join the same
trace. Jaeger UI at `http://localhost:16686`:

```bash
docker compose --profile observability up -d jaeger
```

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

## Deployment

| Artifact | What it is |
|---|---|
| `Dockerfile` | One multi-stage build for both services (`--build-arg MODULE=eventrelay-api\|eventrelay-dispatcher`); non-root runtime on a JRE base |
| `docker-compose.prod.yml` | Single-host production stack (Postgres + Redis + both services) using **real AWS SQS** |
| `.github/workflows/ci.yml` | CI: Maven `verify` (Testcontainers integration tests run here), test-report/jar artifacts, and container image builds |
| `infra/terraform/` | ECS Fargate reference architecture: ALB, two services, IAM least-privilege, CloudWatch, autoscaling on SQS queue depth |
| `docs/DEPLOYMENT.md` | Step-by-step droplet deployment, scoped IAM policy, smoke test, and operations runbook |

```bash
cp .env.prod.example .env && $EDITOR .env
docker compose -f docker-compose.prod.yml up -d --build
```

**Why a droplet and not ECS:** ECS Fargate, RDS, and ElastiCache are not free-tier.
The demo runs on one DigitalOcean droplet (student credit) with **real AWS SQS**,
which *is* permanently free to 1M requests/month. The Terraform config is the
production design of record — the same images deploy to either target because all
configuration is environment-variable driven.

### Webhook delivery headers

Deliveries are signed two ways: the [Standard Webhooks](https://www.standardwebhooks.com/)
headers (the spec adopted by OpenAI, Anthropic, Twilio, Svix and others — so any
off-the-shelf verification library works), plus the original EventRelay headers for
backwards compatibility.

```
Content-Type: application/json
User-Agent: EventRelay/1.0

# Standard Webhooks
webhook-id: <message uuid>
webhook-timestamp: <unix seconds>
webhook-signature: v1,<base64 hmac-sha256>

# EventRelay legacy
X-Event-ID: <event uuid>
X-Event-Type: <event type>
X-EventRelay-Attempt: <n>
X-EventRelay-Timestamp: <unix seconds>
X-EventRelay-Signature: v1=<hex hmac-sha256>
```

Signing secrets are issued in spec format (`whsec_<base64>`). Standard Webhooks signs
`"{webhook-id}.{webhook-timestamp}.{body}"`; the legacy header signs
`"{timestamp}.{body}"`.

### Security

| Control | Implementation |
|---|---|
| **SSRF protection** | Target URLs are rejected if they use plain HTTP or resolve to loopback, private (10/8, 172.16/12, 192.168/16), link-local, CGNAT, IPv6 ULA, or the cloud metadata endpoint (169.254.169.254). Enforced at subscription creation **and** re-checked at delivery time (DNS-rebinding defence). Relaxable only via explicit dev flags. |
| **API keys** | Random 190-bit secrets, bcrypt-hashed at rest, looked up by non-secret prefix, verified in constant time, then cached briefly (see BENCHMARKS.md §2.1) |
| **Payload integrity** | HMAC-SHA256 with per-subscription secrets and a timestamp for replay protection |
| **Tenant isolation** | Every query is tenant-scoped; cross-tenant reads return 404 rather than leaking existence |

### API docs
Interactive Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`.
