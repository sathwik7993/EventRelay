# REST API Design — Ingestion Service

## Overview

The Ingestion Service exposes a versioned REST API (`/api/v1/`) that serves as the primary entry point for tenants to submit events, manage subscriptions, and query event status. All endpoints enforce authentication via API keys, per-tenant rate limiting, and strict input validation.

> [!IMPORTANT]
> All API responses follow a consistent envelope format. All timestamps are ISO-8601 in UTC. All IDs are UUIDs.

---

## Base URL & Versioning

```
https://api.eventrelay.io/api/v1/
```

| Header | Required | Description |
|---|---|---|
| `Authorization` | Yes | `Bearer er_live_<key>` or `Bearer er_test_<key>` |
| `Content-Type` | Yes | `application/json` |
| `Idempotency-Key` | Conditional | Required for POST `/events`. UUID v4 format. |
| `X-Tenant-Id` | No | Auto-resolved from API key. Override only for admin APIs. |

---

## Endpoints Summary

| Method | Path | Description | Auth | Idempotent |
|---|---|---|---|---|
| `POST` | `/api/v1/events` | Submit an event for delivery | API Key | Yes (via header) |
| `GET` | `/api/v1/events/{id}` | Get event status and delivery attempts | API Key | N/A |
| `GET` | `/api/v1/events` | List events with filtering/pagination | API Key | N/A |
| `POST` | `/api/v1/tenants` | Register a new tenant | Admin Key | Yes |
| `GET` | `/api/v1/tenants/{id}` | Get tenant details | API Key | N/A |
| `POST` | `/api/v1/subscriptions` | Create a subscription | API Key | Yes |
| `GET` | `/api/v1/subscriptions` | List subscriptions | API Key | N/A |
| `PATCH` | `/api/v1/subscriptions/{id}` | Update subscription status | API Key | No |
| `DELETE` | `/api/v1/subscriptions/{id}` | Delete a subscription | API Key | No |

---

## 1. POST `/api/v1/events` — Submit Event

The primary ingestion endpoint. Accepts an event payload, validates it, writes to the outbox table within a database transaction, and returns immediately with a `202 Accepted`.

### Request

```json
{
  "event_type": "payment.completed",
  "payload": {
    "payment_id": "pay_abc123",
    "amount": 4999,
    "currency": "USD",
    "customer_id": "cust_xyz789"
  },
  "metadata": {
    "source": "billing-service",
    "trace_id": "abc-def-123"
  }
}
```

### Request DTO

```java
@Validated
public record EventSubmissionRequest(

    @NotBlank(message = "event_type is required")
    @Size(max = 255, message = "event_type must not exceed 255 characters")
    @Pattern(regexp = "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$",
             message = "event_type must be dot-separated lowercase identifiers (e.g., payment.completed)")
    @JsonProperty("event_type")
    String eventType,

    @NotNull(message = "payload is required")
    @JsonProperty("payload")
    Map<String, Object> payload,

    @JsonProperty("metadata")
    Map<String, String> metadata

) {}
```

### Response — `202 Accepted`

```json
{
  "status": "accepted",
  "data": {
    "event_id": "evt_01H5KXZV3JQXR8N4M2GFTY9WBC",
    "event_type": "payment.completed",
    "status": "pending",
    "created_at": "2026-07-10T03:56:42.000Z",
    "idempotency_key": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

### Response DTO

```java
public record EventSubmissionResponse(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("event_type") String eventType,
    @JsonProperty("status") EventStatus status,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("idempotency_key") String idempotencyKey
) {}

public enum EventStatus {
    PENDING, DISPATCHING, DELIVERED, FAILED, DEAD_LETTERED
}
```

### Controller

```java
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Validated
public class EventController {

