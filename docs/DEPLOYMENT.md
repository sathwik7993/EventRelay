# Deploying EventRelay

The live demo runs on **one DigitalOcean droplet** (covered by the GitHub Student
Developer Pack credit) using **real AWS SQS**, which is permanently free up to
1M requests/month — far more than a demo needs.

> The ECS Fargate architecture in [`infra/terraform/`](../infra/terraform) is the
> production design of record but is deliberately not applied: Fargate/RDS/
> ElastiCache are not free-tier. The same container images run in both places
> because all config is environment-variable driven.

---

## 1. Create the SQS queue and a scoped IAM user

In the AWS console (or CLI), region `us-east-1`:

```bash
aws sqs create-queue --queue-name eventrelay-deliveries \
  --attributes VisibilityTimeout=60
```

The dispatcher also creates the queue on startup if missing, so this step is
optional — but pre-creating it lets you drop `sqs:CreateQueue` from the policy.

Create an IAM user (programmatic access) with **only** this policy — never use
root credentials:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "sqs:SendMessage",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueUrl",
      "sqs:GetQueueAttributes"
    ],
    "Resource": "arn:aws:sqs:us-east-1:<ACCOUNT_ID>:eventrelay-deliveries"
  }]
}
```

Save the access key id and secret — they go in `.env` below.

### Staying inside the free tier
SQS bills per request. Each delivery costs roughly 3 requests (send, receive,
delete), and the dispatcher long-polls (`wait-time-seconds: 10`), so an idle
system uses ~6 receive calls/minute per worker thread. That is a few hundred
thousand requests/month — comfortably under 1M. If you idle the demo for long
periods, scale the dispatcher to zero or raise the poll interval.

---

## 2. Provision the droplet

- **Size:** 2 GB RAM / 1 vCPU minimum (~$12/mo from the $200 credit). The 1 GB
  droplet is too small: Postgres + Redis + two JVMs will OOM.
- **Image:** Ubuntu 22.04 LTS.
- Add your SSH key during creation.

Install Docker:

```bash
ssh root@<droplet-ip>
curl -fsSL https://get.docker.com | sh
```

Basic hardening (recommended):

```bash
ufw allow OpenSSH
ufw allow 8080/tcp      # API; omit if you put a reverse proxy in front
ufw enable
```

---

## 3. Deploy

```bash
git clone https://github.com/sathwik7993/EventRelay.git
cd EventRelay

cp .env.prod.example .env
nano .env        # set DB_PASSWORD + the AWS keys from step 1

docker compose -f docker-compose.prod.yml up -d --build
```

The API runs Flyway migrations on startup; the dispatcher waits for the API to be
healthy before starting, so the schema always exists before it validates.

Check it:

```bash
docker compose -f docker-compose.prod.yml ps
curl -s http://localhost:8080/actuator/health
docker compose -f docker-compose.prod.yml logs -f dispatcher
```

---

## 4. Smoke test

```bash
BASE=http://<droplet-ip>:8080

# Create a tenant (returns the API key once)
curl -s -X POST $BASE/api/v1/tenants -H 'Content-Type: application/json' \
  -d '{"name":"Demo","slug":"demo"}'

KEY=er_live_...

# Point a subscription at a throwaway endpoint (e.g. webhook.site)
curl -s -X POST $BASE/api/v1/subscriptions \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"targetUrl":"https://webhook.site/<your-uuid>","eventTypes":["demo.event"]}'

# Ingest — it should arrive at the endpoint, HMAC-signed, within a second or two
curl -s -X POST $BASE/api/v1/events \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"eventType":"demo.event","data":{"hello":"world"}}'
```

---

## 5. Operating it

| Task | Command |
|---|---|
| Logs | `docker compose -f docker-compose.prod.yml logs -f api dispatcher` |
| Restart a service | `docker compose -f docker-compose.prod.yml restart dispatcher` |
| Deploy a new version | `git pull && docker compose -f docker-compose.prod.yml up -d --build` |
| Database backup | `docker compose -f docker-compose.prod.yml exec postgres pg_dump -U eventrelay eventrelay > backup.sql` |
| Stop everything | `docker compose -f docker-compose.prod.yml down` |
| Stop and wipe data | `docker compose -f docker-compose.prod.yml down -v` |

### Before showing it publicly
- Tenant creation (`POST /api/v1/tenants`) is unauthenticated — it is a bootstrap
  endpoint. Firewall it, put it behind a reverse proxy with basic auth, or create
  your tenants and then block the route.
- Terminate TLS with Caddy or nginx in front of the API before exposing it beyond
  a demo; subscription target URLs should be HTTPS.
- Rotate the `DB_PASSWORD` from the example value.

### Cost control
Destroy the droplet when you are not demoing (`doctl compute droplet delete`) and
redeploy in minutes — the whole stack is one compose command. SQS costs nothing at
rest.
