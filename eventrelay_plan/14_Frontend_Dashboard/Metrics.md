# EventRelay — Metrics Dashboard UI

This document details the layout, chart types, and data-fetching patterns for the visual Metrics page in the EventRelay tenant dashboard.

---

## 1. UI Layout: Metrics Visualizer

The Metrics page utilizes Chart.js or Recharts to visualize delivery health:

- **Delivery Success Line Chart (24h/7d/30d)**: Tracks the percentage of successful deliveries over time. Displays a red threshold indicator line at $99.95\%$.
- **Latency Percentile Distribution (Bar Chart)**: Displays columns for p50, p90, p95, and p99 response times.
- **Error Code Distribution (Pie Chart)**: Breaks down HTTP errors (e.g., $65\%\text{ 5xx Gateway Errors}$, $20\%\text{ Connection Timeouts}$, $15\%\text{ 4xx Errors}$).

---

## 2. API Integration & Query Structure

Data is loaded on-demand when the user opens the metrics tab:
- **Endpoint**: `GET /api/v1/tenants/{id}/metrics`
- **Query Parameters**:
  - `range`: `24h`, `7d`, or `30d`.
  - `metric_types`: `success_rate,latency,errors`.
- **Response Caching**: To prevent heavy analytical queries from degrading PostgreSQL production performance, dashboard metric calculations are cached in Redis (`rate:dashboard:metrics:{tenant_id}`) with a 1-minute TTL.
