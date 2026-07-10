package com.eventrelay.dispatcher.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.util.Map;

/**
 * Builds the SQS client and ensures the delivery queue exists.
 *
 * <p>When {@code eventrelay.sqs.endpoint} is set (LocalStack for local dev), the
 * client is pointed at it with static test credentials. Left unset, the default
 * AWS credential/region resolution applies — so the same build runs against real
 * AWS SQS in deployment.
 */
@Configuration
public class SqsConfig {

    private static final Logger log = LoggerFactory.getLogger(SqsConfig.class);

    @Bean
    public SqsClient sqsClient(
            @Value("${eventrelay.sqs.region:us-east-1}") String region,
            @Value("${eventrelay.sqs.endpoint:}") String endpoint) {
        var builder = SqsClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
            log.info("SQS client using endpoint override {}", endpoint);
        }
        return builder.build();
    }

    /** Resolves (creating if needed) the delivery queue URL — a bean so it's shared. */
    @Bean
    public DeliveryQueue deliveryQueue(
            SqsClient sqs,
            @Value("${eventrelay.sqs.queue-name:eventrelay-deliveries}") String queueName,
            @Value("${eventrelay.sqs.visibility-timeout-seconds:60}") int visibilityTimeout,
            @Value("${eventrelay.sqs.wait-time-seconds:10}") int waitTimeSeconds) {
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.of(
                        QueueAttributeName.VISIBILITY_TIMEOUT, Integer.toString(visibilityTimeout)))
                .build()).queueUrl();
        log.info("Delivery queue ready: {}", queueUrl);
        return new DeliveryQueue(sqs, queueUrl, visibilityTimeout, waitTimeSeconds);
    }
}
