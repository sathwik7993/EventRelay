package com.eventrelay.core.domain;

/** State machine for an outbox row: PENDING -> PROCESSING -> PROCESSED | FAILED. */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED
}
