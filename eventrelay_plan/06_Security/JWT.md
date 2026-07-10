# JWT Authentication

## Overview

EventRelay uses JSON Web Tokens (JWT) for authenticating dashboard users and management API sessions. While API keys handle service-to-service communication, JWTs handle interactive user sessions with short-lived access tokens and longer-lived refresh tokens.

> [!NOTE]
> JWTs are used **only** for dashboard/management UI authentication. Programmatic API access uses [API Keys](./API_Keys.md). This separation follows the principle of using the right credential type for each access pattern.

---

## JWT Architecture

```mermaid
sequenceDiagram
    participant Browser
    participant Auth API
    participant Redis
    participant Database

    Browser->>Auth API: POST /auth/login {email, password}
    Auth API->>Database: Validate credentials (bcrypt)
    Database-->>Auth API: User + Tenant info
    Auth API->>Auth API: Generate access token (15min)
    Auth API->>Auth API: Generate refresh token (7d)
    Auth API->>Database: Store refresh token hash
    Auth API-->>Browser: {access_token, refresh_token, expires_in}

    Note over Browser: Access token stored in memory<br/>Refresh token in httpOnly cookie

    Browser->>Auth API: GET /api/v1/events (Authorization: Bearer <access_token>)
    Auth API->>Auth API: Validate JWT signature + claims
    Auth API->>Redis: Check token not blacklisted
    Auth API-->>Browser: 200 OK + data

    Note over Browser: Access token expires after 15min

    Browser->>Auth API: POST /auth/refresh (refresh_token cookie)
    Auth API->>Database: Validate refresh token hash
    Auth API->>Auth API: Generate new access + refresh tokens
    Auth API->>Database: Rotate refresh token hash
    Auth API-->>Browser: {access_token, refresh_token, expires_in}
```

---

## Token Structure

### Access Token

A standard JWT with three Base64URL-encoded parts separated by dots:

```
eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InJzYS0yMDI2LTA3In0.
eyJzdWIiOiJ1c2VyXzEyMyIsInRlbmFudF9pZCI6InRlbmFudF80NTYiLCJyb2xlcyI6WyJhZG1pbiJdLCJpYXQiOjE3NTIxMjM0NTYsImV4cCI6MTc1MjEyNDM1NiwiaXNzIjoiZXZlbnRyZWxheSIsImp0aSI6InRva18xMjM0NTYifQ.
<RS256-signature>
```

### Header

```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "rsa-2026-07"
}
```

| Field | Description |
|---|---|
| `alg` | Signing algorithm — RS256 (RSA with SHA-256) |
| `typ` | Token type — always "JWT" |
| `kid` | Key ID — identifies which signing key was used (critical for key rotation) |

### Payload (Claims)

```json
{
  "sub": "user_abc123",
  "tenant_id": "tenant_def456",
  "email": "admin@acmecorp.com",
  "roles": ["admin", "developer"],
  "permissions": ["events:read", "events:write", "subscriptions:manage"],
  "environment": "LIVE",
  "iss": "eventrelay",
  "aud": "eventrelay-dashboard",
  "iat": 1752123456,
  "exp": 1752124356,
  "nbf": 1752123456,
  "jti": "tok_7f8a9b2c"
}
```

### Claims Reference

| Claim | Type | Required | Description |
|---|---|---|---|
| `sub` | String | ✅ | Subject — unique user ID |
| `tenant_id` | String | ✅ | Tenant the user belongs to (critical for data isolation) |
| `email` | String | ✅ | User email (for display/audit) |
| `roles` | String[] | ✅ | User roles: `owner`, `admin`, `developer`, `viewer` |
| `permissions` | String[] | ✅ | Granular permissions derived from roles |
| `environment` | String | ✅ | `LIVE` or `TEST` |
| `iss` | String | ✅ | Issuer — always `"eventrelay"` |
| `aud` | String | ✅ | Audience — `"eventrelay-dashboard"` |
| `iat` | Number | ✅ | Issued At — Unix timestamp (seconds) |
| `exp` | Number | ✅ | Expiration — Unix timestamp (seconds) |
| `nbf` | Number | ✅ | Not Before — token not valid before this time |
| `jti` | String | ✅ | JWT ID — unique token identifier (for revocation) |

> [!WARNING]
> Never include sensitive data in JWT claims (passwords, API keys, PII beyond email). JWTs are Base64-encoded, **not encrypted** — anyone can decode and read the payload.

---

## Signing Algorithm: RS256

### Why RS256 Over HS256?

