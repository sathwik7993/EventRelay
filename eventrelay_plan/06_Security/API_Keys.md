# API Key System Design

## Overview

EventRelay uses API keys as the primary authentication mechanism for programmatic access to the platform. The API key system is designed to be secure, developer-friendly, and operationally manageable — following patterns established by Stripe, GitHub, and Twilio.

> [!IMPORTANT]
> API keys are long-lived credentials. They must be stored securely (bcrypt-hashed), displayed only once at creation, and support instant revocation. Never log, cache, or transmit keys in plaintext beyond the initial creation response.

---

## Key Format

### Prefix Convention

All API keys use a structured prefix that encodes environment and type information:

| Prefix | Environment | Usage |
|---|---|---|
| `er_live_` | Production | Real webhook delivery |
| `er_test_` | Sandbox | Test/staging environment |
| `er_admin_` | Internal | Platform administration |

### Key Structure

```
er_live_k3xR9mT2pL7wQ5nF8vJ1hY4cA6bD0gE
└──┬──┘ └────────────────┬────────────────┘
 prefix     32 cryptographically random chars
```

**Total key length**: 40–42 characters (8-char prefix + 32-char random body)

**Character set**: Base62 (`a-z`, `A-Z`, `0-9`) — URL-safe, no special characters that cause encoding issues in headers or environment variables.

### Key Identifier

Each key also has a short, non-secret **key ID** (e.g., `key_2xF9mT`) used for:
- Referencing keys in logs and audit trails
- API responses (list keys, revoke by ID)
- Dashboard display

```
Key ID:    key_2xF9mT          (public, safe to log)
Full Key:  er_live_k3xR9mT2... (secret, show once)
```

---

## Key Generation

### Secure Random Generation

```java
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ApiKeyGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String BASE62_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int KEY_BODY_LENGTH = 32;
    private static final int KEY_ID_LENGTH = 6;

    /**
     * Generates a new API key with the specified environment prefix.
     *
     * @param environment LIVE, TEST, or ADMIN
     * @return ApiKeyPair containing the full key (shown once) and its bcrypt hash
     */
    public ApiKeyPair generateApiKey(KeyEnvironment environment) {
        String prefix = environment.getPrefix();     // e.g., "er_live_"
        String keyBody = generateRandomString(KEY_BODY_LENGTH);
        String keyId = "key_" + generateRandomString(KEY_ID_LENGTH);
        String fullKey = prefix + keyBody;

        // Hash immediately — the plaintext key leaves this method and is never stored
        String hashedKey = BCrypt.hashpw(fullKey, BCrypt.gensalt(12));

        // Generate a fast-lookup prefix hash (first 8 chars of SHA-256)
        // Used to locate the key record without bcrypt-comparing every key
        String prefixHash = computePrefixHash(fullKey);

        return ApiKeyPair.builder()
            .keyId(keyId)
            .plaintextKey(fullKey)          // Show to user ONCE
            .hashedKey(hashedKey)            // Store in DB
            .prefixHash(prefixHash)          // Store in DB for lookup
            .environment(environment)
            .maskedKey(maskKey(fullKey))     // e.g., "er_live_...a1b2"
            .build();
    }

    private String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = SECURE_RANDOM.nextInt(BASE62_CHARS.length());
            sb.append(BASE62_CHARS.charAt(index));
        }
        return sb.toString();
    }

    private String computePrefixHash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String maskKey(String key) {
        // "er_live_k3xR9m...a1b2"
        String prefix = key.substring(0, key.indexOf('_', 3) + 1); // "er_live_"
        String lastFour = key.substring(key.length() - 4);
        return prefix + "..." + lastFour;
    }
}
```

### Why SecureRandom?

| Aspect | `SecureRandom` | `Random` / `ThreadLocalRandom` |
|---|---|---|
| Entropy source | OS entropy pool (`/dev/urandom`) | Linear congruential generator |
| Predictability | Cryptographically unpredictable | Predictable if seed is known |
| Suitable for secrets | ✅ Yes | ❌ No |
| Performance | ~1μs per call (acceptable) | ~0.1μs per call |

