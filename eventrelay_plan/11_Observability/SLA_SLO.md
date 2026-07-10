# EventRelay — SLA and SLO Definitions

This document details the Service Level Agreements (SLAs), Service Level Objectives (SLOs), and Service Level Indicators (SLIs) defined for the EventRelay platform.

---

## 1. Definitions and Target Objectives

EventRelay establishes three core SLIs to measure platform health:

| Metric Category | SLI Definition (Calculation) | SLO Target | SLA Threshold |
|-----------------|------------------------------|------------|---------------|
| **Ingestion Availability** | $\frac{\text{Successful Ingest requests (202)}}{\text{Total Ingest requests}} \times 100$ | $\ge 99.9\%$ | $\ge 99.0\%$ |
| **Delivery Durability** | $\frac{\text{Delivered events} + \text{Dead-lettered events}}{\text{Total Ingested events}} \times 100$ | $100\%$ (Zero loss)| $\ge 99.99\%$ |
| **Delivery Latency** | Time elapsed between Ingestion Commit and first HTTP delivery attempt. | $99\%$ of events $< 1.0\text{s}$ | $95\%$ of events $< 5.0\text{s}$ |

---

## 2. Ingestion Availability SLO

- **Measurement Window**: 30-day rolling window.
- **Exclusions**: Scheduled maintenance windows and tenant-induced client rate limits (`429 Too Many Requests`) are excluded from availability calculations.
- **Failures**: Any internal server error (`500`) or database connection timeout (`503`) counts against the availability budget.

---

## 3. Error Budget and Burn Rate Alerting

The Error Budget is the maximum allowed failure rate over a billing cycle.
- For a $99.9\%$ availability SLO, the allowed failure budget is $0.1\%$.
- **Burn Rate**: The rate at which the error budget is consumed. EventRelay configures alerts based on burn rate:
  - **3% Burn Alert**: If $3\%$ of the error budget is consumed in a 1-hour window, Alertmanager sends a Warning ticket to Slack.
  - **14.4x Burn Alert**: If $14.4\%$ of the budget is consumed in a 1-hour window, it triggers PagerDuty to page the on-call engineer, indicating a complete service block.
- **Action on Budget Depletion**: If the 30-day error budget is depleted, feature deployments are paused, and development resources are redirected to stability engineering until the SLO is restored.
