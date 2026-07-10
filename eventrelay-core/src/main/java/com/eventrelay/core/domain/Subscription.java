package com.eventrelay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "target_url", nullable = false)
    private String targetUrl;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "event_types", nullable = false, columnDefinition = "text[]")
    private String[] eventTypes = {};

    @Column(name = "signing_secret", nullable = false)
    private String signingSecret;

    @Column
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String config = "{}";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(name = "failure_count", nullable = false)
    private int failureCount = 0;

    @Column(name = "last_failure_at")
    private OffsetDateTime lastFailureAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected Subscription() {
    }

    public Subscription(UUID id, UUID tenantId, String targetUrl, String[] eventTypes, String signingSecret) {
        this.id = id;
        this.tenantId = tenantId;
        this.targetUrl = targetUrl;
        this.eventTypes = eventTypes != null ? eventTypes : new String[]{};
        this.signingSecret = signingSecret;
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * True if this subscription should receive the given event type.
     * An empty {@code eventTypes} array is a catch-all (subscribe to everything).
     */
    public boolean matches(String eventType) {
        if (eventTypes == null || eventTypes.length == 0) {
            return true;
        }
        for (String pattern : eventTypes) {
            if (pattern.equals(eventType)) {
                return true;
            }
            // simple trailing wildcard support: "order.*"
            if (pattern.endsWith(".*")
                    && eventType.startsWith(pattern.substring(0, pattern.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public String[] getEventTypes() {
        return eventTypes;
    }

    public void setEventTypes(String[] eventTypes) {
        this.eventTypes = eventTypes;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public void setStatus(SubscriptionStatus status) {
        this.status = status;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public void setLastFailureAt(OffsetDateTime lastFailureAt) {
        this.lastFailureAt = lastFailureAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
