# EventRelay — Health and Status APIs

This document outlines the system health, readiness, and internal status endpoints configured in EventRelay, used by monitoring agents and orchestrators (like Kubernetes or ECS).

---

## 1. Spring Boot Actuator Probes

EventRelay implements standardized health endpoints under Spring Boot Actuator:

### Liveness Probe (Is the process alive?)
- **Endpoint**: `GET /health/live`
- **Response (`200 OK`)**:
```json
{
  "status": "UP"
}
```

### Readiness Probe (Can the service accept traffic?)
- **Endpoint**: `GET /health/ready`
- **Verification checks**:
  - PostgreSQL database connection check.
  - Redis connection check.
  - SQS client credential verification.
- **Response (`200 OK`)**:
```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "redis": { "status": "UP" },
    "sqs": { "status": "UP" }
  }
}
```
- **Error Response (`503 Service Unavailable`)**: Returns if database or cache is down. The Load Balancer (ALB) intercepts this and stops sending traffic to the node.

---

## 2. Operational Metrics Endpoint

- **Endpoint**: `GET /api/v1/status`
- **Access**: Internal network only.
- **Response (`200 OK`)**:
```json
{
  "system_status": "HEALTHY",
  "metrics": {
    "active_connections": 14,
    "sqs_queue_depth": 12,
    "sqs_dlq_depth": 0,
    "ingest_rate_1m": 450.2,
    "delivery_success_rate_5m": 99.98
  }
}
```
- **Prometheus Export**: Actuator exports scrape-ready metrics under `/actuator/prometheus` for collection by Prometheus servers.
