package com.eventrelay.dispatcher.worker;

import com.eventrelay.dispatcher.queue.DeliveryQueue;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Long-polls SQS and dispatches each message to {@link DeliveryProcessor}. A
 * message is deleted only after its delivery is successfully processed; if
 * processing throws, the message is left to reappear after the visibility
 * timeout (at-least-once). Runs {@code eventrelay.worker.concurrency} threads.
 */
@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryQueue queue;
    private final DeliveryProcessor processor;
    private final int concurrency;

    private volatile boolean running = true;
    private ExecutorService pool;

    public DeliveryWorker(DeliveryQueue queue, DeliveryProcessor processor,
                          @Value("${eventrelay.worker.concurrency:2}") int concurrency) {
        this.queue = queue;
        this.processor = processor;
        this.concurrency = Math.max(1, concurrency);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        pool = Executors.newFixedThreadPool(concurrency);
        for (int i = 0; i < concurrency; i++) {
            pool.submit(this::pollLoop);
        }
        log.info("Delivery worker started with {} threads", concurrency);
    }

    private void pollLoop() {
        while (running) {
            try {
                List<Message> messages = queue.receive();
                for (Message message : messages) {
                    handle(message);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("Poll loop error; backing off briefly", e);
                    sleep();
                }
            }
        }
    }

    private void handle(Message message) {
        try {
            processor.process(message.body());
            queue.delete(message.receiptHandle());
        } catch (Exception e) {
            // Leave the message on the queue; it reappears after the visibility timeout.
            log.error("Failed to process delivery {}; will be redelivered", message.body(), e);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Delivery worker stopped");
    }
}
