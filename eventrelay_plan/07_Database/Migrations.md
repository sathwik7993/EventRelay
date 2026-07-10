# EventRelay — Database Migration Strategy (Flyway)

This document details the database migration patterns, version naming, and safety rules for Flyway migrations in EventRelay to achieve zero-downtime database updates.

---

## 1. Flyway Directory Structure

EventRelay uses Flyway version control to apply SQL updates systematically:

```
eventrelay-core/src/main/resources/db/migration/
├── V1__init_schema.sql
├── V2__add_rate_limiting_configs.sql
├── V3__add_circuit_breaker_states.sql
└── U2__rollback_rate_limiting.sql
```

- **`V`**: Prefix for versioned, forward migrations.
- **`U`**: Prefix for undo, rollback migrations (tested for emergency recoveries).
- **`__`**: Double underscore separator.

---

## 2. Database Migration Lifecycle

Migrations are applied automatically during application startup in development and staging. In production, migrations are triggered as a distinct, pre-deployment step:

```
[ CI/CD Deploy Stage ] ──► [ Run Flyway Migration Task ] ──► [ Verify Success ] ──► [ Rollout ECS Containers ]
```

- This ensures that database schema changes are complete and verified before the new code runs on container tasks.

---

## 3. Zero-Downtime Migration Design Rules

To prevent database locks during high traffic, migrations must be **backward-compatible**:

- **Rule 1: Never Rename Columns**: Instead, create a new column, deploy code that writes to *both* columns, backfill historical data, and drop the old column in a future release.
- **Rule 2: Add Columns with Defaults Safely**: Do not add columns with a `NOT NULL` constraint and no default value. Instead, add the column as nullable first, populate it, and apply the constraint later.
- **Rule 3: Avoid Table Locks on Large Tables**: Adding an index locks tables and blocks writes. In PostgreSQL, index additions must use `CONCURRENTLY`:
  ```sql
  -- Flyway migrations cannot run concurrent indexes in transactional blocks.
  -- Use the flyway annotation:
  -- @callback: beforeMigrate
  CREATE INDEX CONCURRENTLY idx_events_subscription ON events(subscription_id);
  ```

---

## 4. Verification and Rollback Procedure

- **Validate Migrations**: Before deploying a release, run `flyway info` in a staging replica environment to verify migration states.
- **Rollback Trigger**: If a migration fails mid-deployment, the CI/CD pipeline aborts and alerts developers. The database administrator runs the corresponding `U` script manually or restores the pre-deployment database snapshot.
