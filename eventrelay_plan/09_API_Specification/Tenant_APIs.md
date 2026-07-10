# Tenant APIs

> [!NOTE]
> Tenant APIs manage the lifecycle and configuration of tenants in EventRelay. Each tenant is an isolated unit with its own API keys, subscriptions, rate limits, and retry policies. Tenants are the top-level organizational boundary for multi-tenant isolation.

---

## Table of Contents

- [Tenant Model](#tenant-model)
- [POST /api/v1/tenants — Register Tenant](#post-apiv1tenants--register-tenant)
- [GET /api/v1/tenants/{id} — Get Tenant](#get-apiv1tenantsid--get-tenant)
- [PATCH /api/v1/tenants/{id} — Update Tenant](#patch-apiv1tenantsid--update-tenant)
- [DELETE /api/v1/tenants/{id} — Soft Delete Tenant](#delete-apiv1tenantsid--soft-delete-tenant)
- [Tenant Configuration Reference](#tenant-configuration-reference)
- [Signing Secret Management](#signing-secret-management)
- [Error Responses](#error-responses)
- [Production Considerations](#production-considerations)

---

## Tenant Model

```mermaid
erDiagram
    TENANT ||--o{ API_KEY : has
    TENANT ||--o{ SUBSCRIPTION : owns
    TENANT ||--o{ EVENT : produces
    TENANT {
        string id PK
        string name
        string slug
        string email
        string status
        jsonb config
        string signing_secret_hash
        timestamp created_at
        timestamp deleted_at
    }
```

### Tenant Statuses

| Status | Description |
|---|---|
| `active` | Fully operational; can submit events and receive deliveries |
| `suspended` | Temporarily disabled; events rejected with `403`; existing deliveries paused |
| `deleted` | Soft-deleted; data retained for 30 days, then purged |

---

## POST /api/v1/tenants — Register Tenant

Registers a new tenant and provisions an initial admin API key and signing secret.

**Authorization:** Platform admin key or self-service registration (if enabled)

### Request

```http
POST /api/v1/tenants HTTP/1.1
Host: api.eventrelay.io
Content-Type: application/json
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

```json
{
  "name": "Acme Corporation",
  "slug": "acme-corp",
  "email": "webhooks@acme.com",
  "config": {
    "rate_limit": {
      "events_per_second": 500,
      "burst_size": 1000
    },
    "retry_policy": {
      "max_attempts": 10,
      "initial_delay_ms": 1000,
      "max_delay_ms": 3600000,
      "backoff_multiplier": 3.0,
      "jitter_factor": 0.2
    },
    "delivery": {
      "timeout_ms": 30000,
      "max_payload_size_kb": 256,
      "allowed_ip_ranges": ["0.0.0.0/0"]
    }
  },
  "metadata": {
    "plan": "enterprise",
    "region": "us-east-1"
  }
}
```

### Request Schema

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `name` | `string` | Yes | 2–128 chars | Human-readable tenant name |
| `slug` | `string` | Yes | 2–64 chars, `^[a-z0-9-]+$` | URL-safe unique identifier |
| `email` | `string` | Yes | Valid email | Primary contact email |
| `config` | `object` | No | See configuration reference | Tenant configuration (defaults applied) |
| `metadata` | `object` | No | Max 20 keys | Arbitrary key-value metadata |

### Response — `201 Created`

```json
{
  "data": {
    "id": "tenant_01H5K3ABCDEF",
    "name": "Acme Corporation",
    "slug": "acme-corp",
    "email": "webhooks@acme.com",
    "status": "active",
    "config": {
      "rate_limit": {
        "events_per_second": 500,
        "burst_size": 1000
      },
      "retry_policy": {
        "max_attempts": 10,
        "initial_delay_ms": 1000,
        "max_delay_ms": 3600000,
        "backoff_multiplier": 3.0,
        "jitter_factor": 0.2
      },
      "delivery": {
        "timeout_ms": 30000,
        "max_payload_size_kb": 256,
        "allowed_ip_ranges": ["0.0.0.0/0"]
      }
    },
    "signing_secret": "whsec_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6",
    "initial_api_key": {
      "id": "key_01H5K3GHIJKL",
      "key": "sk_live_EXAMPLE_redacted_key",
      "scopes": ["admin"]
    },
    "created_at": "2026-07-10T04:00:00Z"
  },
  "_links": {
    "self": { "href": "/api/v1/tenants/tenant_01H5K3ABCDEF" },
    "keys": { "href": "/api/v1/auth/keys" },
    "subscriptions": { "href": "/api/v1/subscriptions" },
    "events": { "href": "/api/v1/events" }
  }
}
```

> [!CAUTION]
> The `signing_secret` and `initial_api_key.key` are returned **only once** at registration. Store them securely immediately.

### curl Example

```bash
curl -X POST https://api.eventrelay.io/api/v1/tenants \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "name": "Acme Corporation",
    "slug": "acme-corp",
    "email": "webhooks@acme.com"
  }'
```

---

## GET /api/v1/tenants/{id} — Get Tenant

Retrieves tenant details including current configuration and usage statistics.

**Authorization:** Bearer token with `admin` scope (same tenant) or platform admin

### Request

```http
GET /api/v1/tenants/tenant_01H5K3ABCDEF HTTP/1.1
Host: api.eventrelay.io
Authorization: Bearer sk_live_admin_key...
```

### Response — `200 OK`

```json
{
  "data": {
    "id": "tenant_01H5K3ABCDEF",
    "name": "Acme Corporation",
    "slug": "acme-corp",
    "email": "webhooks@acme.com",
    "status": "active",
    "config": {
      "rate_limit": {
        "events_per_second": 500,
        "burst_size": 1000
      },
      "retry_policy": {
        "max_attempts": 10,
        "initial_delay_ms": 1000,
        "max_delay_ms": 3600000,
        "backoff_multiplier": 3.0,
        "jitter_factor": 0.2
      },
      "delivery": {
        "timeout_ms": 30000,
        "max_payload_size_kb": 256,
        "allowed_ip_ranges": ["0.0.0.0/0"]
      }
    },
    "usage": {
      "active_subscriptions": 12,
      "api_keys": 3,
      "events_last_24h": 45230,
      "events_last_30d": 1234567,
      "dlq_size": 23,
      "delivery_success_rate_24h": 0.9987
    },
    "signing_secret_last_rotated_at": "2026-06-15T00:00:00Z",
    "created_at": "2026-01-01T00:00:00Z",
    "updated_at": "2026-07-09T12:00:00Z"
  },
  "_links": {
    "self": { "href": "/api/v1/tenants/tenant_01H5K3ABCDEF" },
    "update": { "href": "/api/v1/tenants/tenant_01H5K3ABCDEF", "method": "PATCH" },
    "keys": { "href": "/api/v1/auth/keys" },
    "subscriptions": { "href": "/api/v1/subscriptions" },
    "events": { "href": "/api/v1/events" },
    "dead-letter": { "href": "/api/v1/dead-letter" }
  }
}
```

> [!NOTE]
> The `signing_secret` is **never** returned in GET responses. Only the `signing_secret_last_rotated_at` timestamp is provided.

### curl Example

```bash
curl -s "https://api.eventrelay.io/api/v1/tenants/tenant_01H5K3ABCDEF" \
  -H "Authorization: Bearer sk_live_admin_key" | jq
```

---

## PATCH /api/v1/tenants/{id} — Update Tenant

Updates tenant details or configuration. Supports partial updates — only provided fields are changed.

**Authorization:** Bearer token with `admin` scope

### Request

```http
PATCH /api/v1/tenants/tenant_01H5K3ABCDEF HTTP/1.1
Host: api.eventrelay.io
Authorization: Bearer sk_live_admin_key...
Content-Type: application/json
```

```json
{
  "name": "Acme Corp (Updated)",
  "email": "new-webhooks@acme.com",
  "config": {
    "rate_limit": {
      "events_per_second": 1000,
      "burst_size": 2000
    },
    "retry_policy": {
      "max_attempts": 15
    }
  }
}
```

### Updatable Fields

| Field | Type | Description |
|---|---|---|
| `name` | `string` | Tenant display name |
| `email` | `string` | Primary contact email |
| `config.rate_limit` | `object` | Rate limiting configuration |
| `config.retry_policy` | `object` | Retry behavior for failed deliveries |
| `config.delivery` | `object` | Delivery settings (timeout, payload size, IP ranges) |
| `metadata` | `object` | Arbitrary metadata (full replacement) |

> [!IMPORTANT]
> Configuration changes take effect **immediately** for new events. In-flight deliveries continue using the configuration at the time they were queued.

### Response — `200 OK`

```json
{
  "data": {
    "id": "tenant_01H5K3ABCDEF",
    "name": "Acme Corp (Updated)",
    "slug": "acme-corp",
    "email": "new-webhooks@acme.com",
    "status": "active",
    "config": {
      "rate_limit": {
        "events_per_second": 1000,
        "burst_size": 2000
      },
      "retry_policy": {
        "max_attempts": 15,
        "initial_delay_ms": 1000,
        "max_delay_ms": 3600000,
        "backoff_multiplier": 3.0,
        "jitter_factor": 0.2
      },
      "delivery": {
        "timeout_ms": 30000,
        "max_payload_size_kb": 256,
        "allowed_ip_ranges": ["0.0.0.0/0"]
      }
    },
    "updated_at": "2026-07-10T04:10:00Z"
  },
  "message": "Tenant configuration updated successfully"
}
```

### curl Example

```bash
curl -X PATCH https://api.eventrelay.io/api/v1/tenants/tenant_01H5K3ABCDEF \
  -H "Authorization: Bearer sk_live_admin_key" \
  -H "Content-Type: application/json" \
  -d '{
    "config": {
      "rate_limit": { "events_per_second": 1000 }
    }
  }'
```

---

## DELETE /api/v1/tenants/{id} — Soft Delete Tenant

Soft-deletes a tenant. The tenant is immediately marked as `deleted`, all API keys are revoked, and event processing stops. Data is retained for 30 days for recovery, then permanently purged.

**Authorization:** Bearer token with `admin` scope or platform admin

### Request

```http
DELETE /api/v1/tenants/tenant_01H5K3ABCDEF HTTP/1.1
Host: api.eventrelay.io
Authorization: Bearer sk_live_admin_key...
```

### Response — `200 OK`

```json
{
  "data": {
    "id": "tenant_01H5K3ABCDEF",
    "name": "Acme Corporation",
    "status": "deleted",
    "deleted_at": "2026-07-10T04:15:00Z",
    "data_purge_at": "2026-08-09T04:15:00Z"
  },
  "message": "Tenant soft-deleted. Data will be permanently purged after 2026-08-09T04:15:00Z. Contact support to restore within 30 days."
}
```

### Soft Delete Effects

| Resource | Behavior |
|---|---|
| **API Keys** | All keys revoked immediately |
| **Subscriptions** | All subscriptions deactivated |
| **In-flight deliveries** | Cancelled; moved to DLQ |
| **Events** | Retained for 30 days (read-only via platform admin) |
| **DLQ entries** | Retained for 30 days |

### curl Example

```bash
curl -X DELETE https://api.eventrelay.io/api/v1/tenants/tenant_01H5K3ABCDEF \
  -H "Authorization: Bearer sk_live_admin_key"
```

---

## Tenant Configuration Reference

### Rate Limit Configuration

| Field | Type | Default | Min | Max | Description |
|---|---|---|---|---|---|
| `events_per_second` | `integer` | `100` | `1` | `10,000` | Sustained event submission rate |
| `burst_size` | `integer` | `200` | `1` | `50,000` | Maximum burst capacity |

#### Token Bucket Algorithm

```mermaid
flowchart LR
    A["Incoming Event"] --> B{"Tokens Available?"}
    B -->|Yes| C["Consume Token"]
    C --> D["Accept Event"]
    B -->|No| E["429 Too Many Requests"]

    F["Token Refill"] -->|events_per_second rate| G["Token Bucket"]
    G -->|max: burst_size| B
```

### Retry Policy Configuration

| Field | Type | Default | Min | Max | Description |
|---|---|---|---|---|---|
| `max_attempts` | `integer` | `10` | `1` | `30` | Maximum delivery attempts |
| `initial_delay_ms` | `integer` | `1000` | `100` | `60000` | Delay before first retry |
| `max_delay_ms` | `integer` | `3600000` | `1000` | `86400000` | Maximum delay between retries |
| `backoff_multiplier` | `float` | `3.0` | `1.5` | `10.0` | Exponential backoff multiplier |
| `jitter_factor` | `float` | `0.2` | `0.0` | `0.5` | Random jitter range (± percentage) |

#### Retry Schedule Example (Default Config)

| Attempt | Base Delay | With Jitter (±20%) | Cumulative Wait |
|---|---|---|---|
| 1 | 1s | 0.8–1.2s | ~1s |
| 2 | 3s | 2.4–3.6s | ~4s |
| 3 | 9s | 7.2–10.8s | ~13s |
| 4 | 27s | 21.6–32.4s | ~40s |
| 5 | 81s (~1.3 min) | 64.8–97.2s | ~2 min |
| 6 | 243s (~4 min) | 194.4–291.6s | ~6 min |
| 7 | 729s (~12 min) | 583.2–874.8s | ~18 min |
| 8 | 2187s (~36 min) | 1749.6–2624.4s | ~54 min |
| 9 | 3600s (capped) | 2880–4320s | ~1.5 hours |
| 10 | 3600s (capped) | 2880–4320s | ~2.5 hours |

### Delivery Configuration

| Field | Type | Default | Min | Max | Description |
|---|---|---|---|---|---|
| `timeout_ms` | `integer` | `30000` | `1000` | `60000` | HTTP delivery timeout per attempt |
| `max_payload_size_kb` | `integer` | `256` | `1` | `1024` | Maximum event payload size |
| `allowed_ip_ranges` | `string[]` | `["0.0.0.0/0"]` | — | 20 ranges | Allowed target URL IP ranges (CIDR) |

---

## Signing Secret Management

Each tenant has a signing secret used to compute HMAC-SHA256 signatures on webhook deliveries. Subscribers use this to verify that webhook requests originate from EventRelay.

### POST /api/v1/tenants/{id}/signing-secret/rotate

Rotates the signing secret. The old secret remains valid during a configurable grace period (default: 24 hours) to allow subscribers to update their verification logic.

**Authorization:** Bearer token with `admin` scope

#### Request

```http
POST /api/v1/tenants/tenant_01H5K3ABCDEF/signing-secret/rotate HTTP/1.1
Host: api.eventrelay.io
Authorization: Bearer sk_live_admin_key...
Content-Type: application/json
```

```json
{
  "grace_period_hours": 48
}
```

#### Response — `200 OK`

```json
{
  "data": {
    "new_secret": "whsec_n3W5ecr3tK3yV4lu3A1B2c3D4e5F6g7H8",
    "old_secret_expires_at": "2026-07-12T04:00:00Z",
    "grace_period_hours": 48,
    "rotated_at": "2026-07-10T04:00:00Z"
  },
  "message": "Signing secret rotated. Old secret valid until 2026-07-12T04:00:00Z."
}
```

> [!WARNING]
> During the grace period, EventRelay sends **both** the old and new signatures in the webhook header. Subscribers should verify against both:
> ```
> X-EventRelay-Signature: v1=<new_hmac>,v0=<old_hmac>
> ```

### HMAC Signature Computation

```java
public class HmacSignatureService {

    public String computeSignature(String secret, String eventId,
                                     long timestamp, String payload) {
        String signedContent = String.format("%s.%d.%s", eventId, timestamp, payload);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(signedContent.getBytes(UTF_8));

        return "v1=" + Hex.encodeHexString(hash);
    }
}
```

### Subscriber Verification (Example)

```python
import hmac
import hashlib

def verify_signature(payload: bytes, headers: dict, secret: str) -> bool:
    event_id = headers["X-EventRelay-Event-Id"]
    timestamp = headers["X-EventRelay-Timestamp"]
    expected_sigs = headers["X-EventRelay-Signature"].split(",")

    signed_content = f"{event_id}.{timestamp}.{payload.decode()}"
    computed = "v1=" + hmac.new(
        secret.encode(), signed_content.encode(), hashlib.sha256
    ).hexdigest()

    return any(hmac.compare_digest(computed, sig.strip()) for sig in expected_sigs)
```

---

## Error Responses

### Tenant-Specific Error Codes

| HTTP Status | Error Code | Description |
|---|---|---|
| `400` | `INVALID_SLUG` | Slug doesn't match required pattern |
| `404` | `TENANT_NOT_FOUND` | Tenant ID does not exist |
| `409` | `SLUG_ALREADY_EXISTS` | Another tenant uses this slug |
| `409` | `TENANT_ALREADY_DELETED` | Tenant is already soft-deleted |
| `422` | `INVALID_CONFIG` | Configuration values out of allowed range |
| `422` | `INVALID_IP_RANGE` | CIDR notation is malformed |

---

## Production Considerations

### Tenant Isolation

| Boundary | Implementation |
|---|---|
| **Data isolation** | All queries include `WHERE tenant_id = ?`; RLS policies in PostgreSQL |
| **Rate limit isolation** | Per-tenant token bucket in Redis |
| **Queue isolation** | SQS message attributes include `tenantId`; workers filter by tenant |
| **Secret isolation** | Each tenant has its own signing secret |

### Database Schema

```sql
CREATE TABLE tenants (
    id          VARCHAR(26) PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    slug        VARCHAR(64) NOT NULL UNIQUE,
    email       VARCHAR(256) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'active',
    config      JSONB NOT NULL DEFAULT '{}',
    signing_secret_hash     VARCHAR(64) NOT NULL,
    signing_secret_old_hash VARCHAR(64),
    signing_secret_old_expires_at TIMESTAMPTZ,
    metadata    JSONB DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ,

    CONSTRAINT chk_tenant_status CHECK (status IN ('active', 'suspended', 'deleted'))
);

CREATE INDEX idx_tenants_slug ON tenants (slug) WHERE deleted_at IS NULL;
CREATE INDEX idx_tenants_status ON tenants (status);
```

### Configuration Defaults by Plan

| Config | Free | Pro | Enterprise |
|---|---|---|---|
| `events_per_second` | 10 | 500 | 10,000 |
| `burst_size` | 20 | 1,000 | 50,000 |
| `max_attempts` | 5 | 10 | 30 |
| `timeout_ms` | 10,000 | 30,000 | 60,000 |
| `max_payload_size_kb` | 64 | 256 | 1,024 |
| `allowed_ip_ranges` | 5 | 10 | 20 |

---

## Cross-References

| Document | Description |
|---|---|
| [Authentication_APIs.md](./Authentication_APIs.md) | API key management (scoped to tenant) |
| [Subscription_APIs.md](./Subscription_APIs.md) | Subscriptions owned by tenant |
| [REST_APIs.md](./REST_APIs.md) | Common conventions and headers |
| [Error_Codes.md](./Error_Codes.md) | Full error code catalog |
