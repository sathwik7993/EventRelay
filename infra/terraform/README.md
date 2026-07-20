# Terraform — ECS Fargate reference deployment

This is the **cloud-native target architecture** for EventRelay: an ALB in front of
autoscaling API tasks, and dispatcher workers that scale on SQS queue depth.

```
Internet ──> ALB ──> ECS Fargate (eventrelay-api, 2+ tasks)
                          │
                     RDS PostgreSQL   ElastiCache Redis
                          │
                       AWS SQS ──> ECS Fargate (eventrelay-dispatcher)
                                      autoscales on ApproximateNumberOfMessagesVisible
```

> [!IMPORTANT]
> **This configuration is intentionally not applied.** ECS Fargate, RDS, and
> ElastiCache are not covered by the AWS free tier, and this project is run on a
> student budget. The live demo therefore runs on a single DigitalOcean droplet
> (see [`docs/DEPLOYMENT.md`](../../docs/DEPLOYMENT.md)) using **real AWS SQS**,
> which *is* permanently free up to 1M requests/month.
>
> These files are kept as the production design of record — the same container
> images deploy either way, since all configuration is environment-variable driven.

## What it provisions

| Resource | Purpose |
|---|---|
| `aws_sqs_queue.deliveries` (+ DLQ) | Delivery work queue; redrive catches poison messages |
| `aws_ecs_cluster` + 2 services | API (behind ALB) and dispatcher (no inbound) |
| `aws_lb` + target group | Public HTTPS entry point, health-checked on `/actuator/health/readiness` |
| IAM execution + task roles | Least-privilege SQS access; DB password read from Secrets Manager |
| CloudWatch log groups | Structured JSON logs from both services |
| App Auto Scaling | Dispatcher tasks scale out on queue backlog |

## Usage (if you ever do apply it)

```bash
terraform init
terraform plan \
  -var="api_image=<ecr>/eventrelay-api:tag" \
  -var="dispatcher_image=<ecr>/eventrelay-dispatcher:tag" \
  -var="db_url=jdbc:postgresql://<rds-endpoint>:5432/eventrelay" \
  -var="db_username=eventrelay" \
  -var="db_password_secret_arn=arn:aws:secretsmanager:..." \
  -var="redis_host=<elasticache-endpoint>"
```

RDS and ElastiCache are expected to exist (or be added as modules); they are
referenced by variable so the compute layer stays independent of how the data
layer is provisioned.
