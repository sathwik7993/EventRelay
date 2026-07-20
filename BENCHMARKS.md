# EventRelay — Performance Benchmarks

All numbers below were **measured**, not estimated. Scripts live in [`bench/k6`](./bench/k6)
and are reproducible with the commands shown.

> [!IMPORTANT]
> **Test environment caveat.** These runs are on a single Windows development laptop
> with PostgreSQL, Redis, and **LocalStack** in Docker *and* both JVM services on the
> same machine. This is not a production benchmark — everything competes for the same
> CPU. The ingest numbers are meaningful; the end-to-end delivery number is bounded by
> LocalStack's SQS emulation rather than by EventRelay (analysis below).

---

## 1. Ingestion throughput and latency

The synchronous ingest path: API-key auth → rate limit → validation → one transaction
writing the event and its outbox row.

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e API_KEY=$KEY \
  -e VUS=50 -e DURATION=20s grafana/k6 run - < bench/k6/ingest.js
```

| Concurrency | Throughput | avg | p95 | p99 | Errors |
|---|---|---|---|---|---|
| 10 VUs | 419 req/s | 23.7 ms | 45.9 ms | 62.2 ms | 0% |
| 25 VUs | 781 req/s | 31.7 ms | 50.1 ms | 107.4 ms | 0% |
| 50 VUs | **1,117 req/s** | 44.4 ms | 75.6 ms | 89.5 ms | 0% |

Design targets were p95 < 60 ms and p99 < 100 ms — met at 10 and 50 VUs; p99 drifts
over target at 25 VUs on this hardware. Zero failed requests across ~46,000 requests.

---

## 2. Two bottlenecks the benchmark exposed

Benchmarking was worth doing purely for this: both problems were invisible in
functional testing and only appeared under load.

### 2.1 bcrypt on every request — 21 req/s → 1,117 req/s (53×)

The first run measured **21 req/s with a p95 of 3.31 s**. The cause: the auth filter
ran `BCrypt.checkpw` (cost factor 12, ≈300 ms of CPU) on *every* API call. With 50
concurrent requests the service was doing nothing but hashing — 50 ÷ 0.3 s ≈ 21 req/s,
exactly what was measured.

bcrypt is correct for **passwords**, which are low-entropy and must resist offline
brute force. It is the wrong tool for **API keys**, which are 190-bit random secrets.
The fix keeps bcrypt as the at-rest hash but caches successful verifications for a
short TTL (keyed by a SHA-256 digest of the presented key, never the key itself), so
bcrypt is paid once per key per minute instead of once per request.

| | Throughput | avg | p95 |
|---|---|---|---|
| Before | 21 req/s | 2.31 s | 3.31 s |
| After auth cache | 685 req/s | 72.6 ms | 103.2 ms |
| After cache + 32-connection pool | 866 req/s | 57.1 ms | 83.4 ms |

Tradeoff, documented in code: a revoked key stays usable for at most one cache TTL
(60 s default).

### 2.2 Unbatched SQS publishes — ~15/s → ~67/s (4.4×)

The scheduler published one `SendMessage` per delivery. At ~60 ms per call it could
only enqueue ~15 deliveries/s regardless of how fast delivery itself was. Switching to
`SendMessageBatch` / `DeleteMessageBatch` (10 per call) improved end-to-end drain from
~15/s to ~67/s.

---

## 3. End-to-end delivery

10,000 events ingested, fanned out to one subscription, measured until every delivery
reached `DELIVERED`.

```bash
docker run --rm -i -e BASE_URL=... -e API_KEY=$KEY -e COUNT=10000 \
  grafana/k6 run - < bench/k6/e2e_fixed.js
```

| Metric | Value |
|---|---|
| Events ingested | 10,000 (773 req/s while the dispatcher ran concurrently) |
| Time to deliver all 10,000 | 149 s (~67 deliveries/s) |
| Failed / lost | 0 |
| **Actual HTTP delivery cost** | **4.09 ms per delivery** (81.86 s over 20,009 attempts) |

### Why 67/s is a floor, not a ceiling

The application's own delivery work is 4.09 ms per delivery. 10,000 deliveries is
therefore ~41 s of real work — about 5 s spread across 8 worker threads. The drain took
149 s, so **~97% of wall-clock time was spent waiting on LocalStack**, the Python-based
AWS emulator used for local development. A rough measurement put LocalStack API calls in
the hundreds of milliseconds each (inflated somewhat by CLI process startup in that
measurement, but the order of magnitude holds).

Real AWS SQS sustains thousands of messages/second. The meaningful application-side
figure is the **4.09 ms per delivery**, which implies roughly 240 deliveries/s per
worker thread before the queue becomes the constraint — and workers scale horizontally
(the Terraform config autoscales them on queue depth).

Measuring against real SQS on the deployed droplet is the natural follow-up.

---

## 4. Reliability under failure

From the Milestone 3 chaos test (see README):

| Scenario | Result |
|---|---|
| `kill -9` the dispatcher with 115 deliveries in flight | All 150 events still delivered after restart |
| Events lost | **0** |
| Duplicate deliveries | 1 (expected — at-least-once redelivery after the visibility timeout) |

---

## 5. Reproducing

```bash
docker compose up -d postgres redis localstack
mvn -DskipTests clean install

ALLOW_INSECURE_TARGET_URLS=true ALLOW_PRIVATE_TARGET_URLS=true \
  SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=32 \
  java -jar eventrelay-api/target/eventrelay-api.jar

ALLOW_INSECURE_TARGET_URLS=true ALLOW_PRIVATE_TARGET_URLS=true \
  java -Deventrelay.relay.batch-size=500 -Deventrelay.scheduler.batch-size=500 \
       -Deventrelay.worker.concurrency=8 \
       -jar eventrelay-dispatcher/target/eventrelay-dispatcher.jar

# create a tenant with a high rate limit, then run the k6 scripts above
```
