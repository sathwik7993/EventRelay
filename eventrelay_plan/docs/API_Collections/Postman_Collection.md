# EventRelay — API Testing Collection

This document describes the structure and test scripts included in the EventRelay Postman Collection for automated API testing.

---

## 1. Postman Collection Directory Structure

The collection is organized into five functional folders:

- **1. Ingest API**: Test cases for submitting events. Includes assertions for duplicate `X-Idempotency-Key` calls and payload size limit enforcement.
- **2. Subscriptions**: Registering, updating, and verifying webhooks. Includes pre-request scripts to handle challenge-response parameters.
- **3. Admin & Auth**: API Key generation, token requests, scopes validation, and secret rotation triggers.
- **4. DLQ & Replay**: Inspecting dead-letter events and executing single or batch replays.
- **5. Health Checks**: Verifying actuator probes (`/health/live`, `/health/ready`).

---

## 2. HMAC Pre-Request Script (Postman)

To test HMAC verification endpoints using Postman, the following pre-request script computes signatures dynamically before executing calls:

```javascript
// Postman Pre-request Script for HMAC Signature
const payload = pm.request.body.toString();
const timestamp = Math.floor(Date.now() / 1000).toString();
const secret = pm.environment.get("WH_SECRET_KEY");

const signingString = `t=${timestamp}.v1=${payload}`;
const signature = CryptoJS.HmacSHA256(signingString, secret).toString(CryptoJS.enc.Hex);

pm.request.headers.add({
    key: 'X-EventRelay-Signature',
    value: `t=${timestamp},v1=${signature}`
});
```
---

## 3. Automation Runner (Newman)

The postman collection can be executed headlessly inside CI pipelines using **Newman**:

```bash
newman run eventrelay_collection.json \
  -e environments/staging.json \
  --reporters cli,junit \
  --reporter-junit-export reports/api-tests.xml
```
- A non-zero exit code from Newman will abort the pipeline and block deployment to staging or production.
