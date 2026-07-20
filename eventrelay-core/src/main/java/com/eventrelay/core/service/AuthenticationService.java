package com.eventrelay.core.service;

import com.eventrelay.common.crypto.ApiKeys;
import com.eventrelay.core.domain.ApiKey;
import com.eventrelay.core.domain.Tenant;
import com.eventrelay.core.repository.ApiKeyRepository;
import com.eventrelay.core.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a raw API key presented on a request to its owning tenant.
 *
 * <p>Lookup is by non-secret prefix, then the presented key is verified against
 * the stored bcrypt hash.
 *
 * <p><b>Why a cache:</b> bcrypt is deliberately slow (cost factor 12 ≈ 300ms of
 * CPU). That is correct for passwords, but running it on every API call caps
 * ingest throughput at roughly one request per core per 300ms — load testing
 * measured ~21 req/s. Successful verifications are therefore cached for a short
 * TTL, keyed by a SHA-256 digest of the presented key (never the key itself).
 * bcrypt still guards the credential at rest; we just stop paying for it on every
 * request. The tradeoff is that a revoked key stays usable for at most one TTL.
 */
@Service
public class AuthenticationService {

    private final ApiKeyRepository apiKeys;
    private final TenantRepository tenants;
    private final long ttlMillis;
    private final int maxEntries;

    private final Map<String, CachedTenant> verified = new ConcurrentHashMap<>();

    public AuthenticationService(ApiKeyRepository apiKeys, TenantRepository tenants,
                                 @Value("${eventrelay.auth.cache-ttl-seconds:60}") long ttlSeconds,
                                 @Value("${eventrelay.auth.cache-max-entries:10000}") int maxEntries) {
        this.apiKeys = apiKeys;
        this.tenants = tenants;
        this.ttlMillis = ttlSeconds * 1000L;
        this.maxEntries = maxEntries;
    }

    private record CachedTenant(Tenant tenant, long expiresAt) {
        boolean isFresh(long now) {
            return now < expiresAt;
        }
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }

        String cacheKey = digest(rawKey);
        long now = System.currentTimeMillis();

        CachedTenant cached = verified.get(cacheKey);
        if (cached != null) {
            if (cached.isFresh(now)) {
                return Optional.of(cached.tenant());
            }
            verified.remove(cacheKey, cached);
        }

        Optional<Tenant> tenant = verifyAgainstDatabase(rawKey);
        tenant.ifPresent(t -> cache(cacheKey, t, now));
        return tenant;
    }

    private Optional<Tenant> verifyAgainstDatabase(String rawKey) {
        Optional<ApiKey> candidate =
                apiKeys.findByKeyPrefixAndRevokedAtIsNull(ApiKeys.prefixOf(rawKey));
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        ApiKey key = candidate.get();
        if (!key.isActive() || !ApiKeys.matches(rawKey, key.getKeyHash())) {
            return Optional.empty();
        }
        return tenants.findByIdAndDeletedAtIsNull(key.getTenantId());
    }

    private void cache(String cacheKey, Tenant tenant, long now) {
        if (verified.size() >= maxEntries) {
            // Cheap bound: drop everything already expired, then give up if still full.
            verified.entrySet().removeIf(e -> !e.getValue().isFresh(now));
            if (verified.size() >= maxEntries) {
                return;
            }
        }
        verified.put(cacheKey, new CachedTenant(tenant, now + ttlMillis));
    }

    /** Invalidates all cached verifications (call after revoking or rotating keys). */
    public void invalidateCache() {
        verified.clear();
    }

    private String digest(String rawKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder()
                    .encodeToString(sha256.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
