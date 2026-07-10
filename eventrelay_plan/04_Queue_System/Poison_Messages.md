# EventRelay — Poison Message Handling

This document details the strategies and configurations used in EventRelay to identify, isolate, and debug "poison messages" — queue items that fail processing repeatedly due to payload corruption, software bugs, or resource exhaustion.

---

## 1. What is a Poison Message?

In a queue-based system, a poison message is one that is successfully pulled from the queue but causes the consumer node to crash or fail processing. The message returns to the queue, is pulled again by another worker, and causes another failure. This loop consumes resources and blocks valid messages.

EventRelay isolates poison messages through a multi-tier detection architecture:

```
[ SQS Queue ] ──► [ Worker Pull ] ──► [ Deserialization / Processing ]
                          ▲                         │
                          │                         ▼ (Exception Caught / Crash)
                   [ Visible Again ] ◄────── [ Retry / Fail Increment ]
                          │
                          ▼ (If Receive Count > Max Receive Count)
                  [ SQS Redrive Policy ] ──► [ Poison Isolation (DLQ) ]
```

---

## 2. Detection (Receive Count Threshold)

EventRelay leverages SQS metadata to detect looping failures.

- **`ApproximateReceiveCount`**: Every SQS message maintains this attribute, incremented by AWS whenever a consumer pulls it.
- **Max Receive Count**: The main SQS queue is configured with a Redrive Policy set to a `maxReceiveCount` of **3 attempts**.
- **Isolation Action**: If a message fails processing 3 times, SQS intercepts the next pull request and automatically moves the message to the Dead-Letter Queue (`eventrelay-dlq`), preventing further worker crashes.

---

## 3. Worker Ingestion Safeguards (Application-Level)

To prevent poison messages from causing JVM Out-Of-Memory (OOM) errors or application crashes, the worker pipeline integrates defensive checks:

1. **Size Enforcement**: The SQS Listener checks the message size before parsing. Any payload exceeding `256 KB` is rejected immediately.
2. **Safe Deserialization**: The worker wraps the deserialization block in a `try-catch` block:
   ```java
   try {
       EventPayload event = objectMapper.readValue(message.body(), EventPayload.class);
       processEvent(event);
   } catch (JsonProcessingException e) {
       log.error("Poison Message Detected: JSON Deserialization Failure. ID: {}", message.messageId(), e);
       // Move to DLQ immediately, bypassing retry loop
       sendToDlqDirectly(message, "JSON_DESERIALIZATION_FAILURE");
       sqsClient.deleteMessage(builder -> builder.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
   }
   ```
3. **SSRF Blocking**: Before initiating an HTTP call, the worker resolves the target URL domain. If it resolves to local/private ranges, the worker throws a non-retryable exception and isolates the message.

---

## 4. Operational Playbook: Poison Message Inspection

When a poison message enters the DLQ:

1. **Trigger Alert**: A CloudWatch Alarm fires indicating DLQ depth is $>0$.
2. **Access Dashboard**: Open the DLQ Manager UI in the EventRelay dashboard.
3. **Inspect Logs**: Analyze the failure traceback captured in `dead_letter_events.last_error_message`.
4. **Action Choice**:
   - **Discard**: If the message is completely garbage (e.g., malformed test payload), click "Discard" to delete the database entry and purge it from SQS.
   - **Fix & Replay**: If the message failed due to a worker bug, deploy a code fix first, then trigger "Replay" to process the event correctly.
