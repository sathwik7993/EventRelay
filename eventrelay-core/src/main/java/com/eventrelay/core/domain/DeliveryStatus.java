package com.eventrelay.core.domain;

/** Outcome of a single HTTP delivery attempt. */
public enum DeliveryStatus {
    SUCCESS,
    FAILED,
    TIMEOUT,
    SKIPPED
}
