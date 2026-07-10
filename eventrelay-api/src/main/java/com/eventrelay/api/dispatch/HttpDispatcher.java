package com.eventrelay.api.dispatch;

import com.eventrelay.core.domain.DeliveryAttempt;
import com.eventrelay.core.domain.DeliveryStatus;
import com.eventrelay.core.domain.Subscription;
import com.eventrelay.core.repository.DeliveryAttemptRepository;
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
import java.util.UUID;

/**
 * Performs a single HTTP POST delivery of an event envelope to a subscriber and
 * records the outcome as a {@link DeliveryAttempt}.
 *
 * <p>Milestone 1 makes exactly one attempt per event with no retry — retries,
 * backoff, and the DLQ arrive in Milestone 2. Redirects are never followed
 * (an SSRF guard), and a hard timeout bounds each attempt.
 */
@Component
public class HttpDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HttpDispatcher.class);
    private static final int SNIPPET_LIMIT = 1024;

    private final DeliveryAttemptRepository deliveryAttempts;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public HttpDispatcher(DeliveryAttemptRepository deliveryAttempts,
                          @Value("${eventrelay.dispatch.http-timeout-ms:30000}") long timeoutMs) {
        this.deliveryAttempts = deliveryAttempts;
        this.requestTimeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public DeliveryStatus deliver(Subscription subscription, UUID eventId, String eventType,
                                  String envelopeJson, int attemptNumber) {
        DeliveryAttempt attempt = new DeliveryAttempt(eventId, subscription.getId(),
                subscription.getTenantId(), attemptNumber, DeliveryStatus.FAILED,
                subscription.getTargetUrl());

        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(subscription.getTargetUrl()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "EventRelay/1.0")
                    .header("X-Event-ID", eventId.toString())
                    .header("X-Event-Type", eventType)
                    .header("X-EventRelay-Attempt", Integer.toString(attemptNumber))
                    .POST(HttpRequest.BodyPublishers.ofString(envelopeJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            attempt.setHttpStatusCode(code);
            attempt.setResponseBodySnippet(snippet(response.body()));
            boolean success = code >= 200 && code < 300;
            attempt.setStatus(success ? DeliveryStatus.SUCCESS : DeliveryStatus.FAILED);
            if (!success) {
                attempt.setErrorMessage("Non-2xx response: " + code);
            }
        } catch (HttpTimeoutException e) {
            attempt.setStatus(DeliveryStatus.TIMEOUT);
            attempt.setErrorMessage("Request timed out after " + requestTimeout.toMillis() + "ms");
        } catch (Exception e) {
            attempt.setStatus(DeliveryStatus.FAILED);
            attempt.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            attempt.setDurationMs((int) Math.max(0, (System.nanoTime() - start) / 1_000_000));
            deliveryAttempts.save(attempt);
        }

        log.debug("Delivery event={} sub={} status={}", eventId, subscription.getId(), attempt.getStatus());
        return attempt.getStatus();
    }

    private String snippet(String body) {
        if (body == null) {
            return null;
        }
        return body.length() <= SNIPPET_LIMIT ? body : body.substring(0, SNIPPET_LIMIT);
    }
}
