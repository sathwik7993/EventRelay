package com.eventrelay.dispatcher.schedule;

import com.eventrelay.core.domain.Delivery;
import com.eventrelay.core.domain.DeliveryState;
import com.eventrelay.core.repository.DeliveryRepository;
import com.eventrelay.dispatcher.queue.DeliveryQueue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Claims deliveries that are due and publishes them to SQS. The claim marks each
 * row QUEUED and stamps a lease before publishing, all inside one transaction:
 * if the publish fails the transaction rolls back and the row stays due, so a
 * delivery is never lost between the scheduler and the queue.
 */
@Service
public class DueDeliveryPublisher {

    private final DeliveryRepository deliveries;
    private final DeliveryQueue queue;

    public DueDeliveryPublisher(DeliveryRepository deliveries, DeliveryQueue queue) {
        this.deliveries = deliveries;
        this.queue = queue;
    }

    @Transactional
    public int publishDue(int limit) {
        List<Delivery> due = deliveries.claimDue(OffsetDateTime.now(), limit);
        if (due.isEmpty()) {
            return 0;
        }

        OffsetDateTime leasedAt = OffsetDateTime.now();
        List<String> ids = new ArrayList<>(due.size());
        for (Delivery delivery : due) {
            delivery.setStatus(DeliveryState.QUEUED);
            delivery.setLeasedAt(leasedAt);
            deliveries.save(delivery);
            ids.add(delivery.getId().toString());
        }
        // One batched publish instead of one call per delivery.
        queue.publishBatch(ids);
        return due.size();
    }
}
