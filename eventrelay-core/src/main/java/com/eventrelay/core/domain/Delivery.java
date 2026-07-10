package com.eventrelay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "target_url", nullable = false)
    private String targetUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryState status = DeliveryState.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "leased_at")
    private OffsetDateTime leasedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Delivery() {
    }

    public Delivery(UUID eventId, UUID subscriptionId, UUID tenantId,
                    String eventType, String targetUrl) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.subscriptionId = subscriptionId;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.targetUrl = targetUrl;
        OffsetDateTime now = OffsetDateTime.now();
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public DeliveryState getStatus() {
        return status;
    }

    public void setStatus(DeliveryState status) {
        this.status = status;
        touch();
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void incrementAttempts() {
        this.attemptCount++;
        touch();
    }

    public OffsetDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
        touch();
    }

    public OffsetDateTime getLeasedAt() {
        return leasedAt;
    }

    public void setLeasedAt(OffsetDateTime leasedAt) {
        this.leasedAt = leasedAt;
        touch();
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Integer getLastHttpStatus() {
        return lastHttpStatus;
    }

    public void setLastHttpStatus(Integer lastHttpStatus) {
        this.lastHttpStatus = lastHttpStatus;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    private void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
