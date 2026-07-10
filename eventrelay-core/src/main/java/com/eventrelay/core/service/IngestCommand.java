package com.eventrelay.core.service;

/**
 * Input to {@link IngestionService#ingest}. Payload and metadata are already
 * serialized to JSON strings by the caller (the API layer owns JSON concerns),
 * keeping the core module free of any serialization dependency.
 */
public record IngestCommand(
        String eventType,
        String idempotencyKey,
        String payloadJson,
        String metadataJson
) {
}
