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
@Table(name = "delivery_attempts")
public class DeliveryAttempt {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status;

    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    @Column(name = "response_body_snippet")
    private String responseBodySnippet;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "target_url", nullable = false)
    private String targetUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected DeliveryAttempt() {
    }

    public DeliveryAttempt(UUID eventId, UUID subscriptionId, UUID tenantId,
                           int attemptNumber, DeliveryStatus status, String targetUrl) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.subscriptionId = subscriptionId;
        this.tenantId = tenantId;
        this.attemptNumber = attemptNumber;
        this.status = status;
        this.targetUrl = targetUrl;
        this.createdAt = OffsetDateTime.now();
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

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }

    public Integer getHttpStatusCode() {
        return httpStatusCode;
    }

    public String getResponseBodySnippet() {
        return responseBodySnippet;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setHttpStatusCode(Integer httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
    }

    public void setResponseBodySnippet(String responseBodySnippet) {
        this.responseBodySnippet = responseBodySnippet;
    }

    public void setDurationMs(int durationMs) {
        this.durationMs = durationMs;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
