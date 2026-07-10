# Authentication APIs

> [!NOTE]
> EventRelay supports two authentication mechanisms: **API Keys** for server-to-server communication and **JWT tokens** for dashboard/human access. All endpoints except `/health` require authentication.

---

## Table of Contents

- [Authentication Overview](#authentication-overview)
- [API Key Endpoints](#api-key-endpoints)
  - [POST /api/v1/auth/keys — Create API Key](#post-apiv1authkeys--create-api-key)
  - [GET /api/v1/auth/keys — List API Keys](#get-apiv1authkeys--list-api-keys)
  - [DELETE /api/v1/auth/keys/{id} — Revoke API Key](#delete-apiv1authkeysid--revoke-api-key)
- [JWT Token Endpoints](#jwt-token-endpoints)
  - [POST /api/v1/auth/tokens — Get JWT Token](#post-apiv1authtokens--get-jwt-token)
  - [POST /api/v1/auth/tokens/refresh — Refresh JWT Token](#post-apiv1authtokensrefresh--refresh-jwt-token)
- [Authentication Flow Diagrams](#authentication-flow-diagrams)
- [Security Implementation](#security-implementation)
- [Error Responses](#error-responses)
- [Production Considerations](#production-considerations)

---

## Authentication Overview

```mermaid
flowchart LR
    A["Client App"] -->|API Key| B["EventRelay API"]
    C["Dashboard User"] -->|JWT Token| B
    B --> D["Auth Filter"]
    D -->|Validate Key| E["PostgreSQL"]
    D -->|Validate JWT| F["RSA Public Key"]
    D -->|Rate Check| G["Redis"]
```

### Authentication Methods

| Method | Format | Use Case | Lifetime |
|---|---|---|---|
| **API Key** | `sk_live_<base62>` / `sk_test_<base62>` | Server-to-server integration | Until revoked |
| **JWT Access Token** | RS256-signed JWT | Dashboard access, human users | 15 minutes |
| **JWT Refresh Token** | Opaque token | Renew access tokens | 7 days |

### API Key Prefixes

| Prefix | Environment | Description |
|---|---|---|
| `sk_live_` | Production | Full access to production resources |
| `sk_test_` | Sandbox | Isolated test environment; events not delivered |
| `pk_live_` | Production | Public key (read-only, client-safe) |

### Key Format

API keys are 56 characters: `sk_live_` (8) + 48 characters of Base62-encoded random bytes.

```
sk_live_EXAMPLE_redacted_key
```

> [!IMPORTANT]
> The full API key is returned **only once** at creation time. EventRelay stores a SHA-256 hash of the key — the plaintext cannot be retrieved later.

---

## API Key Endpoints

### POST /api/v1/auth/keys — Create API Key

Creates a new API key for the authenticated tenant.

**Authorization:** Bearer token (JWT or existing API key with `admin` scope)

#### Request

```http
POST /api/v1/auth/keys HTTP/1.1
Host: api.eventrelay.io
Authorization: Bearer sk_live_existing_admin_key...
Content-Type: application/json
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

```json
{
  "name": "Production Webhook Sender",
  "environment": "live",
  "scopes": ["events:write", "subscriptions:read"],
  "expires_at": "2027-07-10T00:00:00Z",
  "metadata": {
    "team": "payments",
    "service": "order-service"
  }
}
```

#### Request Schema

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | `string` | Yes | Human-readable key name (3–128 chars) |
| `environment` | `string` | Yes | `live` or `test` |
| `scopes` | `string[]` | Yes | Permission scopes (see table below) |
| `expires_at` | `ISO 8601` | No | Expiration timestamp (default: never) |
| `metadata` | `object` | No | Arbitrary key-value pairs (max 10 keys, 256 chars each) |

#### Available Scopes

| Scope | Description |
|---|---|
| `events:write` | Submit events |
| `events:read` | List and query events |
| `subscriptions:write` | Create, update, delete subscriptions |
| `subscriptions:read` | List and query subscriptions |
| `dead-letter:read` | View dead-letter queue |
| `dead-letter:write` | Replay or discard dead-letter events |
| `admin` | Full access (create/revoke keys, manage tenant) |

#### Response — `201 Created`

```json
{
  "data": {
    "id": "key_01H5K3ABCDEF",
    "name": "Production Webhook Sender",
    "key": "sk_live_EXAMPLE_redacted_key",
    "key_prefix": "sk_live_a1B2c3D4",
    "environment": "live",
    "scopes": ["events:write", "subscriptions:read"],
    "expires_at": "2027-07-10T00:00:00Z",
    "created_at": "2026-07-10T04:00:00Z",
    "metadata": {
      "team": "payments",
      "service": "order-service"
    }
  },
  "_links": {
    "self": { "href": "/api/v1/auth/keys/key_01H5K3ABCDEF" },
    "revoke": { "href": "/api/v1/auth/keys/key_01H5K3ABCDEF", "method": "DELETE" }
  }
}
```

> [!CAUTION]
> The `key` field is returned **only in this response**. Store it securely immediately. It cannot be retrieved again.

#### curl Example

```bash
curl -X POST https://api.eventrelay.io/api/v1/auth/keys \
  -H "Authorization: Bearer sk_live_existing_admin_key" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "name": "Production Webhook Sender",
    "environment": "live",
    "scopes": ["events:write", "subscriptions:read"],
    "expires_at": "2027-07-10T00:00:00Z"
  }'
```

---

### GET /api/v1/auth/keys — List API Keys

Lists all API keys for the authenticated tenant. The `key` field is redacted — only the prefix is shown.

**Authorization:** Bearer token with `admin` scope

#### Request

```http
GET /api/v1/auth/keys?limit=20&status=active HTTP/1.1
Host: api.eventrelay.io
Authorization: Bearer sk_live_admin_key...
```

#### Query Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `limit` | `integer` | `20` | Items per page (1–100) |
| `cursor` | `string` | `null` | Pagination cursor |
| `status` | `string` | `all` | `active`, `revoked`, `expired`, `all` |
| `environment` | `string` | `all` | `live`, `test`, `all` |

#### Response — `200 OK`

```json
{
  "data": [
    {
      "id": "key_01H5K3ABCDEF",
      "name": "Production Webhook Sender",
      "key_prefix": "sk_live_a1B2c3D4",
      "environment": "live",
      "scopes": ["events:write", "subscriptions:read"],
      "status": "active",
      "last_used_at": "2026-07-10T03:45:00Z",
      "expires_at": "2027-07-10T00:00:00Z",
      "created_at": "2026-07-10T04:00:00Z"
    },
    {
      "id": "key_02H6L4GHIJKL",
      "name": "Dashboard Admin",
      "key_prefix": "sk_live_x9Y8w7V6",
      "environment": "live",
      "scopes": ["admin"],
      "status": "active",
      "last_used_at": "2026-07-09T22:30:00Z",
      "expires_at": null,
      "created_at": "2026-06-01T00:00:00Z"
    }
  ],
  "pagination": {
    "has_more": false,
    "next_cursor": null,
    "previous_cursor": null,
    "limit": 20
  }
}
```

#### curl Example

```bash
curl -s "https://api.eventrelay.io/api/v1/auth/keys?status=active" \
  -H "Authorization: Bearer sk_live_admin_key" | jq
```

---

### DELETE /api/v1/auth/keys/{id} — Revoke API Key

Immediately revokes an API key. The key cannot be used after revocation. This is **irreversible**.

**Authorization:** Bearer token with `admin` scope

#### Request

```http
DELETE /api/v1/auth/keys/key_01H5K3ABCDEF HTTP/1.1
Host: api.eventrelay.io
Authorization: Bearer sk_live_admin_key...
```

#### Response — `200 OK`

```json
{
  "data": {
    "id": "key_01H5K3ABCDEF",
    "name": "Production Webhook Sender",
    "status": "revoked",
    "revoked_at": "2026-07-10T04:05:00Z",
    "revoked_by": "key_02H6L4GHIJKL"
  },
  "message": "API key revoked successfully. It can no longer be used for authentication."
}
```

#### Error — Key Not Found (`404`)

```json
{
  "error": {
    "code": "KEY_NOT_FOUND",
    "message": "API key 'key_nonexistent' does not exist",
    "request_id": "req_550e8400"
  }
}
```

#### curl Example

```bash
curl -X DELETE https://api.eventrelay.io/api/v1/auth/keys/key_01H5K3ABCDEF \
  -H "Authorization: Bearer sk_live_admin_key"
```

---

## JWT Token Endpoints

### POST /api/v1/auth/tokens — Get JWT Token

Exchanges credentials (email + password or API key) for a short-lived JWT access token and a refresh token. Designed for dashboard and human user access.

**Authorization:** None (credentials in body)

#### Request — Email/Password

```http
POST /api/v1/auth/tokens HTTP/1.1
Host: api.eventrelay.io
Content-Type: application/json
```

```json
{
  "grant_type": "password",
  "email": "admin@acme.com",
  "password": "s3cur3P@ssw0rd!"
}
```

#### Request — API Key Exchange

```json
{
  "grant_type": "api_key",
  "api_key": "sk_live_EXAMPLE_redacted_key"
}
```

#### Request Schema

| Field | Type | Required | Description |
|---|---|---|---|
| `grant_type` | `string` | Yes | `password` or `api_key` |
| `email` | `string` | Conditional | Required when `grant_type=password` |
| `password` | `string` | Conditional | Required when `grant_type=password` |
| `api_key` | `string` | Conditional | Required when `grant_type=api_key` |

#### Response — `200 OK`

```json
{
  "data": {
    "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZW5hbnRfYWJjMTIzIiwic2NvcGVzIjpbImFkbWluIl0sImlhdCI6MTcyMDU3NjAwMCwiZXhwIjoxNzIwNTc2OTAwfQ.signature",
    "refresh_token": "rt_x9Y8w7V6u5T4s3R2q1P0o9N8m7L6k5J4",
    "token_type": "Bearer",
    "expires_in": 900,
    "expires_at": "2026-07-10T04:15:00Z",
    "scopes": ["admin"],
    "tenant_id": "tenant_abc123"
  }
}
```

#### JWT Payload Structure

```json
{
  "sub": "tenant_abc123",
  "iss": "eventrelay.io",
  "aud": "eventrelay-api",
  "iat": 1720576000,
  "exp": 1720576900,
  "jti": "jwt_550e8400-e29b-41d4",
  "scopes": ["admin"],
  "tenant_id": "tenant_abc123",
  "environment": "live"
}
```

#### Error — Invalid Credentials (`401`)

```json
{
  "error": {
    "code": "INVALID_CREDENTIALS",
    "message": "The provided credentials are invalid",
    "request_id": "req_abc123"
  }
}
```

#### Error — Account Locked (`423`)

```json
{
  "error": {
    "code": "ACCOUNT_LOCKED",
    "message": "Account locked due to 5 failed login attempts. Try again in 15 minutes.",
    "details": {
      "locked_until": "2026-07-10T04:15:00Z",
      "failed_attempts": 5
    },
    "request_id": "req_abc123"
  }
}
```

#### curl Example

```bash
# Email/Password login
curl -X POST https://api.eventrelay.io/api/v1/auth/tokens \
  -H "Content-Type: application/json" \
  -d '{
    "grant_type": "password",
    "email": "admin@acme.com",
    "password": "s3cur3P@ssw0rd!"
  }'

# API Key exchange
curl -X POST https://api.eventrelay.io/api/v1/auth/tokens \
  -H "Content-Type: application/json" \
  -d '{
    "grant_type": "api_key",
    "api_key": "sk_live_a1B2c3D4e5F6g7H8i9J0..."
  }'
```

---

### POST /api/v1/auth/tokens/refresh — Refresh JWT Token

Exchanges a valid refresh token for a new access token without re-authenticating.

**Authorization:** None (refresh token in body)

#### Request

```http
POST /api/v1/auth/tokens/refresh HTTP/1.1
Host: api.eventrelay.io
Content-Type: application/json
```

```json
{
  "refresh_token": "rt_x9Y8w7V6u5T4s3R2q1P0o9N8m7L6k5J4"
}
```

#### Response — `200 OK`

```json
{
  "data": {
    "access_token": "eyJhbGciOiJSUzI1NiIs...<new_token>",
    "refresh_token": "rt_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6",
    "token_type": "Bearer",
    "expires_in": 900,
    "expires_at": "2026-07-10T04:30:00Z"
  }
}
```

> [!NOTE]
> Refresh token rotation is enforced — each refresh call issues a **new refresh token** and invalidates the old one. This limits the blast radius of a leaked refresh token.

#### Error — Expired Refresh Token (`401`)

```json
{
  "error": {
    "code": "REFRESH_TOKEN_EXPIRED",
    "message": "Refresh token has expired. Please re-authenticate.",
    "request_id": "req_abc123"
  }
}
```

#### curl Example

```bash
curl -X POST https://api.eventrelay.io/api/v1/auth/tokens/refresh \
  -H "Content-Type: application/json" \
  -d '{"refresh_token": "rt_x9Y8w7V6u5T4s3R2q1P0o9N8m7L6k5J4"}'
```

---

## Authentication Flow Diagrams

### API Key Authentication Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant A as API Gateway
    participant F as AuthFilter
    participant DB as PostgreSQL
    participant R as Redis

    C->>A: POST /api/v1/events<br/>Authorization: Bearer sk_live_...
    A->>F: Forward request
    F->>F: Extract key, compute SHA-256 hash
    F->>R: Check key cache
    alt Cache hit
        R-->>F: Return tenant + scopes
    else Cache miss
        F->>DB: SELECT * FROM api_keys WHERE key_hash = ?
        DB-->>F: Return key record
        F->>R: Cache for 5 min
    end
    F->>F: Validate scopes, check expiry
    F->>A: Set SecurityContext
    A->>C: Process request
```

### JWT Authentication Flow

```mermaid
sequenceDiagram
    participant U as Dashboard User
    participant A as API
    participant DB as PostgreSQL
    participant R as Redis

    U->>A: POST /api/v1/auth/tokens<br/>{email, password}
    A->>DB: Verify credentials (bcrypt)
    DB-->>A: User record
    A->>A: Generate JWT (RS256) + Refresh Token
    A->>R: Store refresh token (TTL: 7 days)
    A-->>U: {access_token, refresh_token}

    Note over U,A: 15 minutes later...

    U->>A: POST /api/v1/auth/tokens/refresh<br/>{refresh_token}
    A->>R: Validate + rotate refresh token
    R-->>A: Valid
    A->>A: Generate new JWT
    A-->>U: {new_access_token, new_refresh_token}
```

---

## Security Implementation

### API Key Storage

```java
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    private String id;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;  // SHA-256 hash

    @Column(name = "key_prefix", nullable = false)
    private String keyPrefix;  // First 16 chars for identification

    @Enumerated(EnumType.STRING)
    private KeyEnvironment environment;

    @Convert(converter = StringListConverter.class)
    private List<String> scopes;

    @Enumerated(EnumType.STRING)
    private KeyStatus status;  // ACTIVE, REVOKED, EXPIRED

    private Instant expiresAt;
    private Instant lastUsedAt;
    private Instant createdAt;
    private Instant revokedAt;

    @Type(JsonType.class)
    private Map<String, String> metadata;
}
```

### API Key Generation

```java
@Service
public class ApiKeyService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    public ApiKeyCreateResult createKey(ApiKeyCreateRequest request, String tenantId) {
        String prefix = "live".equals(request.getEnvironment()) ? "sk_live_" : "sk_test_";
        String randomPart = generateBase62(48);
        String fullKey = prefix + randomPart;

        String keyHash = Hashing.sha256()
            .hashString(fullKey, StandardCharsets.UTF_8)
            .toString();

        ApiKey apiKey = new ApiKey();
        apiKey.setId(IdGenerator.generate("key"));
        apiKey.setTenantId(tenantId);
        apiKey.setName(request.getName());
        apiKey.setKeyHash(keyHash);
        apiKey.setKeyPrefix(fullKey.substring(0, 16));
        apiKey.setEnvironment(KeyEnvironment.valueOf(request.getEnvironment().toUpperCase()));
        apiKey.setScopes(request.getScopes());
        apiKey.setStatus(KeyStatus.ACTIVE);
        apiKey.setExpiresAt(request.getExpiresAt());
        apiKey.setCreatedAt(Instant.now());

        apiKeyRepository.save(apiKey);

        return new ApiKeyCreateResult(apiKey, fullKey);  // fullKey returned only here
    }

    private String generateBase62(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62.charAt(SECURE_RANDOM.nextInt(62)));
        }
        return sb.toString();
    }
}
```

### JWT Token Generation

```java
@Service
public class JwtTokenService {

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);

    public TokenPair generateTokens(String tenantId, List<String> scopes) {
        Instant now = Instant.now();

        String accessToken = Jwts.builder()
            .setSubject(tenantId)
            .setIssuer("eventrelay.io")
            .setAudience("eventrelay-api")
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(now.plus(ACCESS_TOKEN_TTL)))
            .setId(IdGenerator.generate("jwt"))
            .claim("scopes", scopes)
            .claim("tenant_id", tenantId)
            .signWith(privateKey, SignatureAlgorithm.RS256)
            .compact();

        String refreshToken = "rt_" + generateSecureToken(40);

        // Store refresh token hash in Redis
        redisTemplate.opsForValue().set(
            "refresh:" + hash(refreshToken),
            tenantId,
            REFRESH_TOKEN_TTL
        );

        return new TokenPair(accessToken, refreshToken,
            ACCESS_TOKEN_TTL.getSeconds(), now.plus(ACCESS_TOKEN_TTL));
    }
}
```

### Spring Security Configuration

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health/**").permitAll()
                .requestMatchers("/api/v1/auth/tokens/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/events/**")
                    .hasAuthority("SCOPE_events:write")
                .requestMatchers(HttpMethod.GET, "/api/v1/events/**")
                    .hasAuthority("SCOPE_events:read")
                .requestMatchers("/api/v1/auth/keys/**")
                    .hasAuthority("SCOPE_admin")
                .anyRequest().authenticated()
            )
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, ApiKeyAuthFilter.class)
            .build();
    }
}
```

---

## Error Responses

### Authentication Error Codes

| HTTP Status | Error Code | Description |
|---|---|---|
| `401` | `MISSING_AUTHORIZATION` | `Authorization` header not provided |
| `401` | `INVALID_API_KEY` | API key is invalid or does not exist |
| `401` | `EXPIRED_API_KEY` | API key has passed its expiration date |
| `401` | `REVOKED_API_KEY` | API key was explicitly revoked |
| `401` | `INVALID_JWT` | JWT signature verification failed |
| `401` | `EXPIRED_JWT` | JWT has expired |
| `401` | `INVALID_CREDENTIALS` | Email/password combination is wrong |
| `401` | `REFRESH_TOKEN_EXPIRED` | Refresh token has expired or been rotated |
| `403` | `INSUFFICIENT_SCOPES` | Valid auth but missing required scope |
| `403` | `ENVIRONMENT_MISMATCH` | Test key used against production endpoint |
| `423` | `ACCOUNT_LOCKED` | Too many failed login attempts |

---

## Production Considerations

### Key Rotation Strategy

1. Create a new API key with the same scopes
2. Update your application to use the new key
3. Monitor `last_used_at` on the old key to confirm migration
4. Revoke the old key once traffic drops to zero

### Security Best Practices

| Practice | Implementation |
|---|---|
| **Key hashing** | SHA-256; plaintext never stored |
| **Brute-force protection** | Account lock after 5 failed attempts (15-min cooldown) |
| **Refresh token rotation** | New refresh token issued on each refresh; old one invalidated |
| **Short-lived JWTs** | 15-minute expiry minimizes exposure window |
| **Scope-based access** | Principle of least privilege; grant only needed scopes |
| **Audit logging** | All key creation/revocation events logged with actor identity |
| **Rate limiting** | Auth endpoints: 10 requests/minute per IP |

### Token Lifetimes

| Token | Lifetime | Storage |
|---|---|---|
| API Key | Until revoked / `expires_at` | PostgreSQL (hashed) |
| JWT Access Token | 15 minutes | Client-side only (stateless) |
| JWT Refresh Token | 7 days | Redis (hashed, TTL-based) |
| API Key cache | 5 minutes | Redis |

---

## Cross-References

| Document | Description |
|---|---|
| [REST_APIs.md](./REST_APIs.md) | Common headers, authentication header format |
| [Error_Codes.md](./Error_Codes.md) | Full error code catalog |
| [Tenant_APIs.md](./Tenant_APIs.md) | Tenant registration (creates initial admin key) |
| [OpenAPI.md](./OpenAPI.md) | Security scheme definitions in OpenAPI spec |
