# EventRelay — Replay UI

This document details the interface layouts and wireframe design of the Event Replay dashboard pages in EventRelay.

---

## 1. UI Layout: Custom Replay Configuration

When a support engineer triggers a manual replay, they can configure override routes:

```
┌────────────────────────────────────────────────────────────────────────┐
│  [ Configure Webhook Replay ]                                          │
├────────────────────────────────────────────────────────────────────────┤
│  Event ID: a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d                        │
│                                                                        │
│  Target Webhook URL:                                                   │
│  [ https://api.client.com/webhooks/payments                          ] │
│                                                                        │
│  [X] Override Destination URL (For debugging):                          │
│  [ https://webhook.site/debug-session-102                            ] │
│                                                                        │
│  [ ] Add Custom Request Headers (Header: Value):                       │
│  [ X-Debug-Mode                        ]: [ true                     ] │
│                                                                        │
│                    [ Cancel ]       [ Execute Replay Now ]             │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Replay Audit Trail Integration

- **Execution Indicator**: When the user clicks **Execute Replay**, a toast notification appears: *"Replay request accepted. Audit ID: rep_9f3c"*.
- **Audit Detail View**: The user is redirected to the Audit Log detail page, showing a progress bar representing execution status, count of completed vs. failing deliveries, and error logs if the override target is unreachable.
