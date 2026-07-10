package com.eventrelay.core.service;

import com.eventrelay.core.domain.Event;

/**
 * Outcome of an ingestion call.
 *
 * @param event     the persisted (or pre-existing) event
 * @param duplicate true if this event was already ingested under the same
 *                  idempotency key, in which case no new event was written
 */
public record IngestResult(Event event, boolean duplicate) {
}