    private final EventIngestionService ingestionService;
    private final TenantContext tenantContext;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<EventSubmissionResponse> submitEvent(
            @Valid @RequestBody EventSubmissionRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {

        String tenantId = tenantContext.getCurrentTenantId();

        EventSubmissionResponse response = ingestionService.ingest(
            tenantId, request, idempotencyKey
        );

        return ApiResponse.accepted(response);
    }

    @GetMapping("/{eventId}")
    public ApiResponse<EventDetailResponse> getEvent(
            @PathVariable @NotBlank String eventId) {

        String tenantId = tenantContext.getCurrentTenantId();
        EventDetailResponse detail = ingestionService.getEventDetail(tenantId, eventId);

        return ApiResponse.ok(detail);
    }

    @GetMapping
    public ApiResponse<PagedResponse<EventSummaryResponse>> listEvents(
            @Valid EventListFilter filter,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        String tenantId = tenantContext.getCurrentTenantId();
        Page<EventSummaryResponse> events = ingestionService.listEvents(tenantId, filter, page, size);

        return ApiResponse.ok(PagedResponse.from(events));
    }
}
```

---

## 2. GET `/api/v1/events/{id}` — Get Event Status

Returns the event details along with all delivery attempts.

### Response — `200 OK`

```json
{
  "status": "ok",
  "data": {
    "event_id": "evt_01H5KXZV3JQXR8N4M2GFTY9WBC",
    "event_type": "payment.completed",
    "status": "delivered",
    "payload": {
      "payment_id": "pay_abc123",
      "amount": 4999,
      "currency": "USD"
    },
    "created_at": "2026-07-10T03:56:42.000Z",
    "delivery_attempts": [
      {
        "attempt_number": 1,
        "subscription_id": "sub_xyz",
        "target_url": "https://merchant.example.com/webhooks",
        "http_status": 503,
        "response_body_preview": "Service Unavailable",
        "latency_ms": 2340,
        "attempted_at": "2026-07-10T03:56:43.000Z",
        "outcome": "RETRYABLE_FAILURE"
      },
      {
        "attempt_number": 2,
        "subscription_id": "sub_xyz",
        "target_url": "https://merchant.example.com/webhooks",
        "http_status": 200,
        "response_body_preview": "OK",
        "latency_ms": 145,
        "attempted_at": "2026-07-10T03:57:44.000Z",
        "outcome": "SUCCESS"
      }
    ]
  }
}
```

### Event Detail DTO

```java
public record EventDetailResponse(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("event_type") String eventType,
    @JsonProperty("status") EventStatus status,
    @JsonProperty("payload") Map<String, Object> payload,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("delivery_attempts") List<DeliveryAttemptResponse> deliveryAttempts
) {}

public record DeliveryAttemptResponse(
    @JsonProperty("attempt_number") int attemptNumber,
    @JsonProperty("subscription_id") String subscriptionId,
    @JsonProperty("target_url") String targetUrl,
    @JsonProperty("http_status") Integer httpStatus,
    @JsonProperty("response_body_preview") String responseBodyPreview,
    @JsonProperty("latency_ms") long latencyMs,
    @JsonProperty("attempted_at") Instant attemptedAt,
    @JsonProperty("outcome") DeliveryOutcome outcome
) {}

public enum DeliveryOutcome {
    SUCCESS, RETRYABLE_FAILURE, PERMANENT_FAILURE, TIMEOUT, CIRCUIT_OPEN
}
```

---

## 3. POST `/api/v1/tenants` — Register Tenant

Admin-only endpoint to provision a new tenant. Returns the tenant record and a one-time plaintext API key.

### Request

```json
{
  "name": "Acme Corp",
  "contact_email": "ops@acme.com",
  "plan": "BUSINESS",
  "config": {
    "max_events_per_second": 100,
    "max_payload_size_bytes": 262144,
    "max_subscriptions": 50,
    "retry_policy": {
      "max_attempts": 8,
      "backoff_base_seconds": 1,
      "backoff_multiplier": 5.0,
      "backoff_max_seconds": 3600
    }
  }
}
```

### Request DTO

```java
@Validated
public record TenantRegistrationRequest(

    @NotBlank @Size(max = 255)
    String name,

    @NotBlank @Email
    @JsonProperty("contact_email")
    String contactEmail,

    @NotNull
    TenantPlan plan,

    @Valid @NotNull
    TenantConfigRequest config

) {}

public record TenantConfigRequest(
    @JsonProperty("max_events_per_second") @Min(1) @Max(10000) int maxEventsPerSecond,
    @JsonProperty("max_payload_size_bytes") @Min(1024) @Max(1048576) int maxPayloadSizeBytes,
    @JsonProperty("max_subscriptions") @Min(1) @Max(1000) int maxSubscriptions,
    @JsonProperty("retry_policy") @Valid RetryPolicyRequest retryPolicy
) {}

public record RetryPolicyRequest(
    @JsonProperty("max_attempts") @Min(1) @Max(20) int maxAttempts,
    @JsonProperty("backoff_base_seconds") @Min(1) @Max(60) int backoffBaseSeconds,
    @JsonProperty("backoff_multiplier") @DecimalMin("1.0") @DecimalMax("10.0") double backoffMultiplier,
    @JsonProperty("backoff_max_seconds") @Min(60) @Max(86400) int backoffMaxSeconds
) {}

public enum TenantPlan {
    FREE, STARTER, BUSINESS, ENTERPRISE
}
```

### Response — `201 Created`

```json
{
  "status": "created",
  "data": {
    "tenant_id": "tnt_01H5KXZV3JQXR8N4M2GFTY9WBC",
    "name": "Acme Corp",
    "plan": "BUSINESS",
    "api_key": "er_live_sk_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
    "api_key_prefix": "er_live_sk_a1b2",
    "created_at": "2026-07-10T03:56:42.000Z"
  }
}
```

> [!WARNING]
> The `api_key` field is returned **only once** in this response. It is hashed before storage and cannot be retrieved again. Clients must store it securely.

### Controller

```java
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<TenantRegistrationResponse> registerTenant(
            @Valid @RequestBody TenantRegistrationRequest request) {

        TenantRegistrationResponse response = tenantService.register(request);
        return ApiResponse.created(response);
    }

