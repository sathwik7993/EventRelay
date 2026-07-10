# EventRelay — API Error Codes Catalog

This document details the standardized error response format and catalogs all API error codes returned by the EventRelay ingestion and administrative APIs.

---

## 1. Error Response Format

To simplify integration for client platforms, EventRelay returns a uniform error response structure for all non-2xx endpoints:

```json
{
  "error": {
    "code": "API_ERROR_CODE",
    "message": "Human-readable description of what went wrong.",
    "details": {
      "field_name": "Specific constraint validation failures if applicable"
    }
  }
}
```

---

## 2. Core Error Catalog

### Authentication & Authorization (HTTP 401/403)
| Error Code | HTTP Status | Description |
|------------|-------------|-------------|
| `UNAUTHENTICATED` | 401 | The request did not provide a valid API Key in the headers. |
| `API_KEY_EXPIRED` | 401 | The provided API key is valid but has expired. |
| `INSUFFICIENT_SCOPE` | 403 | The API key is authenticated but lacks the permission scope required for the endpoint. |

### Validation & Limits (HTTP 400/422/429)
| Error Code | HTTP Status | Description |
|------------|-------------|-------------|
| `INVALID_INPUT` | 400 | The request payload violates size, pattern, or type constraints. |
| `INVALID_EVENT_TYPE` | 422 | The event type is not in dotted notation or is not registered. |
| `TENANT_RATE_LIMIT_EXCEEDED`| 429 | The tenant has sent more requests per second than their configured bucket limit. |
| `DUPLICATE_EVENT` | 202 / 409 | An event with the same idempotency key was already ingested. (Returns 202 with cached response on normal deduplication). |

### System Errors (HTTP 500/503)
| Error Code | HTTP Status | Description |
|------------|-------------|-------------|
| `INTERNAL_SERVER_ERROR` | 500 | An unhandled exception occurred in the server application. |
| `DATABASE_TIMEOUT` | 503 | The application timed out waiting for a database connection. |
| `SERVICE_UNAVAILABLE` | 503 | Core dependencies (e.g., SQS or database) are offline. |
