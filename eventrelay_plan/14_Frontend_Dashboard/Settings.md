# EventRelay — Settings & Configurations UI

This document details the configuration screens, API Key management widgets, and subscription creation forms in the EventRelay dashboard.

---

## 1. UI Layout: Subscription Builder

To create a new webhook endpoint, the administrator opens the Subscription Builder:

- **Input Form**:
  - `Subscription Name`: Friendly label (e.g., "Slack Fulfilment Alert").
  - `Webhook Destination URL`: Output target domain.
  - `Event Subscriptions`: Checklist of event types or custom glob patterns (e.g., `order.*`).
  - `Rate Limit Selector`: Input slider to set tenant bucket rate limit (10 to 1,000 req/s).
- **Test Webhook Trigger**: A button next to the URL input: **Send Test Event**. Clicking this triggers a mock payload with dummy headers to verify receiver connectivity.

---

## 2. API Key Management Console

- **Key Generation Wizard**: Clicking **Create API Key** opens a modal displaying the key prefix and a masked sequence.
- **"Reveal Key" Warning**: The plaintext key is shown exactly once. A warning alert displays: *"Please copy this API key and store it securely. For security, you will not be able to view it again."*.
- **Revocation Action**: Next to each active key in the list is a **Revoke** button. Clicking this triggers a modal requesting confirmation before modifying the database.
