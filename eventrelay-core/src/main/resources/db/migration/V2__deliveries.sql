-- Milestone 2: per-(event × subscription) delivery lifecycle.
--
-- A `delivery` tracks the state and retry schedule of delivering one event to
-- one subscription. The outbox relay creates these rows; the scheduler publishes
-- due ones to SQS; the worker advances them through the state machine:
--
--   PENDING ─► QUEUED ─► DELIVERED
--                  │
--                  ├─► RETRYING ─► QUEUED ...   (transient failure, backoff)
--                  └─► DEAD                      (permanent failure / retries exhausted)
--
-- Retry timing lives here (next_attempt_at), not in SQS, so backoff is not
-- bounded by the SQS 15-minute DelaySeconds cap.

CREATE TABLE deliveries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id         UUID NOT NULL,
    subscription_id  UUID NOT NULL REFERENCES subscriptions(id),
    tenant_id        UUID NOT NULL REFERENCES tenants(id),
    event_type       VARCHAR(255) NOT NULL,
    target_url       VARCHAR(2048) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count    INTEGER NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    leased_at        TIMESTAMPTZ,
    last_error       TEXT,
    last_http_status INTEGER,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_deliveries_status
        CHECK (status IN ('PENDING', 'QUEUED', 'DELIVERED', 'RETRYING', 'DEAD'))
);

-- Scheduler hot path: find rows eligible for (re)dispatch, oldest first.
CREATE INDEX idx_deliveries_due ON deliveries(next_attempt_at)
    WHERE status IN ('PENDING', 'RETRYING');

-- Reclaim path: find QUEUED rows whose lease has expired (stuck in flight).
CREATE INDEX idx_deliveries_leased ON deliveries(leased_at)
    WHERE status = 'QUEUED';

CREATE INDEX idx_deliveries_event ON deliveries(event_id);
CREATE INDEX idx_deliveries_subscription ON deliveries(subscription_id, created_at DESC);
