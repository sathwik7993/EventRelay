# EventRelay — Frontend Dashboard Layout & ASCII Wireframes

This document provides ASCII wireframes and design mockups for the core pages in the EventRelay operator dashboard.

---

## 1. Landing Overview Page (Admin Console)

```
+-------------------------------------------------------------------------+
| [EventRelay Logo]     [Overview]   [Monitor]   [DLQ]   [Settings]       |
+-------------------------------------------------------------------------+
|                                                                         |
|  GLOBAL HEALTH: [ OK - 99.98% SUCCESS ]         ACTIVE DISPATCHERS: 8   |
|                                                                         |
|  +------------------------+  +-------------------+  +----------------+  |
|  | Ingestion Throughput   |  | Delivery Latency  |  | SQS Queue Depth|  |
|  | 4,250 eps              |  | p95: 142 ms       |  | 14 messages    |  |
|  +------------------------+  +-------------------+  +----------------+  |
|                                                                         |
|  RECENT DLQ INCIDENTS (Last 5 mins)                                     |
|  - Sub #102: Stripe Webhook -> HTTP 504 Gateway Timeout                 |
|  - Sub #105: Shopify Sync   -> Connection Timeout                       |
|                                                                         |
+-------------------------------------------------------------------------+
```

---

## 2. DLQ Manager Page

```
+-------------------------------------------------------------------------+
|  [ Dead-Letter Queue Manager ]                  [ Search Subscription ] |
+-------------------------------------------------------------------------+
|                                                                         |
|  [ ] Event ID   | Target Endpoint URL           | Error Status | Time   |
|  +--------------+-------------------------------+--------------+--------+
|  | [X] a1b2c3d4 | https://api.client.com/hooks  | HTTP 504     | 2m ago |
|  | [ ] d88d8b87 | https://github.com/hooks/rec  | HTTP 401     | 1h ago |
|  | [X] f9f3c4d2 | https://shopify.com/api/sync  | Timeout      | 4h ago |
|                                                                         |
|  [ Select All ]     [ Replay Selected (2) ]     [ Discard Selected ]    |
|                                                                         |
+-------------------------------------------------------------------------+
```

---

## 3. Webhook Settings Page

```
+-------------------------------------------------------------------------+
|  [ Webhook Subscription Configuration ]                                 |
+-------------------------------------------------------------------------+
|                                                                         |
|  Subscription Name: [ Slack Integration                             ]   |
|  Target URL:        [ https://hooks.slack.com/services/T0/B0/XX       ]   |
|                                                                         |
|  Event Subscriptions:                                                   |
|  [X] payment.succeeded  [X] payment.failed  [ ] refund.processed        |
|                                                                         |
|  Tenant Rate Limit (tokens/sec):                                        |
|  10 [============|----------------------------------------] 1000 (50/s)  |
|                                                                         |
|  [ Cancel ]               [ Save Config ]         [ Test Webhook ]      |
|                                                                         |
+-------------------------------------------------------------------------+
```