    @GetMapping("/{tenantId}")
    public ApiResponse<TenantDetailResponse> getTenant(
            @PathVariable String tenantId) {

        TenantDetailResponse detail = tenantService.getTenantDetail(tenantId);
        return ApiResponse.ok(detail);
    }
}
```

---

## 4. POST `/api/v1/subscriptions` — Create Subscription

Registers a webhook endpoint for specific event types.

### Request

```json
{
  "target_url": "https://merchant.example.com/webhooks/payments",
  "event_types": ["payment.completed", "payment.refunded"],
  "description": "Payment notifications for merchant portal",
  "secret": null,
  "metadata": {
    "environment": "production"
  }
}
```

### Response — `201 Created`

```json
{
  "status": "created",
  "data": {
    "subscription_id": "sub_01H5KXZV3JQXR8N4M2GFTY9WBC",
    "target_url": "https://merchant.example.com/webhooks/payments",
    "event_types": ["payment.completed", "payment.refunded"],
    "status": "pending_verification",
    "signing_secret": "whsec_MIGfMA0GCSqGSIb3DQEBAQUAA4GN...",
    "created_at": "2026-07-10T03:56:42.000Z"
  }
}
```

### Controller

```java
@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final TenantContext tenantContext;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SubscriptionResponse> createSubscription(
            @Valid @RequestBody SubscriptionCreateRequest request) {

        String tenantId = tenantContext.getCurrentTenantId();
        SubscriptionResponse response = subscriptionService.create(tenantId, request);
        return ApiResponse.created(response);
    }

    @GetMapping
    public ApiResponse<PagedResponse<SubscriptionResponse>> listSubscriptions(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        String tenantId = tenantContext.getCurrentTenantId();
        Page<SubscriptionResponse> subs = subscriptionService.list(tenantId, page, size);
        return ApiResponse.ok(PagedResponse.from(subs));
    }

    @PatchMapping("/{subscriptionId}")
    public ApiResponse<SubscriptionResponse> updateSubscription(
            @PathVariable String subscriptionId,
            @Valid @RequestBody SubscriptionUpdateRequest request) {

        String tenantId = tenantContext.getCurrentTenantId();
        SubscriptionResponse response = subscriptionService.update(tenantId, subscriptionId, request);
        return ApiResponse.ok(response);
    }

    @DeleteMapping("/{subscriptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSubscription(@PathVariable String subscriptionId) {
        String tenantId = tenantContext.getCurrentTenantId();
        subscriptionService.delete(tenantId, subscriptionId);
    }
}
```

---

## Standard Response Envelope

All responses use a consistent envelope:

```java
public record ApiResponse<T>(
    String status,
    T data,
    ApiError error,
    Map<String, Object> meta
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("ok", data, null, null);
    }

    public static <T> ApiResponse<T> accepted(T data) {
        return new ApiResponse<>("accepted", data, null, null);
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>("created", data, null, null);
    }

    public static ApiResponse<Void> error(ApiError error) {
        return new ApiResponse<>(error.status(), null, error, null);
    }
}

