# EventRelay — Chaos Testing Strategy

This document outlines the chaos testing plan for EventRelay, validating the system's "zero-loss" guarantee under network cuts, container crashes, and database failovers.

---

## 1. Chaos Principles for EventRelay

Reliability under failure is EventRelay's core value proposition. To verify these claims:

- **Assume anything can fail**: Every connection, node, queue, and database will eventually go offline.
- **Automated Verification**: Chaos tests are written as integration suites using **Toxiproxy** and **Testcontainers** and are executed in the CI pipeline.
- **Zero Data Loss Rule**: Under all simulated failures, the sum of delivered events, retrying events, and dead-lettered events must equal exactly the count of ingested events.

---

## 2. Test Architecture with Toxiproxy

Toxiproxy is used to simulate network failures between the EventRelay services and their dependencies:

```
[ Ingestion Task ] ──► [ Toxiproxy TCP Port ] ──► [ PostgreSQL Container ]
                                │ (Toxic injected: Latency / Cut)
                                ▼
                        [ Broken Socket ]
```

### Example Toxiproxy Java Test Setup
```java
@Testcontainers
class DatabaseChaosTest {

    @Container
    static ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withNetwork(Network.newNetwork());

    private Proxy dbProxy;

    @BeforeEach
    void setupProxy() {
        dbProxy = toxiproxy.getProxy(postgres, 5432);
    }

    @Test
    void testIngestDuringDbOutage() throws Exception {
        // 1. Ingest events successfully
        sendEvent("event-1");

        // 2. Inject network cut to PostgreSQL
        dbProxy.setConnectionCut(true);

        // 3. Attempt ingestion (should fail gracefully)
        assertThrows(DatabaseException.class, () -> sendEvent("event-2"));

        // 4. Restore network connection
        dbProxy.setConnectionCut(false);

        // 5. Ingest event-3 (should succeed)
        sendEvent("event-3");
    }
}
```

---

## 3. Chaos Test Scenarios

### Scenario 1: Worker Crash Mid-Delivery
- **Method**: Spawns a dispatch worker container, pushes 1,000 events to SQS, and terminates the worker task mid-delivery using Docker API (`docker kill`).
- **Success Criteria**: Every message is picked up by a replacement worker after the SQS visibility timeout expires. No events are lost.

### Scenario 2: High Packet Loss to Redis
- **Method**: Injects $30\%$ packet loss on the Redis port (`6379`) using Toxiproxy.
- **Success Criteria**: Ingestion fallback succeeds, switching to direct database validations for rate limits. Outbox writes continue without crashes.

### Scenario 3: Database Network Partition during Outbox Poll
- **Method**: Injects high latency ($5,000\text{ms}$) on the PostgreSQL port during a bulk outbox poll.
- **Success Criteria**: The poller times out, releases row locks, and retries the batch once latency resolves. No duplicate events are published to SQS.
