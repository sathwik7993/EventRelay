package com.eventrelay.api.tracing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

/**
 * Stamps the current W3C trace context onto an event's metadata at ingest.
 *
 * <p>Delivery happens minutes (or hours, after retries) later in a different
 * process, so the trace cannot be carried in-memory. Persisting {@code traceparent}
 * with the event lets the dispatcher resume the same trace, giving one end-to-end
 * view from "event accepted" through "webhook delivered".
 */
@Component
public class TraceMetadataWriter {

    private final Tracer tracer;
    private final Propagator propagator;

    public TraceMetadataWriter(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void writeInto(ObjectNode metadata) {
        try {
            TraceContext context = tracer.currentTraceContext().context();
            if (context == null) {
                return;
            }
            propagator.inject(context, metadata, ObjectNode::put);
        } catch (Exception ignored) {
            // Tracing must never break ingestion.
        }
    }
}
