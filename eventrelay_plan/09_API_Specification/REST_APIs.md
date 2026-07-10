# REST API Overview

> [!NOTE]
> This document describes the foundational conventions, design principles, and cross-cutting concerns for all EventRelay REST APIs. Individual endpoint documentation references this document for shared behavior.

---

## Table of Contents

- [API Design Principles](#api-design-principles)
- [API Versioning Strategy](#api-versioning-strategy)
- [Base URL Structure](#base-url-structure)
- [Common Request Headers](#common-request-headers)
- [Common Response Headers](#common-response-headers)
- [Content Negotiation](#content-negotiation)
- [Pagination](#pagination)
- [Sorting](#sorting)
- [Filtering](#filtering)
- [HATEOAS Considerations](#hateoas-considerations)
- [Request / Response Conventions](#request--response-conventions)
- [Idempotency](#idempotency)
- [Rate Limiting Headers](#rate-limiting-headers)
- [Production Considerations](#production-considerations)

---

## API Design Principles

EventRelay's API is designed following industry best practices drawn from Stripe, GitHub, and Svix:

| Principle | Description |
|---|---|
| **Resource-oriented** | URLs represent nouns (`/events`, `/subscriptions`), not verbs |
| **Predictable** | Consistent naming, error formats, and pagination across all endpoints |
| **Versioned** | Breaking changes ship under a new version prefix; non-breaking changes are additive |
| **Secure by default** | All endpoints require authentication; HMAC signing for webhooks |
| **Idempotent where possible** | Writes accept `Idempotency-Key` header to enable safe retries |
| **Minimal surprises** | HTTP semantics are respected (status codes, methods, caching headers) |

---

## API Versioning Strategy

EventRelay uses **URL-path versioning** — the major version is embedded in the URI.

```
/api/v1/events
/api/v1/subscriptions
/api/v2/events          ← future breaking changes
```

### Version Lifecycle

| Phase | Duration | Description |
|---|---|---|
| **Current** | Indefinite | Actively developed; receives features + fixes |
| **Deprecated** | 12 months | Receives security fixes only; `Sunset` header returned |
| **Retired** | — | Returns `410 Gone` for all requests |

### Deprecation Headers

When a version enters deprecation, every response includes:

```http
Sunset: Sat, 01 Jul 2028 00:00:00 GMT
Deprecation: true
Link: <https://docs.eventrelay.io/api/v2/migration>; rel="successor-version"
```

### Why URL-Path Versioning?

| Approach | Pros | Cons | Used By |
|---|---|---|---|
| **URL path** (`/v1/`) | Simple routing, cache-friendly, explicit | URL changes on major bump | Stripe, Twilio |
| Header (`Accept-Version`) | Clean URLs | Hidden, harder to test/debug | — |
| Query param (`?v=1`) | Easy to add | Pollutes query string, cache issues | — |

EventRelay chose URL-path versioning for **explicitness and debuggability** — every request clearly states which contract it expects.

### Spring Boot Configuration

```java
@Configuration
public class ApiVersionConfig {

    public static final String API_V1 = "/api/v1";

    @Bean
    public WebMvcConfigurer apiVersionConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void configurePathMatch(PathMatchConfigurer configurer) {
                configurer.addPathPrefix(API_V1,
                    HandlerTypePredicate.forAnnotation(V1Api.class));
            }
        };
    }
}

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface V1Api {}
```

---

## Base URL Structure

```
https://{environment}.eventrelay.io/api/v1/{resource}
```

| Environment | Base URL |
|---|---|
| Production | `https://api.eventrelay.io/api/v1/` |
| Staging | `https://staging-api.eventrelay.io/api/v1/` |
| Local Dev | `http://localhost:8080/api/v1/` |

### URL Conventions

- **Plural nouns** for collections: `/events`, `/subscriptions`, `/tenants`
- **Kebab-case** for multi-word resources: `/dead-letter`, `/api-keys`
- **UUIDs** for resource identifiers: `/events/550e8400-e29b-41d4-a716-446655440000`
- **Nested resources** where there is a clear parent-child: `/subscriptions/{id}/deliveries`
- **Action sub-resources** for non-CRUD operations: `/subscriptions/{id}/test`, `/events/{id}/replay`

---

## Common Request Headers

| Header | Required | Description | Example |
|---|---|---|---|
| `Authorization` | Yes (except `/health`) | Bearer token or API key | `Bearer sk_live_abc123...` |
| `Content-Type` | Yes (for request bodies) | Must be `application/json` | `application/json` |
| `Accept` | No | Defaults to `application/json` | `application/json` |
| `Idempotency-Key` | No (recommended for POST) | UUID for safe retries | `550e8400-e29b-41d4-...` |
| `X-Request-Id` | No | Client-generated trace ID | `req_abc123` |
| `X-Tenant-Id` | Conditional | Required for multi-tenant admin endpoints | `tenant_xyz789` |

### Authentication Header Formats

```http
# API Key authentication (server-to-server)
Authorization: Bearer sk_live_EXAMPLE_redacted_key

# JWT authentication (dashboard / human users)
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

---

## Common Response Headers

| Header | Description | Example |
|---|---|---|
| `X-Request-Id` | Echoed from request or server-generated UUID | `req_550e8400-...` |
| `X-RateLimit-Limit` | Maximum requests per window | `1000` |
| `X-RateLimit-Remaining` | Remaining requests in current window | `742` |
| `X-RateLimit-Reset` | UTC epoch seconds when window resets | `1719849600` |
| `X-Response-Time` | Server processing time in milliseconds | `23` |
| `Content-Type` | Always `application/json; charset=utf-8` | — |
| `ETag` | Entity tag for conditional requests | `"33a64df5"` |
| `Cache-Control` | Cache directives | `no-store, no-cache` |

### Spring Boot Response Header Filter

```java
@Component
public class CommonHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String requestId = Optional.ofNullable(request.getHeader("X-Request-Id"))
            .orElse(UUID.randomUUID().toString());

        long start = System.nanoTime();
        response.setHeader("X-Request-Id", requestId);

        // Put requestId in MDC for structured logging
        MDC.put("requestId", requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            response.setHeader("X-Response-Time", String.valueOf(durationMs));
            MDC.remove("requestId");
        }
    }
}
```

---

## Content Negotiation

EventRelay **exclusively** uses `application/json` for request and response bodies.

| Aspect | Policy |
|---|---|
| **Request body** | Must be `application/json`; returns `415 Unsupported Media Type` otherwise |
| **Response body** | Always `application/json; charset=utf-8` |
| **Accept header** | If present, must include `application/json` or `*/*`; returns `406 Not Acceptable` otherwise |
| **Binary data** | Not supported inline; use signed URLs for large payloads |
| **Compression** | `gzip` and `br` (Brotli) supported via `Accept-Encoding` |

### Content-Type Validation

```java
@RestControllerAdvice
public class ContentTypeAdvice {

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(ErrorResponse.of("UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be application/json",
                Map.of("supported", List.of("application/json"))));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(
            HttpMediaTypeNotAcceptableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
            .body(ErrorResponse.of("NOT_ACCEPTABLE",
                "Accept header must include application/json",
                Map.of("supported", List.of("application/json"))));
    }
}
```

---

## Pagination

EventRelay uses **cursor-based pagination** for all list endpoints. Cursor pagination provides stable results even when data is being inserted or deleted concurrently — unlike offset-based pagination, which suffers from page drift.

### Request Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `cursor` | `string` | `null` (first page) | Opaque cursor from previous response |
| `limit` | `integer` | `20` | Items per page (1–100) |
| `direction` | `string` | `after` | `after` (next page) or `before` (previous page) |

### Response Envelope

```json
{
  "data": [
    { "id": "evt_001", "type": "order.created", "created_at": "2026-07-10T04:00:00Z" },
    { "id": "evt_002", "type": "order.updated", "created_at": "2026-07-10T04:01:00Z" }
  ],
  "pagination": {
    "has_more": true,
    "next_cursor": "eyJpZCI6ImV2dF8wMDIiLCJjcmVhdGVkX2F0IjoiMjAyNi0wNy0xMFQwNDowMTowMFoifQ==",
    "previous_cursor": null,
    "limit": 20
  }
}
```

### Cursor Implementation

Cursors are **Base64-encoded JSON** containing the sort key value(s) of the last item:

```java
@Component
public class CursorCodec {

    private final ObjectMapper objectMapper;

    public String encode(Map<String, Object> cursorData) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(cursorData);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode cursor", e);
        }
    }

    public Map<String, Object> decode(String cursor) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new InvalidCursorException("Invalid or expired cursor");
        }
    }
}
```

### SQL Query Pattern

```sql
-- Forward pagination (next page)
SELECT * FROM events
WHERE tenant_id = :tenantId
  AND (created_at, id) > (:cursorCreatedAt, :cursorId)
ORDER BY created_at ASC, id ASC
LIMIT :limit + 1;  -- fetch one extra to determine has_more
```

### Example curl

```bash
# First page
curl -s "https://api.eventrelay.io/api/v1/events?limit=20" \
  -H "Authorization: Bearer sk_live_abc123" | jq

# Next page
curl -s "https://api.eventrelay.io/api/v1/events?limit=20&cursor=eyJpZCI6..." \
  -H "Authorization: Bearer sk_live_abc123" | jq
```

---

## Sorting

List endpoints support sorting via the `sort` query parameter.

### Syntax

```
?sort=field:direction
?sort=created_at:desc
?sort=type:asc,created_at:desc    ← multi-field sort
```

### Available Sort Fields by Resource

| Resource | Sortable Fields | Default Sort |
|---|---|---|
| Events | `created_at`, `type`, `status` | `created_at:desc` |
| Subscriptions | `created_at`, `url`, `status` | `created_at:desc` |
| Dead-letter | `failed_at`, `retry_count`, `event_type` | `failed_at:desc` |
| Tenants | `created_at`, `name` | `created_at:desc` |

### Sort Validation

```java
public class SortParser {

    private static final Set<String> VALID_DIRECTIONS = Set.of("asc", "desc");

    public List<Sort.Order> parse(String sortParam, Set<String> allowedFields) {
        if (sortParam == null || sortParam.isBlank()) {
            return List.of(Sort.Order.desc("created_at"));
        }

        return Arrays.stream(sortParam.split(","))
            .map(clause -> {
                String[] parts = clause.split(":");
                if (parts.length != 2) {
                    throw new InvalidSortException("Sort format: field:direction");
                }
                String field = parts[0].trim();
                String direction = parts[1].trim().toLowerCase();

                if (!allowedFields.contains(field)) {
                    throw new InvalidSortException(
                        "Invalid sort field: " + field + ". Allowed: " + allowedFields);
                }
                if (!VALID_DIRECTIONS.contains(direction)) {
                    throw new InvalidSortException(
                        "Invalid direction: " + direction + ". Use 'asc' or 'desc'");
                }

                return "asc".equals(direction)
                    ? Sort.Order.asc(field) : Sort.Order.desc(field);
            })
            .toList();
    }
}
```

---

## Filtering

List endpoints support filtering via query parameters. Filters use a consistent syntax across all resources.

### Filter Operators

| Operator | Syntax | Example | Description |
|---|---|---|---|
| Equals | `field=value` | `status=delivered` | Exact match |
| In | `field=val1,val2` | `type=order.created,order.updated` | Match any |
| Greater than | `field.gt=value` | `created_at.gt=2026-01-01T00:00:00Z` | Exclusive lower bound |
| Greater or equal | `field.gte=value` | `retry_count.gte=3` | Inclusive lower bound |
| Less than | `field.lt=value` | `created_at.lt=2026-07-01T00:00:00Z` | Exclusive upper bound |
| Less or equal | `field.lte=value` | `retry_count.lte=10` | Inclusive upper bound |

### Date Range Filtering

All timestamps are **ISO 8601 UTC**:

```bash
# Events created in the last 24 hours
GET /api/v1/events?created_at.gte=2026-07-09T04:00:00Z&created_at.lt=2026-07-10T04:00:00Z

# Failed events with 5+ retries
GET /api/v1/events?status=failed&retry_count.gte=5
```

### Filter Specification Pattern

```java
public class EventFilterSpec {

    public static Specification<Event> build(EventFilterRequest filter) {
        return Specification.where(tenantEquals(filter.getTenantId()))
            .and(typeIn(filter.getTypes()))
            .and(statusEquals(filter.getStatus()))
            .and(createdAfter(filter.getCreatedAtGte()))
            .and(createdBefore(filter.getCreatedAtLt()));
    }

    private static Specification<Event> createdAfter(Instant after) {
        return (root, query, cb) -> after == null
            ? null : cb.greaterThanOrEqualTo(root.get("createdAt"), after);
    }

    private static Specification<Event> typeIn(List<String> types) {
        return (root, query, cb) -> types == null || types.isEmpty()
            ? null : root.get("type").in(types);
    }
}
```

---

## HATEOAS Considerations

EventRelay uses **lightweight hypermedia links** (not full HAL or JSON:API) to improve API discoverability without over-engineering.

### Link Format

```json
{
  "id": "evt_550e8400",
  "type": "order.created",
  "status": "delivered",
  "_links": {
    "self": { "href": "/api/v1/events/evt_550e8400" },
    "replay": { "href": "/api/v1/events/evt_550e8400/replay", "method": "POST" },
    "subscription": { "href": "/api/v1/subscriptions/sub_abcdef" },
    "deliveries": { "href": "/api/v1/events/evt_550e8400/deliveries" }
  }
}
```

### Link Assembler

```java
@Component
public class EventLinkAssembler {

    public Map<String, Object> toLinks(Event event) {
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", Map.of("href", "/api/v1/events/" + event.getId()));

        if (event.getStatus() == EventStatus.FAILED) {
            links.put("replay", Map.of(
                "href", "/api/v1/events/" + event.getId() + "/replay",
                "method", "POST"
            ));
        }

        links.put("subscription", Map.of(
            "href", "/api/v1/subscriptions/" + event.getSubscriptionId()
        ));

        return links;
    }
}
```

> [!TIP]
> HATEOAS links are optional for API consumers — all endpoints are fully usable by constructing URLs directly. Links are a convenience, not a requirement.

---

## Request / Response Conventions

### Successful Response Envelope

```json
// Single resource
{
  "data": { ... },
  "_links": { ... }
}

// Collection
{
  "data": [ ... ],
  "pagination": { ... }
}

// Action result
{
  "data": { ... },
  "message": "Event replayed successfully"
}
```

### HTTP Status Codes

| Code | Usage |
|---|---|
| `200 OK` | Successful GET, PATCH, action |
| `201 Created` | Successful POST that creates a resource |
| `202 Accepted` | Async operation accepted (replay, batch) |
| `204 No Content` | Successful DELETE |
| `400 Bad Request` | Validation error, malformed JSON |
| `401 Unauthorized` | Missing or invalid authentication |
| `403 Forbidden` | Valid auth but insufficient permissions |
| `404 Not Found` | Resource does not exist |
| `409 Conflict` | Duplicate resource / idempotency conflict |
| `415 Unsupported Media Type` | Wrong Content-Type |
| `422 Unprocessable Entity` | Semantically invalid request |
| `429 Too Many Requests` | Rate limit exceeded |
| `500 Internal Server Error` | Unexpected server error |
| `502 Bad Gateway` | Upstream service failure |
| `503 Service Unavailable` | Maintenance / overload |

### Error Response Format

All errors follow a consistent structure (see [Error_Codes.md](./Error_Codes.md) for the full catalog):

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": {
      "fields": [
        { "field": "url", "message": "must be a valid HTTPS URL" },
        { "field": "event_types", "message": "must not be empty" }
      ]
    },
    "request_id": "req_550e8400-e29b-41d4-a716-446655440000"
  }
}
```

---

## Idempotency

All `POST` endpoints accept an `Idempotency-Key` header for safe retries.

### Behavior

| Scenario | Response |
|---|---|
| First request with key | Processes normally; caches response |
| Replay with same key + same body | Returns cached response (same status code) |
| Replay with same key + different body | Returns `409 Conflict` |
| Key not provided | Request processed without idempotency protection |

### Implementation

```java
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    private final RedisTemplate<String, String> redis;
    private static final Duration TTL = Duration.ofHours(24);

    @Override
    public boolean preHandle(HttpServletRequest request,
                              HttpServletResponse response,
                              Object handler) throws Exception {
        if (!"POST".equals(request.getMethod())) return true;

        String key = request.getHeader("Idempotency-Key");
        if (key == null) return true;

        String cacheKey = "idempotency:" + key;
        String cached = redis.opsForValue().get(cacheKey);

        if (cached != null) {
            CachedResponse cr = objectMapper.readValue(cached, CachedResponse.class);
            response.setStatus(cr.status());
            response.setContentType("application/json");
            response.getWriter().write(cr.body());
            return false;  // short-circuit
        }

        return true;
    }
}
```

### Key Requirements

- Keys must be **UUIDs** (v4 recommended)
- Keys are scoped to the **tenant** — different tenants can reuse the same key
- Cached responses expire after **24 hours**
- Maximum key length: **256 characters**

---

## Rate Limiting Headers

Every response includes rate-limit information:

```http
HTTP/1.1 200 OK
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 742
X-RateLimit-Reset: 1719849600
Retry-After: 30           ← only on 429 responses
```

When rate-limited, the response body provides details:

```json
{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Too many requests. Retry after 30 seconds.",
    "details": {
      "limit": 1000,
      "window_seconds": 3600,
      "retry_after_seconds": 30
    }
  }
}
```

See [Error_Codes.md](./Error_Codes.md) for all rate-limiting error codes.

---

## Production Considerations

### Request Size Limits

| Limit | Value |
|---|---|
| Max request body | 1 MB |
| Max batch size | 100 events |
| Max URL length | 2,048 characters |
| Max header size | 8 KB |
| Max query string length | 4,096 characters |

### Timeouts

| Timeout | Value |
|---|---|
| Client read timeout (recommended) | 30 seconds |
| Server request timeout | 60 seconds |
| Keep-alive timeout | 75 seconds |
| Idle connection timeout | 300 seconds |

### Best Practices for API Consumers

1. **Always send `Idempotency-Key`** on POST requests to enable safe retries
2. **Implement exponential backoff** when receiving `429` or `5xx` responses
3. **Use cursor pagination** — do not attempt to construct cursors manually
4. **Check `X-RateLimit-Remaining`** proactively to avoid hitting limits
5. **Log `X-Request-Id`** from responses for support/debugging
6. **Set reasonable timeouts** (30s read, 10s connect) on HTTP clients
7. **Handle `Sunset` header** to detect upcoming API version deprecation
8. **Validate webhook signatures** using the provided HMAC-SHA256 secret

### Spring Boot Application Properties

```yaml
server:
  port: 8080
  servlet:
    context-path: /
  tomcat:
    max-http-form-post-size: 1MB
    connection-timeout: 60s
    keep-alive-timeout: 75s
    max-swallow-size: 1MB
    threads:
      max: 200
      min-spare: 20

spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false
      indent-output: false
    deserialization:
      fail-on-unknown-properties: false
    default-property-inclusion: non_null
    date-format: com.fasterxml.jackson.databind.util.ISO8601DateFormat
  mvc:
    throw-exception-if-no-handler-found: true
  web:
    resources:
      add-mappings: false
```

---

## Cross-References

| Document | Description |
|---|---|
| [Authentication_APIs.md](./Authentication_APIs.md) | API key and JWT authentication endpoints |
| [Event_APIs.md](./Event_APIs.md) | Event submission and query endpoints |
| [Subscription_APIs.md](./Subscription_APIs.md) | Subscription management endpoints |
| [Error_Codes.md](./Error_Codes.md) | Complete error code catalog |
| [OpenAPI.md](./OpenAPI.md) | OpenAPI 3.0 specification and tooling |
