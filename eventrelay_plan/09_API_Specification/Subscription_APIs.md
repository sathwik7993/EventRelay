# EventRelay — Subscription Management APIs

This document details the REST API specifications for managing subscriptions in EventRelay, allowing tenants to register and verify webhook endpoints.

---

## 1. Webhook Challenge-Response Verification

To prevent tenants from registering spam endpoints or executing denial-of-service (DoS) attacks on third-party sites, EventRelay enforces a **Challenge-Response handshake** when registering a new target URL:

```
[ Tenant Request ] ──► [ Generate Challenge (Token) ] ──► [ GET to target URL?challenge=Token ]
                                                                       │
                                 ┌─────────────────────────────────────┘
                                 ▼
                     [ Target returns challenge? ] ──► (Yes) ──► [ Subscription ACTIVE ]
```

- **Handshake Flow**:
  1. Ingest API sends an HTTP `GET` request to the proposed target URL containing a `challenge` parameter: `https://receiver.com/webhook?challenge=er_challenge_a1b2c3`.
  2. The receiver must return the plaintext challenge string in the response body with an HTTP `200 OK` status.
  3. If verified, the subscription status switches to `ACTIVE`.

---

## 2. API Endpoint Specification

### Create Subscription
- **Endpoint**: `POST /api/v1/subscriptions`
- **Request Body**:
```json
{
  "name": "Payment Notifications",
  "target_url": "https://api.myclient.com/webhooks/payments",
  "event_types": ["payment.succeeded", "payment.failed"],
  "rate_limit": 50
}
```
- **Response (`201 Created`)**:
```json
{
  "id": "e88d8b87-7546-4b0c-8544-2e66b6066815",
  "status": "PENDING_VERIFICATION",
  "signing_secret": "whsec_7d5e6a8b1c2d3e4f5g6h7i8j9k0l1m2n",
  "created_at": "2026-07-10T09:43:00Z"
}
```

### Update Subscription
- **Endpoint**: `PATCH /api/v1/subscriptions/{id}`
- **Request Body**:
```json
{
  "event_types": ["payment.succeeded", "payment.failed", "payment.refunded"],
  "status": "ACTIVE"
}
```

### Delete Subscription
- **Endpoint**: `DELETE /api/v1/subscriptions/{id}`
- **Response**: `204 No Content`
