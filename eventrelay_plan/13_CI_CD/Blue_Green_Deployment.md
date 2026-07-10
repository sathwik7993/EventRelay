# EventRelay — AWS Blue-Green Deployment

This document details the Blue-Green deployment architecture and configuration utilized to update EventRelay services with zero downtime.

---

## 1. Blue-Green Deployment Flow

EventRelay leverages **AWS CodeDeploy** and Application Load Balancers (ALB) to route traffic between container groups:

```
[ Inbound HTTPS ] ──► [ ALB (Port 443) ]
                             │
            ┌────────────────┴────────────────┐
            ▼ (Active)                        ▼ (Deploying)
   [ Target Group Blue ]             [ Target Group Green ]
   (Active Task: v1.0.0)             (Deploying Task: v1.1.0)
            │                                 │
            ▼                                 ▼
   Ingest Containers                 Ingest Containers
```

1. **Deploy Green Environment**: CodeDeploy creates the Green target group, launching new container tasks running the updated code.
2. **Execute Hook Tests**: Before switching traffic, integration tests check the readiness health of the Green tasks.
3. **Route Traffic**: The ALB shifts traffic from Blue to Green target groups based on the shift configuration (e.g., all-at-once or canary).
4. **Deprovision Blue**: CodeDeploy terminates the tasks in the Blue target group after a connection cooling window of 5 minutes.

---

## 2. AWS CodeDeploy AppSpec Configuration

The CodeDeploy behavior is governed by the `appspec.yml` file:

```yaml
version: 0.0
Resources:
  - myECSLogicalService:
      Type: AWS::ECS::Service
      Properties:
        TaskDefinition: "arn:aws:ecs:us-east-1:123456789012:task-definition/eventrelay-ingest:4"
        LoadBalancerInfo:
          ContainerName: "eventrelay-ingest-container"
          ContainerPort: 8080
        CapacityProviderStrategy:
          - CapacityProvider: "FARGATE"
            Weight: 1
            Base: 1
Hooks:
  - BeforeAllowTraffic: "arn:aws:lambda:us-east-1:123456789012:function:eventrelay-pre-traffic-validator"
  - AfterAllowTraffic: "arn:aws:lambda:us-east-1:123456789012:function:eventrelay-post-traffic-smoke-tester"
```

---

## 3. Pre-Traffic Validation Hook

The `BeforeAllowTraffic` hook runs a Lambda function to perform sanity checks:
- Verify database schemas match task expectations.
- Run readiness requests against the container tasks using private target IPs.
- If the validator returns a failure, CodeDeploy aborts the deployment and triggers an automatic rollback.
