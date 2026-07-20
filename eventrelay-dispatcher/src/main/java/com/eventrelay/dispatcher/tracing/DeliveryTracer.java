package com.eventrelay.dispatcher.tracing;

import com.eventrelay.core.domain.Delivery;
import com.eventrelay.core.domain.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Resumes the trace that started when the event was ingested.
 *
 * <p>The {@code traceparent} written into the event's metadata by the API is
 * extracted here and used as the parent of the delivery span, so a single trace
 * spans ingest → outbox → SQS → HTTP delivery even though they happen in
 * different processes at different times.
 */
@Component
public class DeliveryTracer {

    private static final String SPAN_NAME = "webhook.delivery";

    private final Tracer tracer;
    private final Propagator propagator;
    private final ObjectMapper objectMapper;

    public DeliveryTracer(Tracer tracer, Propagator propagator, ObjectMapper objectMapper) {
        this.tracer = tracer;
        this.propagator = propagator;
        this.objectMapper = objectMapper;
    }

    /** Starts a delivery span, parented to the ingest trace when available. */
    public Span startSpan(Event event, Delivery delivery, int attemptNumber) {
        try {
            JsonNode metadata = objectMapper.readTree(event.getMetadata());
            Span span = propagator
                    .extract(metadata, (carrier, key) ->
                            carrier.hasNonNull(key) ? carrier.get(key).asText() : null)
                    .name(SPAN_NAME)
                    .start();
            span.tag("event.id", event.getId().toString());
            span.tag("event.type", event.getEventType());
            span.tag("delivery.id", delivery.getId().toString());
            span.tag("delivery.attempt", Integer.toString(attemptNumber));
            span.tag("subscription.id", delivery.getSubscriptionId().toString());
            return span;
        } catch (Exception e) {
            // Never let tracing break delivery — fall back to an unparented span.
            return tracer.nextSpan().name(SPAN_NAME).start();
        }
    }

    public Tracer.SpanInScope withSpan(Span span) {
        return tracer.withSpan(span);
    }

    /** Current trace context as W3C headers, for propagating to the subscriber. */
    public Map<String, String> outboundHeaders() {
        Map<String, String> headers = new java.util.HashMap<>();
        try {
            TraceContext context = tracer.currentTraceContext().context();
            if (context != null) {
                propagator.inject(context, headers, Map::put);
            }
        } catch (Exception ignored) {
            // best effort
        }
        return headers;
    }
}
