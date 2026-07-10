# EventRelay — Secret Rotation Strategy

This document details the strategies and workflows for rotating sensitive keys, signing secrets, and credentials in the EventRelay platform with zero message delivery failure.

---

## 1. Tenant Webhook Signing Secret Rotation

To maintain high security, tenants should rotate their signing secrets regularly. To prevent delivery failures during rotation, EventRelay supports a **dual-secret rollover window**:

```
[ Active Secret (New) ] ──┐
                          ├──► [ Generate Outgoing Signatures (v1, v2) ] ──► [ Request Headers ]
[ Previous Secret ] ──────┘
```

1. **Initiate Rotation**: The tenant calls the rotation API: `POST /api/v1/subscriptions/{id}/rotate-secret`.
2. **Dual-Key State**: The system generates a new secret key, promotions it as the `active_secret`, and demotes the existing key to `previous_secret`. Both keys are kept in the database.
3. **Double Signature Generation**: When a dispatcher worker executes a webhook delivery, it computes two signatures:
   - One using the `active_secret` (prefix: `v1=`).
   - One using the `previous_secret` (prefix: `v2=`).
   - The resulting header looks like: `X-EventRelay-Signature: t=1672531199,v1=signature_new,v2=signature_old`.
4. **Validation Buffer**: The receiver's verification logic will accept a match against *either* signature.
5. **Revocation**: After a 24-hour overlap window, the system automatically runs a cleanup job, deletes the `previous_secret`, and returns the dispatch engine to single-signature mode.

---

## 2. API Key Rotation Flow

API keys used for ingestion authorization follow a similar overlapping flow:

- **Grace Period**: When a client requests a new API key (`POST /api/v1/auth/keys/rotate`), the system generates a new key but leaves the old key valid for 12 hours.
- **Client Deployment**: This allows the client platform to deploy the new key across their environments without experiencing ingestion timeouts.
- **Audit Logs**: Every key rotation event logs the trigger user, environment, and timestamps for compliance auditing.

---

## 3. Database Credentials Rotation (AWS Secrets Manager)

To comply with enterprise security requirements, database passwords are rotated every 30 days:

1. **Secrets Manager Integration**: AWS Secrets Manager triggers rotation via a serverless Lambda function.
2. **Double-Pass Authentication**: The Lambda function creates a temporary database user with the same privileges as the active user, updates the password, and updates AWS Secrets Manager.
3. **Application Cache Refresh**: The EventRelay ECS tasks poll AWS Secrets Manager periodically or receive an invalidation event via AWS AppConfig.
4. **Connection Pool Recalibration**: HikariCP handles database connection failures by closing stale connections and opening new ones with the updated credentials from the local secrets cache, requiring zero application downtime.