public record ApiError(
    String status,
    String code,
    String message,
    List<FieldError> fields,
    String request_id
) {}

public record FieldError(
    String field,
    String message,
    Object rejected_value
) {}
```

---

## Error Responses

All errors follow the same structure:

### 400 Bad Request — Validation Error

```json
{
  "status": "error",
  "error": {
    "status": "400",
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "fields": [
      {
        "field": "event_type",
        "message": "event_type must be dot-separated lowercase identifiers",
        "rejected_value": "Payment-Completed"
      }
    ],
    "request_id": "req_01H5KXZV3JQXR8N4M2GFTY9WBC"
  }
}
```

### 401 Unauthorized

```json
{
  "status": "error",
  "error": {
    "status": "401",
    "code": "INVALID_API_KEY",
    "message": "The provided API key is invalid or has been revoked",
    "request_id": "req_01H5KXZV3JQXR8N4M2GFTY9WBC"
  }
}
```

### 409 Conflict — Duplicate Idempotency Key

```json
{
  "status": "error",
  "error": {
    "status": "409",
    "code": "IDEMPOTENCY_CONFLICT",
    "message": "A request with this idempotency key is currently being processed",
    "request_id": "req_01H5KXZV3JQXR8N4M2GFTY9WBC"
  }
}
```

### 429 Too Many Requests

```json
{
  "status": "error",
  "error": {
    "status": "429",
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Rate limit exceeded. Retry after 2 seconds.",
    "request_id": "req_01H5KXZV3JQXR8N4M2GFTY9WBC"
  }
}
```

### Error Code Reference

| HTTP Status | Code | Description |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Request body validation failed |
| 400 | `INVALID_EVENT_TYPE` | Event type not registered for this tenant |
| 400 | `PAYLOAD_TOO_LARGE` | Payload exceeds tenant's size limit |
| 401 | `INVALID_API_KEY` | API key missing, invalid, or revoked |
| 403 | `FORBIDDEN` | API key lacks required scope |
| 404 | `NOT_FOUND` | Resource not found |
| 409 | `IDEMPOTENCY_CONFLICT` | Concurrent request with same idempotency key |
| 409 | `DUPLICATE_SUBSCRIPTION` | Subscription for this URL + event type already exists |
| 422 | `UNPROCESSABLE_ENTITY` | Semantically invalid (e.g., subscribing to own URL) |
| 429 | `RATE_LIMIT_EXCEEDED` | Per-tenant rate limit exceeded |
| 500 | `INTERNAL_ERROR` | Unexpected server error |
| 503 | `SERVICE_UNAVAILABLE` | Service temporarily unavailable |

---

## Rate Limiting Headers

All responses include rate-limiting metadata via standard headers:

```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 87
X-RateLimit-Reset: 1720583802
Retry-After: 2
```

| Header | Description |
|---|---|
| `X-RateLimit-Limit` | Max requests per second for this tenant |
| `X-RateLimit-Remaining` | Remaining requests in the current window |
| `X-RateLimit-Reset` | Unix epoch timestamp when the window resets |
| `Retry-After` | Seconds to wait (only on `429` responses) |

### Rate Limit Interceptor

```java
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;
    private final TenantContext tenantContext;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String tenantId = tenantContext.getCurrentTenantId();
        RateLimitResult result = rateLimitService.tryConsume(tenantId);

        response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(result.resetEpochSecond()));

        if (!result.allowed()) {
            response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                JsonUtil.toJson(ApiResponse.error(new ApiError(
                    "429", "RATE_LIMIT_EXCEEDED",
                    "Rate limit exceeded. Retry after " + result.retryAfterSeconds() + " seconds.",
                    null, RequestContext.getRequestId()
                )))
            );
            return false;
        }
        return true;
    }
}
```

---

## Pagination

List endpoints use offset-based pagination with a consistent response wrapper:

### Paginated Response DTO

```java
public record PagedResponse<T>(
    List<T> items,
    PageMeta pagination
) {
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
            page.getContent(),
            new PageMeta(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                page.hasPrevious()
            )
        );
    }
}

