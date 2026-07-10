# EventRelay — Data Retention & Archival Policy

This document details the data retention limits, automated pruning schedules, and archival processes implemented in EventRelay to control storage growth and ensure GDPR compliance.

---

## 1. Data Retention Window

EventRelay operates on a tier-based data retention schedule:

| Table Category | Retained Window | Archival Target | Cleanup Execution Method |
|----------------|-----------------|-----------------|--------------------------|
| **`outbox`** | Immediate (on success) | None | Row deletion triggered immediately by Outbox Poller. |
| **`events`** (Active DB) | `90 Days` | AWS S3 Bucket | monthly partition detach and drop. |
| **`delivery_attempts`** | `30 Days` | AWS S3 Bucket | Monthly partition detach and drop. |
| **`dead_letter_events`**| `30 Days` | AWS S3 Bucket | Daily batch clean cron job on PostgreSQL. |
| **`tenants`** & configs | Permanent | None | Kept as active configurations. |

---

## 2. Partition Detach & Archival Flow

For range-partitioned tables (`events`, `delivery_attempts`), cleanup is executed at the partition level:

```
[ Active Partition ] ──► [ Detach Partition ] ──► [ Export to S3 CSV ] ──► [ Drop Partition ]
                                                           │
                                                           ▼ (AWS Glacier rule)
                                                   [ Cold Archive ]
```

1. **Detach**: The partition to be pruned is detached from the parent table, separating it from active queries without locking the parent table.
2. **Export to CSV**: A background utility exports the detached table data to a secure S3 bucket (`eventrelay-historical-backups`) in CSV format.
3. **Drop**: Once the S3 transfer is validated, the database drops the detached partition table, releasing storage block clusters to PostgreSQL immediately.

---

## 3. GDPR compliance (Right to Erasure)

Under GDPR rules, tenants can request the immediate deletion of customer data:

- **Implementation**: When an erasure API call is made (`POST /api/v1/tenants/{id}/purge-data`), the system identifies and purges event payloads matching the tenant.
- **Handling Partitions**: Because dropping partitions is efficient, but deleting specific rows across partitions causes table locks, EventRelay runs a row-level update:
  ```sql
  UPDATE events 
  SET payload = '{"purged": true}' 
  WHERE tenant_id = ? AND created_at >= ?;
  ```
- This redacts sensitive customer data within payloads without affecting database structure or metadata audit trails.
