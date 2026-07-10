# EventRelay — High Availability Design

This document details the high availability (HA) and disaster recovery (DR) architecture of the EventRelay platform. It describes how the system maintains continuous operation, isolates faults, and guarantees data durability under infrastructure failure.

---

## 1. Resilience Metrics: RTO and RPO

EventRelay is engineered to satisfy the following Recovery Time Objective (RTO) and Recovery Point Objective (RPO) targets:

| Service / Data Store | Target RTO (Max Downtime) | Target RPO (Max Data Loss) | HA Implementation Mechanism |
|----------------------|---------------------------|----------------------------|-----------------------------|
| **Ingestion API** | $< 1$ Minute | $0$ (Stateless service) | Multi-AZ ALB + Auto-Scaling ECS Tasks |
| **Dispatcher Workers**| $< 2$ Minutes | $0$ (Stateless consumers) | ECS Auto-Scaling + SQS visibility redrive |
| **Event Database (RDS)**| $< 60$ Seconds | $0$ (Synchronous Multi-AZ) | AWS RDS Multi-AZ Automatic Failover |
| **In-Flight Queues (SQS)**| $0$ Seconds | $0$ (Fully managed durability) | AWS SQS multi-region standard replication |
| **Cache & Limits (Redis)**| $< 30$ Seconds | Transient (Loss acceptable) | ElastiCache Redis replication with Auto-Failover |

---

## 2. Multi-AZ Service Architecture

EventRelay is deployed across multiple AWS Availability Zones (AZs) in an active-active model:

```
               [ Internet / DNS Route 53 ]
                            │
               [ Application Load Balancer ]
               ┌────────────┴────────────┐
       [ Availability Zone A ]   [ Availability Zone B ]
       - Ingest Service Task     - Ingest Service Task
       - Dispatcher Worker       - Dispatcher Worker
       - Redis Primary Node      - Redis Replica Node
       - RDS Primary DB          - RDS Standby DB (Sync)
```

- **Stateless Services**: The Ingestion Service and Dispatcher Workers run in both AZ-A and AZ-B. If an entire Availability Zone experiences an outage, AWS Route 53 and ALB route traffic away from the affected zone within seconds, and the ECS service automatically scales up tasks in the surviving AZ.
- **Queue Durability**: AWS SQS stores all messages across multiple AZs automatically. If an AZ goes down, messages are never lost and remain available for worker consumption in other zones.

---

## 3. Database Failover & Replication

- **Synchronous Replication**: AWS RDS PostgreSQL is configured in a Multi-AZ deployment. Writes to the primary database instance are synchronously replicated to a standby instance in a different AZ.
- **Automatic Failover**: If the primary database instance fails, RDS automatically updates the DNS record of the database endpoint to point to the standby replica. The failover process typically completes in 30 to 60 seconds.
- **HikariCP Connection Recovery**: The database connection pool on the ECS tasks is configured to handle temporary connection dropouts:
  ```yaml
  spring:
    datasource:
      hikari:
        connection-timeout: 10000 # 10 seconds to establish connection
        validation-timeout: 3000   # 3 seconds to validate connection
        idle-timeout: 600000       # 10 minutes max idle time
        max-lifetime: 1800000      # 30 minutes max lifetime
  ```

---

## 4. Graceful Shutdown & Work Recovery

During scale-in events or service updates, dispatcher workers must shut down gracefully to prevent terminating in-flight HTTP POST requests:

- **SIGTERM Handling**: When ECS stops a Fargate task, it sends a `SIGTERM` signal. The worker interceptor catches this signal and:
  1. Stops polling new messages from SQS.
  2. Allows active HTTP delivery threads to complete their current requests (up to a 30-second grace period).
  3. Rejects any uncompleted messages back to SQS (NACK) so they are immediately available for other workers.
- **Visibility Timeout Fallback**: If a worker node crashes abruptly (e.g., physical hardware failure), the message remains in an "in-flight" state in SQS. After the **Visibility Timeout** (configured to 60 seconds) expires, the message automatically returns to the queue for another worker to process, guaranteeing **at-least-once delivery**.

---

## 5. Circuit Breaking & Cascading Failure Prevention

To prevent slow or down webhook receivers from consuming all system resources (such as database connections or SQS polling slots), EventRelay integrates Resilience4j circuit breakers:

- **Closed State (Normal)**: All webhooks are delivered.
- **Open State (Failure)**: If a specific subscription endpoint returns `5xx` errors or times out for $50\%$ of requests over a sliding window of 100 attempts, the circuit breaker opens.
- **Behavior in Open State**: The worker immediately marks incoming events for this subscription as `FAILED` (with retry scheduled) without executing the HTTP POST request, preventing dispatcher threads from blocking.
- **Half-Open State (Recovery)**: After 30 seconds, the circuit breaker allows a small probe batch (5 requests) to test receiver recovery. If successful, the circuit closes; otherwise, it returns to the open state.
