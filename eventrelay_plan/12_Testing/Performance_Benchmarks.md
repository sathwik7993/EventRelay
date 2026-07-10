# EventRelay — Performance Benchmarks

This document records the performance benchmark criteria, JMH microbenchmark configurations, and target throughput benchmarks for EventRelay.

---

## 1. Ingestion Performance Microbenchmarks (JMH)

To ensure validation and serialization libraries do not introduce latency on the write path, EventRelay runs microbenchmarks using the **Java Microbenchmark Harness (JMH)**:

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
public class SerializerBenchmark {

    private ObjectMapper objectMapper;
    private EventPayload sampleEvent;

    @Setup
    public void setup() {
        objectMapper = new ObjectMapper();
        sampleEvent = new EventPayload("payment.succeeded", Map.of("id", "evt_123"));
    }

    @Benchmark
    public String testJacksonSerialization() throws Exception {
        return objectMapper.writeValueAsString(sampleEvent);
    }
}
```

- **Execution Gate**: JMH benchmarks run as a compile task. Builds fail if average execution time for serialization and validation exceeds $5\text{ microseconds}$ per event.

---

## 2. Ingestion System Benchmarks (End-to-End)

System throughput benchmarks measure ingestion limits under resource limits:

| Compute Configuration | Ingestion Target (eps) | Delivery Target (eps) | Ingestion Latency (p99) |
|-----------------------|------------------------|-----------------------|-------------------------|
| **1 Task Fargate** (0.5 vCPU) | $1,200$ | $800$ | $< 25\text{ms}$ |
| **4 Task Fargate** (2.0 vCPU) | $4,500$ | $3,200$ | $< 18\text{ms}$ |
| **10 Task Fargate** (5.0 vCPU) | $11,000$ | $8,500$ | $< 12\text{ms}$ |

- **Resource Limits**: Benchmarks are run on AWS using isolated RDS instances with `gp3` storage scaled to 3,000 IOPS.

---

## 3. Database Benchmarks

To optimize write performance, PostgreSQL outbox insert latencies are benchmarked:
- Target: Outbox inserts must execute under $1.5\text{ms}$ at 5,000 concurrent inserts.
- Index lookup times for active partitions must remain below $0.5\text{ms}$.
- This performance is achieved using composite indices: `CREATE INDEX CONCURRENTLY ON outbox(status, created_at)`.
