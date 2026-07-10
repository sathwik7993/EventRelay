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
@Table(name = "events")
public class Event {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Event() {
    }

    public Event(UUID id, UUID tenantId, String eventType, String idempotencyKey,
                 String payload, String metadata) {
        this.id = id;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.idempotencyKey = idempotencyKey;
        this.payload = payload;
        this.metadata = metadata != null ? metadata : "{}";
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPayload() {
        return payload;
    }

    public String getMetadata() {
        return metadata;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
