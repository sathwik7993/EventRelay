# EventRelay — Future Enhancements

This document outlines the roadmap for future enhancements to the EventRelay platform beyond the v1.0.0 production release.

---

## 1. Feature Backlog

| Enhancement ID | Feature Name | Description | Target |
|----------------|--------------|-------------|--------|
| **FE-001** | **GraphQL Webhook Subscriptions** | Real-time subscription model using WebSockets as an alternative to HTTP POST webhooks. | v1.1.0 |
| **FE-002** | **Event Transformation Rules** | Allows tenants to define JSONPath rules to filter or modify payloads before dispatch. | v1.2.0 |
| **FE-003** | **Multi-Region Active-Active** | Deploy RDS and ECS active-active across multiple AWS regions with Global DB replication. | v2.0.0 |
| **FE-004** | **Custom Client SDKs** | Auto-generate SDKs in Python, Go, Node.js, and Java using OpenAPI Generator. | v1.1.0 |
