# EventRelay — Replay and DLQ APIs

This document details the REST API specifications for inspecting dead-lettered events and triggering manual or automated replays.

---

## 1. Dead-Letter Queue Inspection

Tenants can view failed events that have exhausted all delivery retries.

### List Dead-Letter Events
- **Endpoint**: `GET /api/v1/dead-letter`
- **Query Parameters**:
  - `subscription_id`: Filter by subscription UUID.
  - `status`: Filter by status (`PENDING_REVIEW`, `REPLAYED`, `DISCARDED`).
  - `limit`: Default `20`, max `100`.
- **Response (`200 OK`)**:
```json
{
  "data": [
    {
      "id": "8f3c4d21-a1b2-4c3d-9d8e-5f6a7b8c9d0e",
      "event_id": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
      "event_type": "payment.succeeded",
      "last_error_message": "HTTP 504 Gateway Timeout",
      "last_http_status": 504,
      "attempt_count": 5,
      "dead_lettered_at": "2026-07-10T09:00:00Z"
    }
  ],
  "pagination": {
    "next_cursor": "eyJjcmVhdGVkX2F0IjoiMjAyNi0wNy0xMFQwOTowMDowMFoifQ=="
  }
}
```

---

## 2. Triggering Replays

Replay endpoints allow re-delivering failed events.

### Replay Single Event
- **Endpoint**: `POST /api/v1/events/{id}/replay`
- **Request Body** (Optional):
```json
{
  "override_url": "https://debug.myclient.com/webhooks/catch"
}
```
- **Response (`202 Accepted`)**:
```json
{
  "event_id": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "status": "QUEUED_FOR_REPLAY",
  "replay_audit_id": "d88d8b87-7546-4b0c-8544-2e66b6066815"
}
```

### Batch Replay Events
- **Endpoint**: `POST /api/v1/replay/batch`
- **Request Body**:
```json
{
  "subscription_id": "e88d8b87-7546-4b0c-8544-2e66b6066815",
  "failed_after": "2026-07-10T00:00:00Z",
  "failed_before": "2026-07-10T08:00:00Z"
}
```
- **Response (`202 Accepted`)**:
```json
{
  "replay_audit_id": "9f3c4d21-a1b2-4c3d-9d8e-5f6a7b8c9d0e",
  "matched_event_count": 142,
  "status": "PROCESSING"
}
```
