package com.eventrelay.core.service;

import com.eventrelay.common.crypto.ApiKeys;
import com.eventrelay.core.domain.ApiKey;
import com.eventrelay.core.domain.Tenant;
import com.eventrelay.core.repository.ApiKeyRepository;
import com.eventrelay.core.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Resolves a raw API key presented on a request to its owning tenant.
 *
 * <p>Lookup is by non-secret prefix, then the presented key is verified against
 * the stored bcrypt hash in constant time. This avoids scanning every hash.
 */
@Service
public class AuthenticationService {

    private final ApiKeyRepository apiKeys;
    private final TenantRepository tenants;

    public AuthenticationService(ApiKeyRepository apiKeys, TenantRepository tenants) {
        this.apiKeys = apiKeys;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }

        String prefix = ApiKeys.prefixOf(rawKey);
        Optional<ApiKey> candidate = apiKeys.findByKeyPrefixAndRevokedAtIsNull(prefix);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        ApiKey key = candidate.get();
        if (!key.isActive() || !ApiKeys.matches(rawKey, key.getKeyHash())) {
            return Optional.empty();
        }

        return tenants.findByIdAndDeletedAtIsNull(key.getTenantId());
    }
}
