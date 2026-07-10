# EventRelay — Security Best Practices Checklist

This document compiles the security configurations, deployment practices, and software checks required to run EventRelay securely in production.

---

## 1. Secure Headers and CORS Configuration

The ALB and Spring Security filters enforce strict HTTP security headers:

- **Strict-Transport-Security (HSTS)**: `max-age=63072000; includeSubDomains; preload` (forces TLS).
- **X-Content-Type-Options**: `nosniff` (prevents MIME-type sniffing).
- **X-Frame-Options**: `DENY` (blocks clickjacking).
- **Content-Security-Policy (CSP)**: Exposes only the APIs needed for the dashboard.
- **CORS Config**:
  - The API endpoints accept requests *only* from verified dashboard domains or tenant applications, rejecting wildcard (`*`) origins in production.

---

## 2. PII Masking and Logging Sanitization

To comply with HIPAA and GDPR regulations, no Personally Identifiable Information (PII) or keys must appear in logs:

- **API Key Masking**: Hashed keys are kept in the database; during logs, print only the prefix (e.g., `er_live_...a1c3`).
- **Payload Logging Policy**: The dispatcher worker logs event headers, HTTP status codes, and latency, but **never logs the payload JSON body** to CloudWatch or ElasticSearch.
- **Sensitive Fields Redaction**: Logs are filtered by a custom logback pattern to redact keys named `password`, `secret`, `token`, or `api_key`.

---

## 3. Deployment Hardening Checklist

| Security Area | Best Practice Target | Verification Tool |
|---------------|----------------------|-------------------|
| **OS Packages** | Use minimal container images (distroless or Alpine Linux) with no shell access. | Trivy / Snyk |
| **Java Sandbox** | Run the Spring Boot JVM process as a non-root user inside the container (`USER 1000`). | Dockerfile audit |
| **IAM Policies** | Enforce least-privilege policies. Avoid wildcard policies (`*`) in IAM role definitions. | AWS IAM Access Analyzer |
| **Secrets** | Database credentials and TLS keys must be fetched from AWS Secrets Manager. | Code scanning |
| **Data Encryption**| Encryption at rest enabled on RDS, ElastiCache, S3, and SQS using KMS keys. | AWS Config |

---

## 4. Software Composition Analysis (SCA)

To protect the software supply chain:
- **Dependency Scanning**: Run OWASP Dependency-Check or Snyk on every pull request.
- **Static Analysis (SAST)**: Run Checkstyle, PMD, and SpotBugs to enforce secure Java programming patterns.
- **Dependency Rules**: Automatically fail builds in CI if dependencies contain CVEs with a severity rating of **High** or **Critical**.
