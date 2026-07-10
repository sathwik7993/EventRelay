# EventRelay — Queue Monitoring & Health Metrics

This document outlines the monitoring strategies, CloudWatch alarms, Prometheus exporters, and Grafana dashboard layout configured to monitor the health of the EventRelay queue systems.

---

## 1. Key SQS Metrics to Monitor

EventRelay collects queue metrics from AWS CloudWatch every 1 minute:

| Metric | Source | Metric Type | Target Threshold | Action if Exceeded |
|--------|--------|-------------|------------------|---------------------|
| **`ApproximateNumberOfMessagesVisible`** | SQS | Gauge | $< 100$ | **Scale Out**: Spawns additional dispatcher workers. |
| **`ApproximateNumberOfMessagesNotVisible`** | SQS | Gauge | N/A | High values indicate long-running HTTP deliveries. |
| **`ApproximateAgeOfOldestMessage`** | SQS | Gauge | $< 60$ Seconds | **Warning Alarm**: Indicates dispatcher queue backup. |
| **`NumberOfMessagesSent`** | SQS | Counter | N/A | Tracks incoming event volume trends. |
| **`NumberOfMessagesDeleted`** | SQS | Counter | N/A | Tracks successful event delivery volume. |

---

## 2. Prometheus Integration (Micrometer SQS Metrics)

In addition to CloudWatch, dispatcher workers export real-time queue consumer statistics directly to Prometheus using Spring Boot Micrometer:

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: prometheus, health
  metrics:
    tags:
      application: eventrelay-dispatcher
```

### Custom Prometheus Exporters
- `eventrelay_queue_poll_duration_seconds`: Histogram measuring the latency of polling requests to SQS.
- `eventrelay_message_processing_duration_seconds`: Histogram measuring time elapsed from message dequeue to deletion/ack.
- `eventrelay_consumer_active_threads`: Gauge tracking active worker threads executing HTTP calls.

---

## 3. CloudWatch Alarm Definitions (CloudFormation)

Below is the CloudFormation template snippet defining a critical alert for queue backlogs:

```yaml
QueueAgeAlarm:
  Type: AWS::CloudWatch::Alarm
  Properties:
    AlarmName: EventRelay-Queue-Age-High
    AlarmDescription: "Alert when the oldest message in the delivery queue exceeds 5 minutes."
    MetricName: ApproximateAgeOfOldestMessage
    Namespace: AWS/SQS
    Dimensions:
      - Name: QueueName
        Value: eventrelay-delivery-queue
    Statistic: Maximum
    Period: 60
    EvaluationPeriods: 3
    Threshold: 300
    ComparisonOperator: GreaterThanThreshold
    AlarmActions:
      - !Ref AlarmNotificationTopic
```

---

## 4. Grafana Queue Dashboard Layout

The Queue Monitoring dashboard in Grafana features three main sections:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        QUEUE HEALTH SUMMARY                            │
│  [ Visible: 12 Messages ] [ Invisible: 42 ] [ Age of Oldest: 1.4s ]    │
├──────────────────────────────────────┬─────────────────────────────────┤
│          QUEUE DEPTH OVER TIME       │      MESSAGE TRANSIT RATE       │
│                                      │                                 │
│  Visible / Invisible message charts  │  Sent vs. Deleted count graphs  │
├──────────────────────────────────────┴─────────────────────────────────┤
│                         WORKER EFFICIENCY                              │
│  - Active Threads: 18 / 20                                             │
│  - Mean Dequeue-to-Ack Latency: 145 ms                                  │
│  - SQS API Success Rate: 100%                                          │
└────────────────────────────────────────────────────────────────────────┘
```
