package com.eventrelay.dispatcher.queue;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.List;

/**
 * Thin wrapper over the SQS delivery queue. The message body is simply the
 * delivery id; all state lives in Postgres, so the queue is pure work
 * distribution.
 */
public class DeliveryQueue {

    private final SqsClient sqs;
    private final String queueUrl;
    private final int visibilityTimeout;
    private final int waitTimeSeconds;

    public DeliveryQueue(SqsClient sqs, String queueUrl, int visibilityTimeout, int waitTimeSeconds) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.visibilityTimeout = visibilityTimeout;
        this.waitTimeSeconds = waitTimeSeconds;
    }

    public void publish(String deliveryId) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(deliveryId)
                .build());
    }

    /** Long-polls for up to 10 messages. */
    public List<Message> receive() {
        return sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(waitTimeSeconds)
                .visibilityTimeout(visibilityTimeout)
                .build()).messages();
    }

    public void delete(String receiptHandle) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build());
    }
}
