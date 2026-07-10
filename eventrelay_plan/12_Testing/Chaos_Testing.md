# EventRelay — Chaos Testing Suite Execution

This document details the chaos testing scripts, environment profiles, and Testcontainers execution used to run reliability verification tests in CI pipelines.

---

## 1. Testing Reliability under Failure

While standard integration tests verify happy paths, EventRelay's reliability guarantees must hold under network failure and infrastructure crashes.
- Chaos tests run as standard JUnit integration tests.
- We utilize Shopify's **Toxiproxy** to simulate network latency, rate limits, and packet drops between the microservices and databases.
- We utilize **Testcontainers** to spin up actual PostgreSQL, Redis, and LocalStack instances in Docker containers during test compilation.

---

## 2. Chaos Test Execution Code (JUnit 5)

Below is the Java test code verifying that events are not lost when Redis becomes unreachable mid-ingestion:

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("chaos-test")
public class CacheChaosIntegrationTest {

    @Container
    static ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379)
        .withNetwork(Network.newNetwork());

    private Proxy redisProxy;

    @BeforeEach
    void setupProxy() {
        redisProxy = toxiproxy.getProxy(redis, 6379);
    }

    @Test
    void testIngestFallsBackOnCacheOutage() throws Exception {
        // 1. Send request (Success)
        submitEvent("key-1");

        // 2. Simulate complete Redis outage via proxy cut
        redisProxy.setConnectionCut(true);

        // 3. Submit request (System must fall back to PostgreSQL and succeed)
        submitEvent("key-2");

        // 4. Restore Redis connection
        redisProxy.setConnectionCut(false);

        // 5. Submit request (System uses cache again)
        submitEvent("key-3");
    }
}
```

---

## 3. Chaos Success Criteria

To pass CI gate verification, chaos execution runs must verify:
- **No Lost Messages**: Sum of delivered + retrying + dead-lettered events must match exact ingestion count.
- **Bounded Duplication**: Under network cuts, duplicate delivery rate must remain $< 1.5\%$ (confirming successful deduplication).
- **Self-Healing Time**: The system must fully restore normal operation within 10 seconds of network recovery without requiring manual restarts.
