package com.eventrelay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false)
    private String keyPrefix;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] scopes = {"events:write"};

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected ApiKey() {
    }

    public ApiKey(UUID id, UUID tenantId, String keyHash, String keyPrefix, String name) {
        this.id = id;
        this.tenantId = tenantId;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.name = name;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getName() {
        return name;
    }

    public String[] getScopes() {
        return scopes;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public boolean isActive() {
        OffsetDateTime now = OffsetDateTime.now();
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