| Aspect | RS256 (asymmetric) | HS256 (symmetric) |
|---|---|---|
| Key type | RSA private/public key pair | Single shared secret |
| Signing | Private key (auth server only) | Shared secret |
| Verification | Public key (any service) | Shared secret |
| Key distribution | Only public key needs distribution | Secret must be shared with all verifiers |
| Key rotation | Easier — new key pair, publish public key | Harder — all services need new secret |
| Security | Compromise of verifier doesn't compromise signing | Any verifier compromise = full compromise |

### Key Pair Management

```java
@Configuration
public class JwtKeyConfig {

    @Value("${jwt.private-key-path}")
    private String privateKeyPath;

    @Value("${jwt.public-key-path}")
    private String publicKeyPath;

    @Value("${jwt.key-id}")
    private String keyId;

    @Bean
    public RSAPrivateKey jwtSigningKey() throws Exception {
        String keyPem = Files.readString(Path.of(privateKeyPath));
        String keyContent = keyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(keyContent);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    @Bean
    public RSAPublicKey jwtVerificationKey() throws Exception {
        String keyPem = Files.readString(Path.of(publicKeyPath));
        String keyContent = keyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(keyContent);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    @Bean
    public String jwtKeyId() {
        return keyId; // e.g., "rsa-2026-07"
    }
}
```

### Key Generation (Ops)

```bash
# Generate 4096-bit RSA key pair
openssl genpkey -algorithm RSA -out jwt-private.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem

# Store private key in AWS Secrets Manager
aws secretsmanager create-secret \
  --name eventrelay/jwt-private-key \
  --secret-string file://jwt-private.pem

# Public key can be distributed freely
# Serve at /.well-known/jwks.json for federation
```

---

## Token Lifecycle

### Configuration

```yaml
# application.yml
eventrelay:
  jwt:
    access-token:
      expiration: 900          # 15 minutes (seconds)
    refresh-token:
      expiration: 604800       # 7 days (seconds)
      max-reuse-count: 1       # Refresh token rotation — single use
    issuer: "eventrelay"
    audience: "eventrelay-dashboard"
    clock-skew-tolerance: 30   # 30 seconds tolerance for clock drift
```

### Token Expiration Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│                    Token Lifecycle                                │
│                                                                  │
│  Login ──────────► Access Token (15 min) ──────► Expired         │
│     │                                              │             │
│     │                                              ▼             │
│     └──────────► Refresh Token (7 days) ──► Refresh ──► New AT   │
│                       │                                  │       │
│                       │                              New RT      │
│                       │                         (old RT invalid)  │
│                       ▼                                          │
│                   7 days later ──► Re-login required              │
└─────────────────────────────────────────────────────────────────┘
```

| Token Type | TTL | Storage | Purpose |
|---|---|---|---|
| Access Token | 15 minutes | JavaScript memory (never localStorage) | API authorization |
| Refresh Token | 7 days | httpOnly, Secure, SameSite=Strict cookie | Obtain new access token |

---

## Token Service Implementation

```java
@Service
@Slf4j
public class JwtTokenService {

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final String keyId;
    private final JwtProperties jwtProperties;

    /**
     * Generates an access token for an authenticated user.
     */
    public String generateAccessToken(AuthenticatedUser user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtProperties.getAccessToken().getExpiration(), ChronoUnit.SECONDS);
        String tokenId = "tok_" + generateTokenId();

        return Jwts.builder()
            .header()
                .keyId(keyId)
                .type("JWT")
                .and()
            .subject(user.getUserId())
            .claim("tenant_id", user.getTenantId())
            .claim("email", user.getEmail())
            .claim("roles", user.getRoles())
            .claim("permissions", user.getPermissions())
            .claim("environment", user.getEnvironment())
            .issuer(jwtProperties.getIssuer())
            .audience().add(jwtProperties.getAudience()).and()
            .issuedAt(Date.from(now))
            .notBefore(Date.from(now))
            .expiration(Date.from(expiry))
            .id(tokenId)
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact();
    }

    /**
     * Generates a refresh token (opaque, stored hashed in DB).
     */
    public RefreshTokenPair generateRefreshToken(String userId, String tenantId) {
        String tokenValue = generateSecureToken(64);
        String tokenHash = hashRefreshToken(tokenValue);
        Instant expiry = Instant.now()
            .plus(jwtProperties.getRefreshToken().getExpiration(), ChronoUnit.SECONDS);

        return RefreshTokenPair.builder()
            .tokenValue(tokenValue)    // Sent to client in httpOnly cookie
            .tokenHash(tokenHash)      // Stored in database
            .expiresAt(expiry)
            .userId(userId)
            .tenantId(tenantId)
            .build();
    }

    /**
     * Validates and parses an access token.
     */
    public TokenClaims validateAccessToken(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(jwtProperties.getIssuer())
                .requireAudience(jwtProperties.getAudience())
                .clockSkewSeconds(jwtProperties.getClockSkewTolerance())
                .build()
                .parseSignedClaims(token);

            Claims claims = jws.getPayload();

            return TokenClaims.builder()
                .userId(claims.getSubject())
                .tenantId(claims.get("tenant_id", String.class))
                .email(claims.get("email", String.class))
                .roles(claims.get("roles", List.class))
                .permissions(claims.get("permissions", List.class))
                .tokenId(claims.getId())
                .expiresAt(claims.getExpiration().toInstant())
                .build();

        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException("Access token has expired", e);
        } catch (JwtException e) {
            throw new InvalidTokenException("Invalid access token", e);
        }
    }

    private String generateSecureToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashRefreshToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String generateTokenId() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