> [!CAUTION]
> Never use `java.util.Random` or `Math.random()` for API key generation. These are deterministic PRNGs and can be predicted by an attacker who observes enough output.

---

## Key Storage

### Database Schema

```sql
CREATE TABLE api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_id          VARCHAR(20) NOT NULL UNIQUE,       -- "key_2xF9mT" (public identifier)
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    hashed_key      VARCHAR(72) NOT NULL,              -- bcrypt hash ($2a$12$...)
    prefix_hash     VARCHAR(16) NOT NULL,              -- SHA-256 prefix for fast lookup
    environment     VARCHAR(10) NOT NULL,              -- LIVE, TEST, ADMIN
    name            VARCHAR(100),                       -- Human-readable label
    scopes          TEXT[] NOT NULL DEFAULT '{}',       -- {"events:write", "subscriptions:read"}
    masked_key      VARCHAR(30) NOT NULL,              -- "er_live_...a1b2"
    last_used_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,                        -- NULL = never expires
    revoked_at      TIMESTAMPTZ,                        -- NULL = active
    revoked_by      UUID REFERENCES users(id),
    revocation_reason VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID REFERENCES users(id),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_environment CHECK (environment IN ('LIVE', 'TEST', 'ADMIN'))
);

-- Fast lookup by prefix hash during authentication
CREATE INDEX idx_api_keys_prefix_hash ON api_keys(prefix_hash) WHERE revoked_at IS NULL;

-- Tenant's active keys
CREATE INDEX idx_api_keys_tenant_active ON api_keys(tenant_id, environment)
    WHERE revoked_at IS NULL;

-- Expiration cleanup
CREATE INDEX idx_api_keys_expires ON api_keys(expires_at)
    WHERE expires_at IS NOT NULL AND revoked_at IS NULL;
```

### Storage Security Properties

```
┌──────────────────────────────────────────────────────────────┐
│                    API Key Lifecycle                          │
│                                                              │
│  Generation ──► Show Once ──► Stored as bcrypt hash          │
│                    │                                         │
│                    ▼                                         │
│            User copies key                                   │
│            (only opportunity)                                │
│                    │                                         │
│                    ▼                                         │
│          Plaintext key NEVER                                 │
│          exists in our system again                          │
│                                                              │
│  Authentication:                                             │
│    1. Extract prefix_hash from incoming key                  │
│    2. Find candidate records by prefix_hash                  │
│    3. BCrypt.checkpw(incoming_key, stored_hash)              │
└──────────────────────────────────────────────────────────────┘
```

### JPA Entity

```java
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "key_id", nullable = false, unique = true)
    private String keyId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "hashed_key", nullable = false)
    private String hashedKey;

    @Column(name = "prefix_hash", nullable = false)
    private String prefixHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KeyEnvironment environment;

    private String name;

    @Column(name = "masked_key", nullable = false)
    private String maskedKey;

    @Type(ListArrayType.class)
    @Column(columnDefinition = "text[]")
    private List<String> scopes;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revocation_reason")
    private String revocationReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    // Getters, setters, builder pattern...

    public boolean isActive() {
        return revokedAt == null
            && (expiresAt == null || expiresAt.isAfter(Instant.now()));
    }
}
```

---

## Key Display and Masking

### Creation Response (Only Time Full Key Is Shown)

```json
{
  "key_id": "key_2xF9mT",
  "api_key": "er_live_k3xR9mT2pL7wQ5nF8vJ1hY4cA6bD0gE",
  "masked_key": "er_live_...D0gE",
  "environment": "LIVE",
  "scopes": ["events:write", "subscriptions:read"],
  "created_at": "2026-07-10T04:00:00Z",
  "expires_at": null,
  "_warning": "Store this key securely. It will not be shown again."
}
```

### Subsequent List/Get Responses

