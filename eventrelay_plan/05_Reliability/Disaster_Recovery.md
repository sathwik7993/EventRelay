# EventRelay — Disaster Recovery (DR) Plan

This document details the disaster recovery (DR) strategy, backup policies, and runbooks configured to restore operations in the event of major AWS regional outages or catastrophic data corruption.

---

## 1. RTO and RPO Targets

| Disaster Classification | Target RTO (Recovery Time) | Target RPO (Data Loss) | Recovery Strategy |
|-------------------------|----------------------------|------------------------|-------------------|
| **AWS AZ Outage** | $< 2$ Minutes | $0$ (No loss) | Active-Active routing across surviving zones. |
| **AWS Region Outage** | $< 4$ Hours | $< 5$ Minutes | Deploy infrastructure to secondary region via Terraform; restore RDS snapshot. |
| **Database Corruption** | $< 1$ Hour | $< 5$ Minutes | Point-in-Time Recovery (PITR) to a timestamp prior to corruption. |

---

## 2. Backup & Archival Schedule

EventRelay implements automated backup rules across the AWS infrastructure:

- **RDS Database Backups**:
  - **Automated Snapshots**: Daily backups retained for 35 days.
  - **Point-in-Time Recovery (PITR)**: Write-Ahead Logs (WAL) are shipped to S3 every 5 minutes, allowing restore to any second within the retention window.
- **S3 Archive Storage**:
  - **Event Archival**: All events older than 90 days are moved from PostgreSQL to a secure S3 bucket (`eventrelay-longterm-archive`). S3 buckets are configured with **Cross-Region Replication (CRR)** to a backup region (e.g., `us-west-2` to `us-east-1`).
  - **Replication SLA**: S3 CRR replicates $99.9\%$ of objects within 15 minutes.

---

## 3. Disaster Recovery Runbook: Regional Failover

If the primary AWS region (e.g., `us-east-1`) experiences a catastrophic, prolonged outage, follow these steps to deploy EventRelay to the backup region (e.g., `us-west-2`):

### Phase 1: Infrastructure Deployment
1. Open the local CI/CD CLI.
2. Initialize Terraform targetting the secondary region:
   ```bash
   cd terraform/environments/prod
   terraform init
   terraform select-workspace prod-us-west-2
   terraform apply -auto-approve
   ```
3. This provisions ALB, ECS Clusters, SQS queues, and RDS instances in the secondary region.

### Phase 2: Data Restoration
1. Access the RDS console in the secondary region.
2. Select **Restore to Point-in-Time** using the latest replicated database snapshot.
3. Once the database restore completes (typically 30-50 minutes), update the database endpoint URL in AWS Systems Manager Parameter Store.

### Phase 3: Traffic Switch
1. Update DNS routing in AWS Route 53.
2. Change the weight of the primary region to $0\%$ and set the secondary region to $100\%$.
3. Verify connection logs in the secondary region ECS tasks.
4. Instruct clients to resume event submission.
