# EventRelay — Networking & Security Groups

This document details the VPC networking architecture, subnets, route tables, and security group policies for EventRelay.

---

## 1. Network Subnet Table

EventRelay is isolated within a private VPC with a CIDR range of `10.0.0.0/16`:

```
VPC: 10.0.0.0/16
├── Public Subnets (10.0.1.0/24, 10.0.2.0/24)
│   └── ALB, NAT Gateways
├── Private App Subnets (10.0.10.0/24, 10.0.20.0/24)
│   └── Ingest Service, Dispatcher Workers
└── Private Data Subnets (10.0.100.0/24, 10.0.200.0/24)
    └── RDS PostgreSQL, ElastiCache Redis
```

- **Route Tables**:
  - Public Subnets route `0.0.0.0/0` to the AWS Internet Gateway.
  - Private App Subnets route `0.0.0.0/0` to the NAT Gateways.
  - Private Data Subnets have no route to `0.0.0.0/0`.

---

## 2. Security Group Network Rules

Traffic is restricted at the network interfaces using AWS Security Groups:

```
                  [ Public Internet ]
                           │
                  [ Port 443 HTTPS ]
                           ▼
                      [ ALB-SG ]
                           │
                   [ Port 8080 HTTP ]
                           ▼
                      [ Ingest-SG ]
                      ┌────┴────┐
           [ Port 5432 ]        [ Port 6379 ]
                ▼                    ▼
            [ RDS-SG ]          [ Redis-SG ]
```

### ALB Security Group (`ALB-SG`)
- **Inbound**: Port `443` from `0.0.0.0/0`.
- **Outbound**: Port `8080` to private application subnets.

### Ingestion Service Security Group (`Ingest-SG`)
- **Inbound**: Port `8080` from `ALB-SG`.
- **Outbound**:
  - Port `5432` to `RDS-SG`.
  - Port `6379` to `Redis-SG`.

### Dispatcher Workers Security Group (`Workers-SG`)
- **Inbound**: None.
- **Outbound**:
  - Port `5432` to `RDS-SG`.
  - Port `6379` to `Redis-SG`.
  - Port `443` to target domains via NAT Gateway (for webhook deliveries).

### Database Security Group (`RDS-SG`)
- **Inbound**: Port `5432` from `Ingest-SG` and `Workers-SG`.
- **Outbound**: None.