```json
{
  "key_id": "key_2xF9mT",
  "masked_key": "er_live_...D0gE",
  "environment": "LIVE",
  "scopes": ["events:write", "subscriptions:read"],
  "last_used_at": "2026-07-10T03:45:12Z",
  "created_at": "2026-07-10T04:00:00Z",
  "status": "active"
}
```

> [!NOTE]
> The full API key is never returned in any API response after creation. This is the same pattern used by Stripe, GitHub, and AWS. If a user loses their key, they must generate a new one.

---

## Authentication Flow

### Request Authentication

```
Client Request:
  GET /api/v1/events
  Authorization: Bearer er_live_k3xR9mT2pL7wQ5nF8vJ1hY4cA6bD0gE

Server Flow:
  1. Extract key from Authorization header
  2. Compute prefix_hash = SHA256(key)[0:16]
  3. SELECT * FROM api_keys WHERE prefix_hash = ? AND revoked_at IS NULL
  4. For each candidate: BCrypt.checkpw(key, candidate.hashed_key)
  5. If match found → authenticate, update last_used_at
  6. If no match → 401 Unauthorized
```

### Spring Security Integration

```java
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyAuthenticationService apiKeyService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer er_")) {
            String apiKey = authHeader.substring(7); // Remove "Bearer "

            try {
                ApiKeyAuthentication authentication = apiKeyService.authenticate(apiKey);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (InvalidApiKeyException e) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("""
                    {"error": "invalid_api_key", "message": "The API key provided is invalid or has been revoked."}
                    """);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip for JWT-authenticated dashboard routes
        String path = request.getRequestURI();
        return path.startsWith("/dashboard/") || path.startsWith("/auth/");
    }
}
```

```java
@Service
public class ApiKeyAuthenticationService {

    private final ApiKeyRepository apiKeyRepository;

    @Transactional
    public ApiKeyAuthentication authenticate(String plaintextKey) {
        // Step 1: Compute prefix hash for fast lookup
        String prefixHash = computePrefixHash(plaintextKey);

        // Step 2: Find candidate keys
        List<ApiKey> candidates = apiKeyRepository
            .findByPrefixHashAndRevokedAtIsNull(prefixHash);

        // Step 3: BCrypt verify against each candidate
        for (ApiKey candidate : candidates) {
            if (BCrypt.checkpw(plaintextKey, candidate.getHashedKey())) {
                // Step 4: Check expiration
                if (!candidate.isActive()) {
                    throw new InvalidApiKeyException("API key has expired");
                }

                // Step 5: Update last_used_at (async to avoid auth latency)
                updateLastUsedAsync(candidate.getId());

                // Step 6: Build authentication object
                return new ApiKeyAuthentication(
                    candidate.getTenantId(),
                    candidate.getKeyId(),
                    candidate.getScopes(),
                    candidate.getEnvironment()
                );
            }
        }

        throw new InvalidApiKeyException("Invalid API key");
    }

    @Async
    void updateLastUsedAsync(UUID keyId) {
        apiKeyRepository.updateLastUsedAt(keyId, Instant.now());
    }
}
```

### Security Configuration

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            ApiKeyAuthenticationFilter apiKeyFilter,
            JwtAuthenticationFilter jwtFilter) throws Exception {

        return http
            .securityMatcher("/api/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/events/**")
                    .hasAuthority("SCOPE_events:write")
                .requestMatchers(HttpMethod.GET, "/api/v1/events/**")
                    .hasAuthority("SCOPE_events:read")
                .requestMatchers("/api/v1/subscriptions/**")
                    .hasAuthority("SCOPE_subscriptions:manage")
                .anyRequest().authenticated()
            )
            .build();
    }
}
```

---

## Key Revocation

### Revocation API

```
POST /api/v1/keys/{key_id}/revoke
Authorization: Bearer er_live_...
Content-Type: application/json

{
  "reason": "Key compromised — rotated to new key"
}
```

### Revocation Implementation

```java
@Service
public class ApiKeyRevocationService {

