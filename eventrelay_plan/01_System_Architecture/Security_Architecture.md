# EventRelay — Security Architecture

This document details the security architecture of EventRelay, detailing the defense-in-depth model used to protect data, authenticate tenants, verify webhook payloads, and prevent server-side exploits.

---

## 1. Network Security & Partitioning

EventRelay operates on a strict zero-trust network topology within AWS:

- **Private Placement**: All compute workloads (Ingestion API, Dispatcher Workers) and data storage systems (RDS PostgreSQL, ElastiCache Redis) reside in private subnets with no direct routing to or from the public internet.
- **ALB Ingress**: Only the Application Load Balancer (ALB) is exposed to the public internet, terminating HTTPS connections on port `443` using TLS 1.3 and forwarding traffic to container port `8080` in the private subnets.
- **SSRF Prevention on Egress**: Dispatcher workers make outbound requests to public internet endpoints to deliver webhooks. To prevent Server-Side Request Forgery (SSRF) attacks targeting local AWS services or metadata endpoints, the outbound routes are:
  - Restricted to HTTP/HTTPS ports (`80`, `443`).
  - Screened by a network filter that immediately drops connections targeting local subnets (`127.0.0.0/8`, `10.0.0.0/8`, `192.168.0.0/16`, `172.16.0.0/12`) or the AWS Metadata Endpoint (`169.254.169.254`).
- **AWS VPC Endpoints**: For private communication with SQS, S3, and Secrets Manager, EventRelay uses VPC Interface/Gateway Endpoints to keep traffic within the AWS private backbone, avoiding NAT Gateway transit costs and public routes.

---

## 2. Ingestion Authentication (API Key Lifecycle)

All interactions with the Ingestion API require authentication via headers:

- **API Key Format**: Keys are generated using `SecureRandom` to output a high-entropy string prefixed by environment tags:
  - Live environment: `er_live_[a-zA-Z0-9]{32}`
  - Test environment: `er_test_[a-zA-Z0-9]{32}`
- **Secure Hash Storage**: API keys are never stored in plaintext within the database. EventRelay hashes them using `bcrypt` (or `PBKDF2-HMAC-SHA256` with 10,000 iterations). Plaintext is returned once upon creation and is masked (`er_live_...4a2c`) in all subsequent API calls.
- **API Key Scopes**: Keys are associated with specific permission scopes (e.g., `events:write`, `subscriptions:read`, `admin`) to restrict access.

---

## 3. Payload Integrity (HMAC Webhook Signing)

To ensure webhook receivers can prove that incoming payloads originated from EventRelay and were not tampered with in transit, the system signs all deliveries:

```
[ Worker Payload ] + [ Timestamp ] ──► [ HMAC-SHA256 Signer ] ──► [ Signature Header ]
                                                ▲
                                                │
                                      [ Tenant Secret Key ]
```

1. **Secret Generation**: Every tenant subscription is assigned a unique, high-entropy signing secret (e.g., `whsec_[a-zA-Z0-9]{32}`).
2. **Signature Computation**:
   - The worker generates a timestamp representing the dispatch instant (epoch seconds).
   - It forms a payload string by concatenating: `t={timestamp}.v1={payload_json_string}`.
   - It computes the signature using HMAC-SHA256 with the tenant's secret key:
     $$\text{Signature} = \text{HMAC-SHA256}(\text{Secret}, \text{Timestamp} + "." + \text{Payload})$$
3. **HTTP Header Delivery**: The signature is attached to the outgoing request via headers:
   - `X-EventRelay-Signature: t=1672531199,v1=a3b2c1d0...`
4. **Replay Protection**: The receiver verifies that the timestamp `t` is within $\pm 5$ minutes of its current system time to block replay attacks.

---

## 4. Secrets Management & Rotation

- **AWS Secrets Manager**: All environment credentials (database passwords, Redis access tokens, JWT signing keys, TLS certificates) are retrieved dynamically at runtime from AWS Secrets Manager using IAM Task Role authentication, leaving no plaintext credentials in source code or Git repositories.
- **Zero-Downtime Secret Rotation**:
  - EventRelay supports a **dual-secret window** for tenant signing secrets.
  - During rotation, the database holds both the `active_secret` and `previous_secret`.
  - The dispatcher signs the request with both secrets (creating two signature entries in `X-EventRelay-Signature`).
  - After a 24-hour overlap window, the previous key is fully revoked, allowing receivers to rotate secrets with zero message failure.

---

## 5. Threat Model & Mitigations (OWASP Top 10)

| Threat | Target | System Mitigation |
|--------|--------|-------------------|
| **SQL Injection (SQLi)** | Database | Complete parameterization of queries using Spring Data JPA; Hibernate entities enforce typing; no dynamic string concatenation in raw SQL. |
| **Server-Side Request Forgery (SSRF)** | Dispatcher Workers | Target URL resolution is parsed via Java's `InetAddress` to verify the IP does not resolve to local or private subnet ranges before opening the socket. |
| **Replay Attacks** | Webhook Receivers | Timestamp validation header (`X-EventRelay-Timestamp`) is checked against receiver clock; signatures older than 5 minutes are rejected. |
| **DDoS / Ingestion Exhaustion** | Ingestion API | Redis token-bucket rate limiter acts as a shield at the API gateway layer, dropping unauthorized or excessive requests before database queries execute. |
| **Data Leakage in Transit** | Networks | TLS 1.3 enforced on ALB ingress. Dispatcher workers refuse to deliver webhooks over unencrypted HTTP (except for local test environments). |
