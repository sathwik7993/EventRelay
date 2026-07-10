package com.eventrelay.core.domain;

/**
 * Lifecycle state of a single (event × subscription) delivery.
 *
 * <pre>
 *   PENDING  → QUEUED → DELIVERED
 *                  │
 *                  ├→ RETRYING → QUEUED ...
 *                  └→ DEAD
 * </pre>
 */
public enum DeliveryState {
    /** Created, awaiting first dispatch. */
    PENDING,
    /** Published to SQS; a worker is (or will be) processing it. */
    QUEUED,
    /** Received a 2xx — done. */
    DELIVERED,
    /** Transient failure; waiting for {@code next_attempt_at}. */
    RETRYING,
    /** Permanent failure or retries exhausted; moved to the DLQ. */
    DEAD
}