    private final ApiKeyRepository apiKeyRepository;
    private final SecurityAuditLogger auditLogger;
    private final RevokedKeyCache revokedKeyCache;

    @Transactional
    public void revokeKey(String keyId, UUID revokedBy, String reason) {
        ApiKey apiKey = apiKeyRepository.findByKeyId(keyId)
            .orElseThrow(() -> new KeyNotFoundException(keyId));

        if (apiKey.getRevokedAt() != null) {
            throw new KeyAlreadyRevokedException(keyId);
        }

        apiKey.setRevokedAt(Instant.now());
        apiKey.setRevokedBy(revokedBy);
        apiKey.setRevocationReason(reason);
        apiKeyRepository.save(apiKey);

        // Add to Redis cache for fast rejection (avoid DB lookup for revoked keys)
        revokedKeyCache.markRevoked(apiKey.getPrefixHash());

        // Audit log
        auditLogger.logKeyRevocation(keyId, revokedBy, reason);
    }

    /**
     * Emergency: revoke ALL keys for a tenant (e.g., account compromise)
     */
    @Transactional
    public int revokeAllTenantKeys(UUID tenantId, UUID revokedBy, String reason) {
        int count = apiKeyRepository.revokeAllByTenantId(
            tenantId, Instant.now(), revokedBy, reason);

        auditLogger.logBulkKeyRevocation(tenantId, count, revokedBy, reason);
        return count;
    }
}
```

### Revocation Propagation

```
Key Revoked
    │
    ├──► PostgreSQL: revoked_at = NOW()          (source of truth)
    ├──► Redis: add prefix_hash to revoked set   (fast rejection, TTL 24h)
    └──► Audit Log: security event emitted        (compliance)

On Authentication:
    1. Check Redis revoked set first (O(1))
    2. If not in revoked set, proceed to DB lookup
    3. DB query filters: WHERE revoked_at IS NULL
```

---

## Key Rotation

### Rotation Workflow

```mermaid
sequenceDiagram
    participant Tenant
    participant EventRelay API
    participant Database

    Note over Tenant: Rotation starts
    Tenant->>EventRelay API: POST /api/v1/keys (create new key)
    EventRelay API->>Database: Insert new key (active)
    EventRelay API-->>Tenant: New key: er_live_newKey...

    Note over Tenant: Update systems to use new key<br/>(overlapping validity window)

    Tenant->>EventRelay API: POST /api/v1/keys/{old_key_id}/revoke
    EventRelay API->>Database: Set revoked_at on old key
    EventRelay API-->>Tenant: Old key revoked

    Note over Tenant: Rotation complete
```

### Overlapping Validity Window

During rotation, **both old and new keys are valid simultaneously**. This allows:
- Rolling deployments across multiple services
- Gradual migration without downtime
- Verification that the new key works before revoking the old one

```java
@RestController
@RequestMapping("/api/v1/keys")
public class ApiKeyController {

