# EventRelay — AWS IAM Configuration

This document outlines the AWS Identity and Access Management (IAM) security policies, role definitions, and trust relationships required to run EventRelay under the principle of least privilege.

---

## 1. ECS Task Execution Role

This role grants the ECS agent permission to pull container images from AWS ECR and write logs to CloudWatch.

### Policy Definition
- **Role Name**: `eventrelay-task-execution-role`
- **Trust Relationship**:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs-tasks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
```
- **Attached Policies**:
  - `service-role/AmazonECSTaskExecutionRolePolicy` (AWS Managed)
  - Custom Policy for retrieving secrets:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue",
        "ssm:GetParameters"
      ],
      "Resource": [
        "arn:aws:secretsmanager:*:*:secret:eventrelay/*",
        "arn:aws:ssm:*:*:parameter/eventrelay/*"
      ]
    }
  ]
}
```

---

## 2. Ingest API ECS Task Role

This role is assumed by the Ingest Service containers at runtime, granting access to databases and queues.

- **Role Name**: `eventrelay-ingest-task-role`
- **Permissions**:
  - **SQS Access (Write only)**: Publish incoming events to SQS.
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "sqs:SendMessage",
        "sqs:GetQueueAttributes"
      ],
      "Resource": "arn:aws:sqs:*:*:eventrelay-delivery-queue"
    }
  ]
}
```

---

## 3. Dispatcher Worker ECS Task Role

This role is assumed by the Dispatcher Worker containers at runtime.

- **Role Name**: `eventrelay-dispatcher-task-role`
- **Permissions**:
  - **SQS Access (Read, Delete)**: Poll messages, adjust visibility timeouts, and delete messages on successful processing.
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:ChangeMessageVisibility",
        "sqs:GetQueueAttributes",
        "sqs:GetQueueUrl"
      ],
      "Resource": [
        "arn:aws:sqs:*:*:eventrelay-delivery-queue",
        "arn:aws:sqs:*:*:eventrelay-dlq"
      ]
    }
  ]
}
```
  - **S3 Access (Archive write)**: Archive processed payloads to S3 bucket.
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:PutObjectAcl"
      ],
      "Resource": "arn:aws:s3:::eventrelay-historical-backups/*"
    }
  ]
}
```
