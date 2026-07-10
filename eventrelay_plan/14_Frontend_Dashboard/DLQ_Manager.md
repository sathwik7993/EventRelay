# EventRelay — Dead-Letter Queue (DLQ) Manager UI

This document details the design, workflows, and interaction wireframes for the Dead-Letter Queue (DLQ) Manager interface in EventRelay.

---

## 1. UI Layout: DLQ Manager Table

The DLQ Manager allows support engineers to inspect, drop, or replay failed events:

```
┌────────────────────────────────────────────────────────────────────────┐
│  [ Dead-Letter Queue Manager ]                 [ Search Event ID/URL ] │
├────────────────────────────────────────────────────────────────────────┤
│ [ ] Event ID │ Subscription │ Last HTTP Error      │ Failed Time │ Action│
├──────────────┼──────────────┼──────────────────────┼─────────────┼───────┤
│ [ ] a1b2c3d4 │ Payments     │ 504 Gateway Timeout  │ 5 mins ago  │[Repl] │
│ [ ] d88d8b87 │ Auth Triggers│ 401 Unauthorized     │ 1 hour ago  │[Repl] │
│ [ ] f9f3c4d2 │ Order Sync   │ Connection Timeout   │ 4 hours ago │[Repl] │
├──────────────┴──────────────┴──────────────────────┴─────────────┴───────┤
│ [ Select All ]   [ Batch Replay Selected ]   [ Batch Discard Selected ]│
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Interaction Flows

### Bulk Action Replays
1. The operator selects multiple checkboxes next to failed events.
2. Click **Batch Replay Selected**.
3. A confirmation modal displays: *"Confirm replay of 42 events to target endpoints?"*.
4. On confirmation, the frontend triggers `POST /api/v1/replay/batch` and transitions the selected table rows to a grey loading spinner state.
5. As SQS processes the replay, the rows automatically clear from the DLQ view.
