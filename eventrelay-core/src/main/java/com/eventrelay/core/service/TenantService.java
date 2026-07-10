package com.eventrelay.core.service;

import com.eventrelay.common.crypto.ApiKeys;
import com.eventrelay.core.domain.ApiKey;
import com.eventrelay.core.domain.Tenant;
import com.eventrelay.core.repository.ApiKeyRepository;
import com.eventrelay.core.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository tenants;
    private final ApiKeyRepository apiKeys;

    public TenantService(TenantRepository tenants, ApiKeyRepository apiKeys) {
        this.tenants = tenants;
        this.apiKeys = apiKeys;
    }

    /** A newly created tenant plus its raw API key, which is shown exactly once. */
    public record Created(Tenant tenant, String rawApiKey) {
    }

    @Transactional
    public Created createTenant(String name, String slug) {
        Tenant tenant = new Tenant(UUID.randomUUID(), name, slug);
        tenants.save(tenant);

        ApiKeys.Generated key = ApiKeys.generate();
        ApiKey apiKey = new ApiKey(UUID.randomUUID(), tenant.getId(),
                key.keyHash(), key.keyPrefix(), "Default Key");
        apiKeys.save(apiKey);

        return new Created(tenant, key.rawKey());
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> findActive(UUID id) {
        return tenants.findByIdAndDeletedAtIsNull(id);
    }
}
