# EventRelay — Cloud Cost Optimization

This document outlines the cost optimization strategies, instance sizing, and SQS/NAT Gateway savings configurations applied to reduce EventRelay's AWS monthly spend.

---

## 1. AWS Resource Sizing (Monthly Cost Baseline)

The table below breaks down the estimated monthly cost for different traffic tiers:

| AWS Service | Developer Sandbox | Production (Medium Scale) | Enterprise (High Scale) |
|-------------|-------------------|---------------------------|-------------------------|
| **Compute (Fargate)** | 1x Ingest, 1x Worker ($35/mo) | 4x Ingest, 10x Worker ($280/mo) | Auto-scaled tasks ($1,200/mo) |
| **Database (RDS)** | `db.t3.medium` ($30/mo) | `db.m6g.xlarge` ($180/mo) | Multi-AZ Clustered ($650/mo) |
| **Cache (Redis)** | `cache.t3.micro` ($15/mo) | `cache.m6g.large` ($70/mo) | Clustered shards ($280/mo) |
| **NAT Gateways** | 1x NAT ($32/mo) | 2x NAT ($64/mo + traffic) | 2x NAT ($64/mo + VPC endpoints) |
| **Queue (SQS)** | Free tier ($0/mo) | $0.40 per 1M APIs ($12/mo) | $150/mo |
| **Total Estimate** | **~$112/Month** | **~$606/Month** | **~$2,444/Month** |

---

## 2. Fargate Spot Instances

To reduce compute costs for background delivery workloads:
- **Dispatcher Workers** are configured to run on **ECS Fargate Spot** tasks, saving up to $70\%$ compared to standard On-Demand Fargate prices.
- **Graceful Failover**: Since Fargate Spot tasks can be terminated by AWS with a 2-minute warning, the worker code handles `SIGTERM` by pushing in-flight SQS messages back to SQS, guaranteeing no messages are lost during spot interruption.
- **Ingestion API** runs on On-Demand Fargate to guarantee API availability.

---

## 3. VPC Endpoints (Reducing NAT Gateway Costs)

AWS charges $0.045 per GB of data processed by NAT Gateways. Because dispatcher workers transfer gigabytes of payload data, NAT costs can inflate.

- **Solution**: EventRelay provisions **VPC Interface Endpoints** (AWS PrivateLink) for SQS, S3, and Secrets Manager.
- **Savings**: Data sent from Ingest to SQS, and logs sent from tasks to S3, bypasses the NAT Gateway, routing through local endpoints and reducing NAT traffic by over $80\%$.
- Only outbound webhook deliveries go through the NAT Gateways.
