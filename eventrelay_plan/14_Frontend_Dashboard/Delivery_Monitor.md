# EventRelay — Delivery Monitor UI

This document details the layout, wireframe design, and API integrations for the Real-Time Delivery Monitor page in the EventRelay dashboard.

---

## 1. UI Layout: Real-Time Delivery Feed

The Delivery Monitor provides operations teams with a live view of outgoing webhook attempts:

```
┌────────────────────────────────────────────────────────────────────────┐
│  [ Real-Time Webhook Delivery Monitor ]               [ Filter: ALL ]  │
├────────────────────────────────────────────────────────────────────────┤
│ Time     │ Event ID │ Tenant   │ Target URL      │ Status  │ Latency   │
├──────────┼──────────┼──────────┼─────────────────┼─────────┼───────────┤
│ 09:43:02 │ a1b2c3d4 │ Stripe   │ https://api...  │ 200 OK  │ 124 ms    │
│ 09:43:01 │ d88d8b87 │ Github   │ https://hooks.. │ 503 Err │ 5,000 ms  │
│ 09:42:58 │ f9f3c4d2 │ Shopify  │ https://shop... │ 202 OK  │ 84 ms     │
├──────────┴──────────┴──────────┴─────────────────┴─────────┴───────────┤
│ Active Webhook Deliveries: 42 events/sec           Connection Pool: 8% │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. API Integration & WebSockets

To render updates without polling overhead:
- **WebSocket Protocol**: The frontend dashboard establishes a secure WebSocket connection to `/api/v1/monitor/ws`.
- **MDC Correlation**: Every event pushed through the socket contains MDC logging keys (such as `eventId`, `tenantId`, `latencyMs`, and `statusCode`) to allow developers to jump directly from a log line to a visual attempt track.
- **Failback Polling**: If the WebSocket connection fails (e.g., behind strict enterprise firewalls), the frontend falls back to standard HTTP polling (`GET /api/v1/status`) every 5 seconds.
