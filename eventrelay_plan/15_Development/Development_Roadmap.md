# EventRelay — Detailed Development Roadmap

This document outlines the detailed development phases, sprint plans, and milestones for EventRelay's implementation.

---

## 1. Project Phase Breakdown

The build plan consists of 6 core milestones spanning 12 weeks:

- **Sprint 1-2: Core Ingestion (Weeks 1-4)**: Relational schema, Flyway migrations, API Key auth system, and basic Event log write path.
- **Sprint 3-4: Outbox Pattern (Weeks 5-8)**: Outbox polling service running `SELECT FOR UPDATE SKIP LOCKED`, and local dispatch delivery.
- **Sprint 5-6: Queue & Reliability (Weeks 9-12)**: AWS SQS integration, exponential backoff retries with Full Jitter, and visibility timeout sync.
- **Sprint 7-8: Security & Separation (Weeks 13-16)**: HMAC-SHA256 request signing, Redis token-bucket rate limiting, and Circuit Breakers.
- **Sprint 9-10: DLQ & Replay (Weeks 17-20)**: SQS Dead-Letter Queue redrive integration, Replay Service APIs, and audit trails.
- **Sprint 11-12: Cloud & Ops (Weeks 21-24)**: Terraform modules, ECS Fargate deployments, Prometheus observability dashboard, and GitHub Actions CI/CD pipelines.
