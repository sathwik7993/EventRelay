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
@Table(name = "dead_letter_events")
public class DeadLetterEvent {

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "failure_reason", nullable = false)
    private String failureReason;

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "total_attempts", nullable = false)
    private int totalAttempts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeadLetterStatus status = DeadLetterStatus.PENDING;

    @Column(name = "failed_at", nullable = false)
    private OffsetDateTime failedAt;

    @Column(name = "replayed_at")
    private OffsetDateTime replayedAt;

    @Column(name = "discarded_at")
    private OffsetDateTime discardedAt;

    @Column(name = "replayed_by")
    private String replayedBy;

    protected DeadLetterEvent() {
    }

    public DeadLetterEvent(UUID eventId, UUID subscriptionId, UUID tenantId, String eventType,
                           String payload, String failureReason, Integer lastHttpStatus,
                           int totalAttempts) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.subscriptionId = subscriptionId;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.payload = payload;
        this.failureReason = failureReason;
        this.lastHttpStatus = lastHttpStatus;
        this.totalAttempts = totalAttempts;
        this.failedAt = OffsetDateTime.now();
    }

    public void markReplayed(String by) {
        this.status = DeadLetterStatus.REPLAYED;
        this.replayedAt = OffsetDateTime.now();
        this.replayedBy = by;
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

    public String getPayload() {
        return payload;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Integer getLastHttpStatus() {
        return lastHttpStatus;
    }

    public int getTotalAttempts() {
        return totalAttempts;
    }

    public DeadLetterStatus getStatus() {
        return status;
    }

    public OffsetDateTime getFailedAt() {
        return failedAt;
    }
}
