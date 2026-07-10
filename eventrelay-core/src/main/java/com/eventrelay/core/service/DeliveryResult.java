package com.eventrelay.core.service;

/** What happened to a delivery after a failed attempt. */
public enum DeliveryResult {
    /** Rescheduled for another attempt after backoff. */
    RETRY_SCHEDULED,
    /** Permanent failure or retries exhausted — moved to the DLQ. */
    DEAD_LETTERED
}