    @PostMapping
    public ResponseEntity<ApiKeyCreationResponse> createKey(
            @RequestBody CreateApiKeyRequest request,
            @AuthenticationPrincipal TenantPrincipal principal) {

        // Validate: max 10 active keys per tenant per environment
        long activeCount = apiKeyRepository.countActiveByTenantAndEnvironment(
            principal.getTenantId(), request.getEnvironment());

        if (activeCount >= 10) {
            throw new TooManyKeysException(
                "Maximum 10 active keys per environment. Revoke unused keys first.");
        }

        ApiKeyPair keyPair = apiKeyGenerator.generateApiKey(request.getEnvironment());

        ApiKey entity = ApiKey.builder()
            .keyId(keyPair.getKeyId())
            .tenantId(principal.getTenantId())
            .hashedKey(keyPair.getHashedKey())
            .prefixHash(keyPair.getPrefixHash())
            .environment(request.getEnvironment())
            .name(request.getName())
            .maskedKey(keyPair.getMaskedKey())
            .scopes(request.getScopes())
            .expiresAt(request.getExpiresAt())
            .createdBy(principal.getUserId())
            .createdAt(Instant.now())
            .build();

        apiKeyRepository.save(entity);

        return ResponseEntity.status(HttpStatus.CREATED).body(
            new ApiKeyCreationResponse(
                keyPair.getKeyId(),
                keyPair.getPlaintextKey(),  // Only time this is returned
                keyPair.getMaskedKey(),
                request.getEnvironment(),
                request.getScopes()
            )
        );
    }
}
```

---

## Key Scoping

### Available Scopes

| Scope | Description | Typical Use |
|---|---|---|
| `events:write` | Publish events | Event producer services |
| `events:read` | Read event history and status | Monitoring dashboards |
| `subscriptions:manage` | CRUD subscription endpoints | Admin/setup tools |
| `subscriptions:read` | List subscriptions | Read-only access |
| `dlq:read` | View dead-letter queue | Debugging tools |
| `dlq:replay` | Replay dead-letter events | Ops tooling |
| `keys:manage` | Create/revoke API keys | Key management |
| `tenant:admin` | Full tenant administration | Root admin key |

### Scope Validation

```java
public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final UUID tenantId;
    private final String keyId;
    private final List<String> scopes;
    private final KeyEnvironment environment;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return scopes.stream()
            .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
            .collect(Collectors.toList());
    }

    /**
     * Verifies that this key has the required scope.
     * tenant:admin scope implies all permissions.
     */
    public boolean hasScope(String requiredScope) {
        return scopes.contains("tenant:admin")
            || scopes.contains(requiredScope);
    }
}
```

### Scope Enforcement Example

```java
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_events:write')")
    public ResponseEntity<EventResponse> publishEvent(
            @Valid @RequestBody PublishEventRequest request) {
        // Only keys with events:write scope can reach here
    }

    @GetMapping("/{eventId}")
    @PreAuthorize("hasAuthority('SCOPE_events:read')")
    public ResponseEntity<EventResponse> getEvent(
            @PathVariable UUID eventId) {
        // Only keys with events:read scope can reach here
    }
}
```

---

## Production Considerations

### Performance

| Operation | Latency | Notes |
|---|---|---|
| Key generation | ~5ms | SecureRandom + bcrypt(12 rounds) |
| Authentication | ~100ms | Prefix hash lookup + bcrypt verify |
| Revocation | ~2ms | DB update + Redis write |
| Scope check | <1μs | In-memory set lookup |

### Security Hardening

1. **Constant-time comparison**: BCrypt.checkpw already uses constant-time comparison internally
2. **Rate limit failed auth attempts**: Max 20 failed attempts per IP per minute
3. **No key enumeration**: Always return generic 401, never "key not found" vs "key revoked"
4. **Audit all key operations**: Creation, revocation, failed authentication
5. **Key expiration job**: Background job to clean up expired keys daily

### Monitoring Alerts

```yaml
# Prometheus alerting rules
groups:
  - name: api_key_security
    rules:
      - alert: HighAuthFailureRate
        expr: rate(api_key_auth_failures_total[5m]) > 10
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "High API key authentication failure rate"

      - alert: BulkKeyRevocation
        expr: increase(api_key_revocations_total[1m]) > 5
        for: 0m
        labels:
          severity: critical
        annotations:
          summary: "Bulk API key revocation detected — possible compromise"

      - alert: KeyNearingExpiration
        expr: api_keys_expiring_within_7d > 0
        for: 1h
        labels:
          severity: info
        annotations:
          summary: "API keys expiring within 7 days"
```

---

## Related Documents

- [JWT Authentication](./JWT.md) — Dashboard authentication
- [Secret Rotation](./Secret_Rotation.md) — Key rotation procedures
- [Rate Limiting](./Rate_Limiting.md) — Authentication rate limits
- [Security Best Practices](./Security_Best_Practices.md) — Overall security posture
