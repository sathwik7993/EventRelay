package com.eventrelay.dispatcher.worker;

import com.eventrelay.common.crypto.HmacSigner;
import com.eventrelay.core.domain.Delivery;
import com.eventrelay.core.domain.DeliveryAttempt;
import com.eventrelay.core.domain.DeliveryStatus;
import com.eventrelay.core.domain.Event;
import com.eventrelay.core.domain.Subscription;
import com.eventrelay.core.repository.DeliveryAttemptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;

/**
 * Signs and POSTs one delivery to a subscriber, records the attempt in the audit
 * log, and classifies the result into a {@link DeliveryOutcome} the caller uses
 * to drive the delivery state machine.
 *
 * <p>Every request carries an HMAC-SHA256 signature over {@code timestamp + "." +
 * body} so receivers can verify authenticity, integrity, and freshness.
 * Redirects are never followed (SSRF guard).
 */
@Component
public class HttpDeliveryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDeliveryClient.class);
    private static final int SNIPPET_LIMIT = 1024;

    private final DeliveryAttemptRepository attempts;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public HttpDeliveryClient(DeliveryAttemptRepository attempts, ObjectMapper objectMapper,
                              @Value("${eventrelay.delivery.http-timeout-ms:30000}") long timeoutMs) {
        this.attempts = attempts;
        this.objectMapper = objectMapper;
        this.requestTimeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public DeliveryOutcome deliver(Delivery delivery, Subscription subscription,
                                   Event event, int attemptNumber) {
        String body = buildEnvelope(event);
        long timestamp = Instant.now().getEpochSecond();
        String signature = HmacSigner.sign(subscription.getSigningSecret(), timestamp, body);

        DeliveryAttempt attempt = new DeliveryAttempt(event.getId(), subscription.getId(),
                delivery.getTenantId(), attemptNumber, DeliveryStatus.FAILED, delivery.getTargetUrl());

        long start = System.nanoTime();
        DeliveryOutcome outcome;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(delivery.getTargetUrl()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "EventRelay/1.0")
                    .header("X-Event-ID", event.getId().toString())
                    .header("X-Event-Type", event.getEventType())
                    .header("X-EventRelay-Attempt", Integer.toString(attemptNumber))
                    .header("X-EventRelay-Timestamp", Long.toString(timestamp))
                    .header("X-EventRelay-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            attempt.setHttpStatusCode(code);
            attempt.setResponseBodySnippet(snippet(response.body()));
            outcome = classify(code);
            attempt.setStatus(outcome.success() ? DeliveryStatus.SUCCESS : DeliveryStatus.FAILED);
            if (!outcome.success()) {
                attempt.setErrorMessage(outcome.error());
            }
        } catch (HttpTimeoutException e) {
            outcome = DeliveryOutcome.transient_(null, "Timeout after " + requestTimeout.toMillis() + "ms");
            attempt.setStatus(DeliveryStatus.TIMEOUT);
            attempt.setErrorMessage(outcome.error());
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            outcome = DeliveryOutcome.transient_(null, msg);
            attempt.setStatus(DeliveryStatus.FAILED);
            attempt.setErrorMessage(msg);
        } finally {
            attempt.setDurationMs((int) Math.max(0, (System.nanoTime() - start) / 1_000_000));
            attempts.save(attempt);
        }

        log.debug("Delivery {} attempt {} -> success={} permanent={} status={}",
                delivery.getId(), attemptNumber, outcome.success(), outcome.permanent(), outcome.httpStatus());
        return outcome;
    }

    /** Maps an HTTP status to a retry decision (see FR-4 response-handling matrix). */
    private DeliveryOutcome classify(int code) {
        if (code >= 200 && code < 300) {
            return DeliveryOutcome.ok(code);
        }
        if (code == 408 || code == 429) {
            return DeliveryOutcome.transient_(code, "Retryable HTTP " + code);
        }
        if (code >= 400 && code < 500) {
            return DeliveryOutcome.permanent(code, "Permanent HTTP " + code);
        }
        if (code >= 300 && code < 400) {
            return DeliveryOutcome.permanent(code, "Redirect not followed (HTTP " + code + ")");
        }
        return DeliveryOutcome.transient_(code, "Server error HTTP " + code);
    }

    private String buildEnvelope(Event event) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("event_id", event.getId().toString());
            root.put("event_type", event.getEventType());
            root.put("created_at", event.getCreatedAt().toString());
            root.set("data", objectMapper.readTree(event.getPayload()));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("Failed to build envelope for event {}", event.getId(), e);
            return "{\"event_id\":\"" + event.getId() + "\"}";
        }
    }

    private String snippet(String body) {
        if (body == null) {
            return null;
        }
        return body.length() <= SNIPPET_LIMIT ? body : body.substring(0, SNIPPET_LIMIT);
    }
}
