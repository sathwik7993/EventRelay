# EventRelay — Alerting Rules & Alertmanager Configuration

This document outlines the Prometheus Alerting rules and Alertmanager routing configured in EventRelay to notify support engineers of delivery failures or system degradation.

---

## 1. Alerting Rule Thresholds

Alerts are categorized into three severity levels:

| Alarm Name | Metric Source | Condition | Duration | Severity | Notification Channel |
|------------|---------------|-----------|----------|----------|----------------------|
| **DeliverySuccessLow** | Dispatcher | Success Rate $< 95\%$ | 5 Minutes | Critical | PagerDuty |
| **DlqBacklogRising** | SQS | DLQ Depth $> 100$ | 10 Minutes| Warning | Slack |
| **QueueLatencyHigh** | SQS | Age of Oldest message $> 5\text{m}$ | 5 Minutes | Warning | Slack |
| **DatabaseConnectionPoolExhausted** | JVM | Hikari Active Connections $> 90\%$ | 3 Minutes | Critical | PagerDuty |

---

## 2. Prometheus Alert Rules Definition (YAML)

Below is the Prometheus alert configuration snippet:

```yaml
groups:
  - name: eventrelay-delivery-alerts
    rules:
      - alert: WebhookDeliverySuccessLow
        expr: (sum(rate(eventrelay_deliveries_total{status="SUCCESS"}[5m])) / sum(rate(eventrelay_deliveries_total[5m]))) * 100 < 95
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Webhook delivery success rate is low ({{ $value | printf \"%.2f\" }}%)"
          description: "Tenant delivery success rate has dropped below 95% over a 5-minute sliding window."

      - alert: QueueOldestMessageAgeHigh
        expr: eventrelay_queue_age_seconds > 300
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "SQS oldest message age is high ({{ $value }}s)"
          description: "The oldest message in the SQS delivery queue has been waiting for more than 5 minutes."
```

---

## 3. Alertmanager Routing Configuration

Alertmanager routes alerts to target systems based on severity labels:

```yaml
route:
  group_by: ['alertname', 'tenant_id']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  receiver: 'slack-warnings'
  routes:
    - match:
        severity: critical
      receiver: 'pagerduty-critical'

receivers:
  - name: 'slack-warnings'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/YOUR/WEBHOOK/URL'
        channel: '#eventrelay-alerts'
        text: "Alert: {{ .CommonAnnotations.summary }}\nDescription: {{ .CommonAnnotations.description }}"

  - name: 'pagerduty-critical'
    pagerduty_configs:
      - service_key: 'PAGERDUTY_INTEGRATION_KEY'
        severity: 'critical'
```
