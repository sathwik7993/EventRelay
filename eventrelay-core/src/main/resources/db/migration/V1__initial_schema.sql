-- EventRelay initial schema (Milestone 1).
--
-- Faithful to eventrelay_plan/07_Database/PostgreSQL_Schema.md with two
-- deliberate M1 simplifications, both behaviourally equivalent:
--   1. Status columns use VARCHAR + CHECK instead of native ENUM types
--      (maps cleanly to JPA @Enumerated(STRING)).
--   2. events / delivery_attempts are NOT range-partitioned yet
--      (partitioning is introduced in Milestone 3, matching the plan's
--      "production considerations").

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- -----------------------------------------------------------------------------
-- Tenants
-- -----------------------------------------------------------------------------
CREATE TABLE tenants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    slug        VARCHAR(63)  NOT NULL,
    settings    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    tier        VARCHAR(20)  NOT NULL DEFAULT 'free',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,

    CONSTRAINT uq_tenants_slug UNIQUE (slug),
    CONSTRAINT chk_tenants_tier CHECK (tier IN ('free', 'starter', 'business', 'enterprise'))
);

-- -----------------------------------------------------------------------------
-- API keys
-- -----------------------------------------------------------------------------
CREATE TABLE api_keys (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    key_hash    VARCHAR(255) NOT NULL,
    key_prefix  VARCHAR(16)  NOT NULL,
    name        VARCHAR(255) NOT NULL,
    scopes      TEXT[]       NOT NULL DEFAULT '{events:write}',
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ,

    CONSTRAINT uq_api_keys_prefix UNIQUE (key_prefix)
);

CREATE INDEX idx_api_keys_tenant_id ON api_keys(tenant_id);
CREATE INDEX idx_api_keys_key_prefix ON api_keys(key_prefix) WHERE revoked_at IS NULL;

-- -----------------------------------------------------------------------------
-- Subscriptions
-- -----------------------------------------------------------------------------
CREATE TABLE subscriptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    target_url      VARCHAR(2048) NOT NULL,
    event_types     TEXT[]        NOT NULL DEFAULT '{}',
    signing_secret  VARCHAR(255)  NOT NULL,
    description     VARCHAR(500),
    config          JSONB         NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    failure_count   INTEGER       NOT NULL DEFAULT 0,
    last_failure_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT chk_subscriptions_url CHECK (target_url ~ '^https?://'),
    CONSTRAINT chk_subscriptions_status CHECK (status IN ('ACTIVE', 'PAUSED', 'DISABLED'))
);

CREATE INDEX idx_subscriptions_tenant_id ON subscriptions(tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_subscriptions_event_types ON subscriptions USING GIN (event_types) WHERE deleted_at IS NULL;

-- -----------------------------------------------------------------------------
-- Events (append-only log)
-- -----------------------------------------------------------------------------
CREATE TABLE events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    event_type      VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255),
    payload         JSONB NOT NULL,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_events_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_events_tenant_created ON events(tenant_id, created_at DESC);
CREATE INDEX idx_events_tenant_type ON events(tenant_id, event_type);

-- -----------------------------------------------------------------------------
-- Outbox (transactional outbox pattern)
-- -----------------------------------------------------------------------------
CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(100) NOT NULL DEFAULT 'Event',
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count     INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,

    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')),
    CONSTRAINT chk_outbox_retry CHECK (retry_count <= 10)
);

CREATE INDEX idx_outbox_pending ON outbox(id ASC) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_processed ON outbox(processed_at) WHERE status = 'PROCESSED';

-- -----------------------------------------------------------------------------
-- Delivery attempts
-- -----------------------------------------------------------------------------
CREATE TABLE delivery_attempts (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id              UUID NOT NULL,
    subscription_id       UUID NOT NULL REFERENCES subscriptions(id),
    tenant_id             UUID NOT NULL REFERENCES tenants(id),
    attempt_number        INTEGER NOT NULL,
    status                VARCHAR(20) NOT NULL,
    http_status_code      INTEGER,
    response_body_snippet TEXT,
    duration_ms           INTEGER NOT NULL,
    error_message         TEXT,
    target_url            VARCHAR(2048) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_delivery_status CHECK (status IN ('SUCCESS', 'FAILED', 'TIMEOUT', 'SKIPPED')),
    CONSTRAINT chk_delivery_attempt_number CHECK (attempt_number > 0 AND attempt_number <= 20),
    CONSTRAINT chk_delivery_duration CHECK (duration_ms >= 0)
);

CREATE INDEX idx_delivery_event_id ON delivery_attempts(event_id);
CREATE INDEX idx_delivery_tenant_created ON delivery_attempts(tenant_id, created_at DESC);
CREATE INDEX idx_delivery_subscription ON delivery_attempts(subscription_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- Dead letter events (populated from Milestone 2; table created now for stability)
-- -----------------------------------------------------------------------------
CREATE TABLE dead_letter_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id         UUID NOT NULL,
    subscription_id  UUID NOT NULL REFERENCES subscriptions(id),
    tenant_id        UUID NOT NULL REFERENCES tenants(id),
    event_type       VARCHAR(255) NOT NULL,
    payload          JSONB NOT NULL,
    failure_reason   TEXT NOT NULL,
    last_http_status INTEGER,
    total_attempts   INTEGER NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    failed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    replayed_at      TIMESTAMPTZ,
    discarded_at     TIMESTAMPTZ,
    replayed_by      VARCHAR(255),

    CONSTRAINT chk_dlq_status CHECK (status IN ('PENDING', 'REPLAYED', 'DISCARDED')),
    CONSTRAINT chk_dlq_attempts CHECK (total_attempts > 0)
);

CREATE INDEX idx_dlq_tenant_status ON dead_letter_events(tenant_id, status) WHERE status = 'PENDING';
CREATE INDEX idx_dlq_tenant_failed ON dead_letter_events(tenant_id, failed_at DESC);
