package com.eventrelay.dispatcher.schedule;

import com.eventrelay.core.service.DeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Periodically reclaims stuck in-flight deliveries and publishes due ones to SQS.
 * Timing is DB-driven ({@code next_attempt_at}), so backoff is unbounded by the
 * SQS delay cap.
 */
@Component
public class DeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryScheduler.class);

    private final DueDeliveryPublisher publisher;
    private final DeliveryService deliveryService;
    private final int batchSize;
    private final Duration lease;

    public DeliveryScheduler(DueDeliveryPublisher publisher, DeliveryService deliveryService,
                             @Value("${eventrelay.scheduler.batch-size:100}") int batchSize,
                             @Value("${eventrelay.scheduler.lease-seconds:120}") int leaseSeconds) {
        this.publisher = publisher;
        this.deliveryService = deliveryService;
        this.batchSize = batchSize;
        this.lease = Duration.ofSeconds(leaseSeconds);
    }

    @Scheduled(fixedDelayString = "${eventrelay.scheduler.poll-interval-ms:1000}")
    public void tick() {
        deliveryService.reclaimStale(lease, batchSize);
        int published = publisher.publishDue(batchSize);
        if (published > 0) {
            log.debug("Published {} due deliveries to SQS", published);
        }
    }
}
