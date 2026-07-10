package com.eventrelay.core.integration;

import com.eventrelay.core.CoreTestApplication;
import com.eventrelay.core.domain.DeadLetterEvent;
import com.eventrelay.core.domain.Delivery;
import com.eventrelay.core.domain.DeliveryState;
import com.eventrelay.core.domain.Event;
import com.eventrelay.core.domain.Subscription;
import com.eventrelay.core.domain.Tenant;
import com.eventrelay.core.repository.DeadLetterEventRepository;
import com.eventrelay.core.repository.DeliveryRepository;
import com.eventrelay.core.repository.EventRepository;
import com.eventrelay.core.repository.OutboxRepository;
import com.eventrelay.core.repository.SubscriptionRepository;
import com.eventrelay.core.repository.TenantRepository;
import com.eventrelay.core.service.DeliveryResult;
import com.eventrelay.core.service.DeliveryService;
import com.eventrelay.core.service.IngestCommand;
import com.eventrelay.core.service.IngestResult;
import com.eventrelay.core.service.IngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the core pipeline against a real PostgreSQL (Testcontainers):
 * transactional outbox atomicity, idempotency, delivery fan-out, retry→DLQ, and
 * the circuit breaker.
 */
@SpringBootTest(classes = CoreTestApplication.class)
@ContextConfiguration
@Testcontainers(disabledWithoutDocker = true)
class DeliveryPipelineIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired IngestionService ingestion;
    @Autowired DeliveryService deliveryService;
    @Autowired TenantRepository tenants;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired EventRepository events;
    @Autowired OutboxRepository outbox;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeadLetterEventRepository deadLetters;

    @Test
    void ingestWritesEventAndOutboxAtomically_andIsIdempotent() {
        Tenant tenant = newTenant();
        IngestCommand cmd = new IngestCommand("order.created", "idem-1", "{\"a\":1}", "{}");

        IngestResult first = ingestion.ingest(tenant.getId(), cmd);
        assertThat(first.duplicate()).isFalse();

        // Event and its outbox row are both written (transactional outbox).
        assertThat(events.findById(first.event().getId())).isPresent();
        assertThat(outbox.lockPendingBatch(10))
                .anyMatch(o -> o.getAggregateId().equals(first.event().getId()));

        // Same idempotency key -> no new event, marked duplicate.
        IngestResult second = ingestion.ingest(tenant.getId(), cmd);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.event().getId()).isEqualTo(first.event().getId());
    }

    @Test
    void deliveryRetriesThenDeadLettersAfterMaxAttempts() {
        Tenant tenant = newTenant();
        Subscription sub = newSubscription(tenant);
        Event event = ingestion.ingest(tenant.getId(),
                new IngestCommand("order.created", null, "{\"x\":1}", "{}")).event();

        Delivery delivery = deliveryService.createDeliveries(event, List.of(sub)).get(0);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryState.PENDING);

        // max-attempts=3 in test config: two retries, then dead-letter on the third.
        DeliveryResult r1 = fail(delivery, sub);
        assertThat(r1).isEqualTo(DeliveryResult.RETRY_SCHEDULED);
        DeliveryResult r2 = fail(reload(delivery), sub);
        assertThat(r2).isEqualTo(DeliveryResult.RETRY_SCHEDULED);
        DeliveryResult r3 = fail(reload(delivery), sub);
        assertThat(r3).isEqualTo(DeliveryResult.DEAD_LETTERED);

        assertThat(reload(delivery).getStatus()).isEqualTo(DeliveryState.DEAD);
        List<DeadLetterEvent> dlq = deadLetters.findByTenantIdOrderByFailedAtDesc(
                tenant.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertThat(dlq).hasSize(1);
        assertThat(dlq.get(0).getEventId()).isEqualTo(event.getId());
    }

    @Test
    void circuitOpensAfterThresholdAndClosesOnSuccess() {
        Tenant tenant = newTenant();
        Subscription sub = newSubscription(tenant);
        Event event = ingestion.ingest(tenant.getId(),
                new IngestCommand("order.created", null, "{\"x\":1}", "{}")).event();
        Delivery delivery = deliveryService.createDeliveries(event, List.of(sub)).get(0);

        // failure-threshold=3: three consecutive failures open the circuit.
        assertThat(deliveryService.isCircuitOpen(reload(sub))).isFalse();
        fail(delivery, sub);
        fail(reload(delivery), reload(sub));
        fail(reload(delivery), reload(sub));
        assertThat(deliveryService.isCircuitOpen(reload(sub))).isTrue();

        // A success closes the circuit and resets the failure count.
        Delivery good = deliveryService.createDeliveries(event, List.of(sub)).get(0);
        deliveryService.recordSuccess(good, reload(sub), 200);
        assertThat(deliveryService.isCircuitOpen(reload(sub))).isFalse();
    }

    private DeliveryResult fail(Delivery delivery, Subscription sub) {
        return deliveryService.recordFailure(delivery, sub, false, 500, "boom", "{\"x\":1}");
    }

    private Delivery reload(Delivery d) {
        return deliveries.findById(d.getId()).orElseThrow();
    }

    private Subscription reload(Subscription s) {
        return subscriptions.findById(s.getId()).orElseThrow();
    }

    private Tenant newTenant() {
        return tenants.save(new Tenant(UUID.randomUUID(), "T", "t-" + UUID.randomUUID()));
    }

    private Subscription newSubscription(Tenant tenant) {
        return subscriptions.save(new Subscription(UUID.randomUUID(), tenant.getId(),
                "https://example.test/webhook", new String[]{"order.created"}, "whsec_test"));
    }
}