```

---

## Token Refresh Flow

### Refresh Token Rotation

EventRelay implements **refresh token rotation** — each refresh token is single-use. When a refresh token is used, a new refresh token is issued and the old one is invalidated.

```java
@Service
@Transactional
public class TokenRefreshService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenService jwtTokenService;
    private final UserRepository userRepository;
    private final SecurityAuditLogger auditLogger;

    public AuthTokenResponse refreshTokens(String refreshTokenValue) {
        // Hash the provided refresh token
        String tokenHash = hashRefreshToken(refreshTokenValue);

        // Look up the stored token
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
            .orElseThrow(() -> {
                // Possible token reuse attack — revoke all tokens for this family
                log.warn("Refresh token not found — possible reuse attack");
                return new InvalidTokenException("Invalid refresh token");
            });

        // Check if token has been used before (reuse detection)
        if (storedToken.isUsed()) {
            // SECURITY: Token reuse detected — revoke entire token family
            log.error("Refresh token reuse detected for user {}. Revoking all sessions.",
                storedToken.getUserId());
            refreshTokenRepository.revokeAllByUserId(storedToken.getUserId());
            auditLogger.logSuspiciousTokenReuse(storedToken.getUserId());
            throw new TokenReuseException("Refresh token has already been used. All sessions revoked.");
        }

        // Check expiration
        if (storedToken.getExpiresAt().isBefore(Instant.now())) {
            throw new TokenExpiredException("Refresh token has expired");
        }

        // Mark current token as used
        storedToken.setUsed(true);
        storedToken.setUsedAt(Instant.now());
        refreshTokenRepository.save(storedToken);

        // Load user data for new access token
        AuthenticatedUser user = userRepository.findAuthenticatedUser(storedToken.getUserId())
            .orElseThrow(() -> new UserNotFoundException(storedToken.getUserId()));

        // Generate new token pair
        String newAccessToken = jwtTokenService.generateAccessToken(user);
        RefreshTokenPair newRefreshToken = jwtTokenService.generateRefreshToken(
            user.getUserId(), user.getTenantId());

        // Store new refresh token
        RefreshToken newStoredToken = RefreshToken.builder()
            .tokenHash(newRefreshToken.getTokenHash())
            .userId(user.getUserId())
            .tenantId(user.getTenantId())
            .expiresAt(newRefreshToken.getExpiresAt())
            .familyId(storedToken.getFamilyId()) // Same family for reuse detection
            .createdAt(Instant.now())
            .build();
        refreshTokenRepository.save(newStoredToken);

        return AuthTokenResponse.builder()
            .accessToken(newAccessToken)
            .refreshToken(newRefreshToken.getTokenValue())
            .expiresIn(jwtTokenService.getAccessTokenExpiration())
            .tokenType("Bearer")
            .build();
    }
}
```

### Refresh Token Database Schema

```sql
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash  VARCHAR(64) NOT NULL UNIQUE,     -- SHA-256 hash
    user_id     VARCHAR(50) NOT NULL,
    tenant_id   VARCHAR(50) NOT NULL,
    family_id   UUID NOT NULL,                   -- Groups related tokens for reuse detection
    is_used     BOOLEAN NOT NULL DEFAULT FALSE,
    used_at     TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    user_agent  TEXT,                            -- Browser/device info
    ip_address  INET                             -- Client IP for audit
);

CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens(expires_at);
```

---

## Token Revocation via Redis Blacklist

Since JWTs are stateless, revocation requires a **blacklist** of invalidated token IDs (JTI). Redis is ideal for this because:
- O(1) lookup per request
- Automatic TTL-based cleanup (tokens expire naturally)
- High throughput for the authentication hot path

### Blacklist Implementation

```java
@Service
public class TokenBlacklistService {

    private final StringRedisTemplate redisTemplate;
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    /**
     * Blacklists a token. The Redis key automatically expires when the token would
     * have expired anyway, so the blacklist is self-cleaning.
     */
    public void blacklistToken(String jti, Instant tokenExpiration) {
        String key = BLACKLIST_PREFIX + jti;
        Duration ttl = Duration.between(Instant.now(), tokenExpiration);

        if (ttl.isPositive()) {
            redisTemplate.opsForValue().set(key, "revoked", ttl);
        }
        // If TTL is negative, token already expired — no need to blacklist
    }

    /**
     * Checks if a token has been blacklisted.
     */
    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
    }

    /**
     * Blacklists ALL tokens for a user (logout from all devices).
     * Stores a "revoked before" timestamp — any token issued before this time is invalid.
     */
    public void blacklistAllUserTokens(String userId) {
        String key = "jwt:user_revoked:" + userId;
        redisTemplate.opsForValue().set(key, String.valueOf(Instant.now().getEpochSecond()),
            Duration.ofDays(7)); // Max refresh token lifetime
    }

    public boolean isUserTokenRevokedBefore(String userId, Instant tokenIssuedAt) {
        String revokedBefore = redisTemplate.opsForValue().get("jwt:user_revoked:" + userId);
        if (revokedBefore == null) return false;
        return tokenIssuedAt.getEpochSecond() <= Long.parseLong(revokedBefore);
    }
}
```

---

## Spring Security JWT Filter

```java
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final TokenBlacklistService blacklistService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")
                && !authHeader.startsWith("Bearer er_")) {
            // JWT token (not an API key)
            String token = authHeader.substring(7);

            try {
                // Step 1: Validate signature and standard claims
                TokenClaims claims = jwtTokenService.validateAccessToken(token);

                // Step 2: Check individual token blacklist
                if (blacklistService.isBlacklisted(claims.getTokenId())) {
                    sendUnauthorized(response, "Token has been revoked");
                    return;
                }

                // Step 3: Check user-level revocation
                if (blacklistService.isUserTokenRevokedBefore(
                        claims.getUserId(), claims.getIssuedAt())) {
                    sendUnauthorized(response, "All sessions have been revoked");
                    return;
                }

                // Step 4: Build Spring Security authentication
                JwtAuthentication authentication = new JwtAuthentication(
                    claims.getUserId(),
                    claims.getTenantId(),
                    claims.getRoles(),
                    claims.getPermissions()
                );
                authentication.setAuthenticated(true);
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (TokenExpiredException e) {
                sendUnauthorized(response, "Token has expired");
                return;
            } catch (InvalidTokenException e) {
                log.warn("Invalid JWT received: {}", e.getMessage());
                sendUnauthorized(response, "Invalid token");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse response, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
            String.format("{\"error\": \"unauthorized\", \"message\": \"%s\"}", message));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/auth/") || path.equals("/api/v1/health");
    }
}
```

---

## Login and Logout Endpoints

```java
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationService authService;
    private final TokenRefreshService refreshService;
    private final TokenBlacklistService blacklistService;
    private final JwtTokenService jwtTokenService;

    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {

        AuthenticatedUser user = authService.authenticate(
            request.getEmail(), request.getPassword());

        String accessToken = jwtTokenService.generateAccessToken(user);
        RefreshTokenPair refreshToken = jwtTokenService.generateRefreshToken(
            user.getUserId(), user.getTenantId());

        // Set refresh token as httpOnly cookie
        ResponseCookie refreshCookie = ResponseCookie.from("refresh_token", refreshToken.getTokenValue())
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/auth/refresh")
            .maxAge(Duration.ofDays(7))
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        return ResponseEntity.ok(AuthTokenResponse.builder()
            .accessToken(accessToken)
            .expiresIn(900) // 15 minutes
            .tokenType("Bearer")
            .build());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refresh(
            @CookieValue("refresh_token") String refreshToken,
            HttpServletResponse response) {

        AuthTokenResponse tokens = refreshService.refreshTokens(refreshToken);

        // Rotate refresh token cookie
        ResponseCookie refreshCookie = ResponseCookie.from("refresh_token", tokens.getRefreshToken())
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/auth/refresh")
            .maxAge(Duration.ofDays(7))
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        return ResponseEntity.ok(tokens);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader("Authorization") String authHeader,
            @CookieValue(value = "refresh_token", required = false) String refreshToken,
            HttpServletResponse response) {

        // Blacklist access token
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            TokenClaims claims = jwtTokenService.validateAccessToken(token);
            blacklistService.blacklistToken(claims.getTokenId(), claims.getExpiresAt());
        }

        // Clear refresh token cookie
        ResponseCookie clearCookie = ResponseCookie.from("refresh_token", "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/auth/refresh")
            .maxAge(0)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(
            @AuthenticationPrincipal JwtPrincipal principal) {
        // Blacklist ALL tokens for this user
        blacklistService.blacklistAllUserTokens(principal.getUserId());
        return ResponseEntity.noContent().build();
    }
}
```

---

## JWKS Endpoint

Expose public keys for external verification:

```java
@RestController
@RequestMapping("/.well-known")
public class JwksController {