public record PageMeta(
    int page,
    int size,
    long total_elements,
    int total_pages,
    boolean has_next,
    boolean has_previous
) {}
```

### Pagination Response Example

```json
{
  "status": "ok",
  "data": {
    "items": [ ... ],
    "pagination": {
      "page": 0,
      "size": 20,
      "total_elements": 143,
      "total_pages": 8,
      "has_next": true,
      "has_previous": false
    }
  }
}
```

---

## Event List Filtering

The `GET /api/v1/events` endpoint supports filtering via query parameters:

```
GET /api/v1/events?event_type=payment.completed&status=FAILED&from=2026-07-01T00:00:00Z&to=2026-07-10T23:59:59Z&page=0&size=50
```

### Filter DTO

```java
public record EventListFilter(
    @JsonProperty("event_type") String eventType,
    @JsonProperty("status") EventStatus status,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
) {}
```

---

## Global Exception Handler

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> new FieldError(e.getField(), e.getDefaultMessage(), e.getRejectedValue()))
            .toList();

        return ApiResponse.error(new ApiError(
            "400", "VALIDATION_ERROR", "Request validation failed",
            fields, RequestContext.getRequestId()
        ));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(ResourceNotFoundException ex) {
        return ApiResponse.error(new ApiError(
            "404", "NOT_FOUND", ex.getMessage(),
            null, RequestContext.getRequestId()
        ));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ApiResponse.error(new ApiError(
            "409", "IDEMPOTENCY_CONFLICT", ex.getMessage(),
            null, RequestContext.getRequestId()
        ));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiResponse<Void> handlePayloadTooLarge(PayloadTooLargeException ex) {
        return ApiResponse.error(new ApiError(
            "413", "PAYLOAD_TOO_LARGE", ex.getMessage(),
            null, RequestContext.getRequestId()
        ));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        log.error("Unexpected error [requestId={}]", RequestContext.getRequestId(), ex);
        return ApiResponse.error(new ApiError(
            "500", "INTERNAL_ERROR", "An unexpected error occurred",
            null, RequestContext.getRequestId()
        ));
    }
}
```

---

## Request ID Propagation

Every request is assigned a unique request ID for tracing:

```java
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String requestId = Optional.ofNullable(request.getHeader("X-Request-Id"))
            .orElse("req_" + ULID.random());

        RequestContext.setRequestId(requestId);
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
            MDC.remove("requestId");
        }
    }
}
```

---

## Production Considerations

1. **Payload size enforcement** — Use a `ContentCachingRequestWrapper` with a hard limit; reject before JSON parsing to prevent OOM.
2. **Connection pooling** — HikariCP with `maximumPoolSize = 20`, `connectionTimeout = 3000ms`. Size the pool based on expected concurrent ingestion.
3. **Request timeouts** — Ingestion endpoint target: < 50ms p99. Set `server.tomcat.connection-timeout=5000` and `spring.mvc.async.request-timeout=10000`.
4. **Structured logging** — Every request log includes `tenantId`, `requestId`, `eventType`, `statusCode`, and `latencyMs`.
5. **Health checks** — Expose `/actuator/health` with DB + Redis + SQS checks for ECS task health.
6. **CORS** — Disabled by default. If tenant dashboards call the API directly, configure per-tenant CORS origins.
7. **Compression** — Enable gzip for responses > 1KB: `server.compression.enabled=true`.

---

## Cross-References

- [Authentication](./Authentication.md) — API key authentication flow
- [Event Validation](./Event_Validation.md) — Input validation pipeline
- [Idempotency](./Idempotency.md) — Idempotency key handling
- [Transactional Outbox](./Transactional_Outbox.md) — How events are persisted
- [Tenant Management](./Tenant_Management.md) — Tenant registration details
- [Subscription Management](./Subscription_Management.md) — Subscription CRUD
