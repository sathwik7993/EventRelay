# EventRelay — Glossary

This glossary defines the technical terms, architectural patterns, and concepts used throughout the EventRelay platform.

---

## A

#### API Key
A secure token used by event producers to authenticate requests sent to the EventRelay Ingestion API. Keys are generated with a prefix denoting their environment (e.g., `er_live_` or `er_test_`) and stored using bcrypt hashing.

#### At-Least-Once Delivery
A message delivery guarantee stating that a message will be delivered to the receiver one or more times. EventRelay guarantees at-least-once delivery to ensure no messages are lost, even in the event of network failure or worker crashes.

#### Automatic Replay
A system-triggered process where failed deliveries (stored in the Dead-Letter Queue) are automatically retried according to a tenant's configured recovery schedule or system-wide disaster recovery policies.

---

## B

#### Backpressure
A software flow control mechanism where a downstream consumer signals the upstream producer to slow down message rate when internal resource limits (such as queue capacity or memory) are reached.

#### Backoff Algorithm
The mathematical formula used to calculate the delay before a failed delivery is retried. See *Exponential Backoff*.

#### Blue-Green Deployment
A deployment methodology that uses two identical production environments (Blue and Green). One is live, and the other is idle. New releases are deployed to the idle environment, tested, and cut over using load balancer rules to achieve zero downtime.

---

## C

#### Circuit Breaker
A design pattern used to detect failures and prevent cascading errors. In EventRelay, if a receiver's URL returns consecutive errors, the circuit breaker opens, temporarily pausing delivery attempts to that destination without consuming worker threads.

#### Clock Skew
The difference in time readings between two different servers or network nodes. EventRelay validates timestamps within a ±5-minute window to account for acceptable clock skew.

#### Connection Pooling
A database caching mechanism that maintains a pool of open database connections (e.g., via HikariCP), allowing connection reuse and avoiding the overhead of establishing a new TCP connection for every query.

#### Consumer-Side Deduplication
The practice where a webhook receiver checks incoming delivery IDs against a local cache or database constraint to ignore duplicate deliveries, which are a side-effect of at-least-once systems.

---

## D

#### Dead-Letter Queue (DLQ)
A dedicated storage queue (SQS standard queue) and database table where EventRelay routes events that have failed all configured delivery attempts. This allows manual inspection and recovery.

#### Dead-Letter Manager
The service responsible for monitoring the DLQ, exposing APIs to inspect failed payloads, and managing event replay or discarding.

#### Decorrelated Jitter
An algorithm for introducing randomness into retry intervals. It computes a delay based on the previous delay and a random range, minimizing overlapping retries and thundering herds.

#### Delivery Attempt
An individual HTTP POST request made by a dispatcher worker to a subscription's target URL.

#### Delivery State
The current stage of an event in the dispatch lifecycle (e.g., `PENDING`, `DISPATCHING`, `DELIVERED`, `FAILED`, `RETRYING`, `EXHAUSTED`, `DEAD_LETTERED`, `REPLAYED`).

#### Dispatcher Worker
A multi-threaded consumer process that pulls messages from AWS SQS, constructs HTTP requests, signs them with HMAC signatures, and executes deliveries.

---

## E

#### Equal Jitter
A jitter strategy that adds a uniform random value to a calculated base delay, keeping the delay bounded between the base delay and twice that value.

#### Event Ingestion
The process where an external system submits an event to EventRelay via the `POST /api/v1/events` endpoint.

#### Event Log
A read-heavy, write-once table in PostgreSQL storing every event ingested by the platform, acting as the system of record for audit and replay.

#### Event Type
A dot-notation string identifying the category of an event (e.g., `payment.succeeded`, `user.created`). Subscriptions filter events by subscribing to specific event types or wildcards (`payment.*`).

#### Exponential Backoff
A retry scheduling strategy where the delay between attempts increases exponentially (e.g., 1s, 2s, 4s, 8s...) to give failing endpoints time to recover.

---

## F

#### Full Jitter
A jitter strategy that computes the maximum exponential backoff limit and chooses a random value between 0 and that maximum limit, offering maximum distribution of retry intervals.

#### Flyway
An open-source database migration tool used by EventRelay to apply version-controlled SQL schema updates predictably across environments.

---

## H

#### HMAC (Hash-based Message Authentication Code)
A cryptographic signature algorithm combining a secret key and a message payload. EventRelay uses HMAC-SHA256 request signing to guarantee payload integrity and verify the sender's identity.

---

## I

#### Idempotency Key
A unique string provided by the API client in the header (`X-Idempotency-Key`) to prevent double-processing of duplicate API calls.

#### Ingestion Service
The Spring Boot microservice responsible for receiving client requests, authenticating them, verifying idempotency, and writing to the Transactional Outbox.

#### In-Flight Message
An SQS message that has been retrieved from the queue by a dispatcher worker but has not yet been deleted or returned to the queue (remains invisible to other workers).

---

## J

#### Jitter
Random noise added to retry calculations to distribute HTTP requests over time, preventing synchronized thundering herd problems on destination endpoints.

#### JWT (JSON Web Token)
A compact, URL-safe means of representing claims to be transferred between two parties. Used by EventRelay to secure dashboard user sessions.

---

## L

#### Leader Election
The process of designating a single node as the manager of a cluster or cron task. EventRelay uses Redis-based distributed locks to select a coordinator for outbox polling.

#### LocalStack
A cloud service emulator that runs in a local container, allowing testing of AWS SQS, S3, and IAM APIs offline.

---

## M

#### Message Lifecycle
The complete path of an SQS message from publishing, through visibility transitions, worker consumption, and eventual deletion or DLQ relocation.

#### Multi-Tenancy
An architecture where a single instance of a software application serves multiple customers (tenants). EventRelay enforces data and rate isolation between tenants.

---

## O

#### Outbox Pattern
A reliable distributed transactions pattern where updates to business entities and outbox logs are performed within a single database transaction, ensuring the outbox poller eventually publishes all events.

---

## P

#### Poison Message
A message in the queue that cannot be processed due to corruption, invalid format, or bugs, causing workers to fail repeatedly when consuming it.

---

## R

#### Rate Limiting
The control of event throughput. EventRelay uses a Redis Token Bucket script to restrict requests per tenant and protect downstream APIs.

#### Replay Attack
A network security exploit where a valid data transmission is maliciously or fraudulently repeated or delayed. EventRelay prevents this by validating request timestamps.

#### Replay Engine
The module responsible for fetching historical events (or DLQ records) and re-queuing them for delivery.

---

## S

#### Secret Rotation
A security policy of periodically invalidating old encryption keys or API keys and replacing them with new ones to mitigate credential leaks.

#### SSRF (Server-Side Request Forgery)
An exploit where an attacker forces a server-side application to make HTTP requests to an arbitrary domain, often targetting local IPs. EventRelay prevents SSRF by blocking localhost/private subnet targets.

---

## T

#### Tenant
A customer organization configured in EventRelay, possessing isolated configurations, API keys, and dedicated rate limits.

#### Token Bucket
An algorithm used to control rate limits. A bucket has a maximum capacity and is refilled with tokens at a constant rate; every incoming request consumes a token.

#### Transactional Outbox
See *Outbox Pattern*.

---

## V

#### Visibility Timeout
The duration during which AWS SQS prevents other consumers from receiving a message after a worker has fetched it.
