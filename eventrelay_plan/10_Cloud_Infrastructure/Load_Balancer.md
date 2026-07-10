# EventRelay — Load Balancer Configuration

This document details the configuration of the AWS Application Load Balancer (ALB) used to route ingestion and administrative traffic to EventRelay services.

---

## 1. Target Groups Setup

The ALB splits incoming traffic between the two ECS Fargate services:

| Target Group Name | Target Type | Container Port | Protocol | Health Check Path | Action on Success |
|-------------------|-------------|----------------|----------|-------------------|-------------------|
| `tg-eventrelay-ingest` | IP | `8080` | HTTP | `/health/ready` | Dynamic traffic routing. |
| `tg-eventrelay-dashboard` | IP | `3000` | HTTP | `/` | Serves dashboard UI. |

- **Health Check Configuration**:
  - Interval: `15 seconds`.
  - Timeout: `5 seconds`.
  - Healthy Threshold: `2 consecutive successes`.
  - Unhealthy Threshold: `3 consecutive failures`.
  - Return Code: `200` (Readiness check returns 200 when system is ready).

---

## 2. Listener Routing Rules

The ALB listens on port `443` with TLS 1.3 enforced.

- **Routing Rules**:
  - `IF PathPattern = "/api/v1/*" THEN Forward to tg-eventrelay-ingest`
  - `IF PathPattern = "/dashboard/*" THEN Forward to tg-eventrelay-dashboard`
  - `ELSE Redirect HTTP to HTTPS (Port 80 to Port 443)`

---

## 3. ALB Security & Logging

- **AWS WAF (Web Application Firewall) Integration**:
  - WAF is attached to the ALB to drop requests containing common exploit payloads (SQL injection, XSS) and restrict volumetric request rate per IP.
- **Connection Draining (Deregistration Delay)**:
  - Configured to `30 seconds`. During deployments, the ALB allows in-flight connections to terminate gracefully before removing a container task.
- **Access Logs**:
  - Enable access logging to `s3://eventrelay-alb-logs-{account-id}/`. Logs are used for performance analytics and security audits.
