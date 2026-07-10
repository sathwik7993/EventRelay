# EventRelay — Performance Benchmark Baseline

This document outlines the performance benchmarks, latency limits, and resource utilization targets for the EventRelay platform.

---

## 1. Performance Target Metrics

Under load test validation, EventRelay must meet the following performance targets:

| Operation | Target Throughput | Latency Target (p95) | Latency Target (p99) |
|-----------|-------------------|----------------------|----------------------|
| **Event Ingestion API** | $5,000\text{ events/sec}$ | $< 25\text{ms}$ | $< 50\text{ms}$ |
| **Outbox Log Database Write**| $5,000\text{ writes/sec}$ | $< 2\text{ms}$ | $< 5\text{ms}$ |
| **SQS Dequeue & Dispatch** | $4,000\text{ events/sec}$ | $< 120\text{ms}$ (first attempt)| $< 250\text{ms}$ |
| **Redis Cache Configuration Lookup**| $25,000\text{ calls/sec}$ | $< 1.2\text{ms}$ | $< 3\text{ms}$ |

---

## 2. Resource Utilization Limits

Under peak load of $10,000\text{ events/sec}$:
- **JVM Heap Memory**: Must stabilize below $80\%$ of Fargate container task allocation.
- **CPU Utilization**: ECS Fargate auto-scaling targets average CPU utilization below $70\%$.
- **Database Connection pool**: Active connection count (HikariCP) must stay under $85\%$ of the database limit.

---

## 3. Verification Method

Benchmarks are verified before major releases:
1. Deploy build to performance environment (prod replica).
2. Spin up k6 runners on 3 distinct client machines.
3. Inject load matching the target throughput for 2 hours.
4. Export Grafana data logs and verify that all latency targets are met.