    private final RSAPublicKey publicKey;
    private final String keyId;

    @GetMapping("/jwks.json")
    public Map<String, Object> jwks() {
        Map<String, Object> jwk = Map.of(
            "kty", "RSA",
            "kid", keyId,
            "use", "sig",
            "alg", "RS256",
            "n", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(publicKey.getModulus().toByteArray()),
            "e", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(publicKey.getPublicExponent().toByteArray())
        );

        return Map.of("keys", List.of(jwk));
    }
}
```

---

## Security Considerations

### Token Storage (Client-Side)

| Storage | Access Token | Refresh Token |
|---|---|---|
| JavaScript memory | ✅ Recommended | ❌ Never |
| httpOnly cookie | ⚠️ CSRF risk | ✅ Recommended |
| localStorage | ❌ XSS vulnerable | ❌ Never |
| sessionStorage | ⚠️ XSS vulnerable | ❌ Never |

### Attack Prevention

| Attack | Mitigation |
|---|---|
| Token theft (XSS) | Access token in memory only; refresh token in httpOnly cookie |
| CSRF | SameSite=Strict on refresh cookie; CSRF token for state-changing requests |
| Token replay | Short-lived access tokens (15min); refresh token rotation |
| Token reuse | Single-use refresh tokens; family-based revocation on reuse detection |
| Brute force | Rate limiting on `/auth/login` (5 attempts per minute per email) |
| Algorithm confusion | Explicitly specify RS256; reject tokens with `alg: none` |
| Key confusion | Validate `kid` header against known key IDs |

### JWT Validation Checklist

```java
// All of these are enforced by our JwtTokenService:
// ✅ Verify RS256 signature with public key
// ✅ Check exp claim (token not expired)
// ✅ Check nbf claim (token not used before valid time)
// ✅ Check iss claim (must be "eventrelay")
// ✅ Check aud claim (must be "eventrelay-dashboard")
// ✅ Check jti against blacklist (revocation)
// ✅ Check user-level revocation timestamp
// ✅ Allow 30-second clock skew tolerance
// ✅ Reject tokens with alg: none
// ✅ Validate kid matches a known key
```

---

## Production Considerations

### Monitoring

```yaml
# Prometheus metrics
jwt_tokens_issued_total{type="access|refresh"}
jwt_tokens_validated_total{result="success|expired|invalid|blacklisted"}
jwt_tokens_blacklisted_total
jwt_refresh_reuse_detected_total
auth_login_attempts_total{result="success|failure"}
```

### Cleanup Jobs

```sql
-- Clean up expired refresh tokens (run daily)
DELETE FROM refresh_tokens
WHERE expires_at < NOW() - INTERVAL '1 day';

-- Clean up used refresh tokens older than 24 hours
DELETE FROM refresh_tokens
WHERE is_used = TRUE AND used_at < NOW() - INTERVAL '24 hours';
```

### Performance

| Operation | Latency | Notes |
|---|---|---|
| Token generation | ~2ms | RSA signing |
| Token validation | ~1ms | RSA verification + Redis lookup |
| Redis blacklist check | <1ms | Single key lookup |
| Refresh token rotation | ~5ms | DB read + write + token generation |

---

## Related Documents

- [API Keys](./API_Keys.md) — Programmatic API authentication
- [Secret Rotation](./Secret_Rotation.md) — JWT signing key rotation
- [Security Best Practices](./Security_Best_Practices.md) — Overall security posture
- [Rate Limiting](./Rate_Limiting.md) — Login rate limiting
