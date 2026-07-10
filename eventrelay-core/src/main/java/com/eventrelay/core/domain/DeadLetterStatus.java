package com.eventrelay.core.domain;

/** Review state of a dead-lettered event. */
public enum DeadLetterStatus {
    PENDING,
    REPLAYED,
    DISCARDED
}
