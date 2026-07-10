# EventRelay — Prometheus & Grafana Dashboards

This document outlines the visual structure, layout, and metrics panels provisioned on the EventRelay Grafana dashboards to monitor delivery pipelines.

---

## 1. Dashboard Structure: Core Panels

The Main System Dashboard is organized into three analytical levels:

### Ingestion Metrics (Top Row)
- **Ingestion Rate (p50/p99)**: Line chart tracking events received per second.
- **Idempotency Hit Rate**: Bar chart tracking requests blocked as duplicates.
- **API Key Scopes**: Table showing active client API requests by scope.

### Queue Metrics (Middle Row)
- **Visible Message Count**: Line chart showing active queue backlog.
- **Age of Oldest Message**: Gauge panel showing the queue delay.

### Delivery Metrics (Bottom Row)
- **Delivery Success Rate**: Gauge panel showing success percentage (Target: $\ge 99.95\%$).
- **Latencies (p50, p95, p99)**: Line chart tracking HTTP POST response times.
- **Error Code Breakdown**: Pie chart tracking return codes (`5xx`, `4xx`, network timeouts).

---

## 2. Mermaid Dashboard Layout Mockup

```
┌────────────────────────────────────────────────────────────────────────┐
│  [ Dashboard: EventRelay System Overview ]                             │
├────────────────────────────────────────────────────────────────────────┤
│ Ingress Rate             Queue Backlog          Delivery Success Rate  │
│ [ 1,450 events/sec ]     [ 12 Messages ]        [ 99.98% SUCCESS ]     │
├────────────────────────────────────────────────────────────────────────┤
│ Ingestion Inbound Latency (p99)  │ Delivery Response Latencies (p99)   │
│                                  │                                     │
│  [ Ingress: 12ms ]               │  [ Target URL Delivery: 340ms ]     │
├──────────────────────────────────┴─────────────────────────────────────┤
│ Queue Age of Oldest Message      │ HTTP Delivery Failures              │
│ [ 0.8 seconds ]                  │ [ 502: 4% ] [ Timeout: 2% ]         │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Provisioning Configuration (JSON/YAML)

Grafana dashboards are stored as JSON declarations inside the repository and provisioned automatically on startup:

```yaml
# grafana-datasources.yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```
Dashboard JSON documents are mapped to the directory: `/var/lib/grafana/dashboards/`. Any updates merged to the repository are auto-reloaded by Grafana's dashboard sidecar agent.
