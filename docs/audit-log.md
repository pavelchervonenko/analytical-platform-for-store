# Persistent audit log

Status: implemented through Flyway V12 and revalidated on 2026-07-26.

## Purpose

`audit_log` is the unified persistent history of security-sensitive, operational and financial
commands. Domain tables may still keep author fields required by their own snapshots, but those
fields do not replace the action history.

Each entry contains:

- `action`: a stable value from `AuditAction`;
- `entity_type` and `entity_id`: the affected aggregate;
- `actor_user_id`: the authenticated author, or null only for an explicitly recorded system job;
- `store_id`: store scope when the command belongs to a store;
- `created_at`: database-owned event time;
- `metadata`: versioned safe JSON with an optional reason and before/after summaries;
- `retention_class`: immutable `FINANCIAL`, `SECURITY`, `BUSINESS` or `OPERATIONAL`;
- `retain_until`: earliest permitted purge time, or null only for indefinite financial history.

The IP address and user-agent columns remain reserved for a future request-context adapter. They are
not populated by the application services.

## Audited commands

| Area | Actions |
| --- | --- |
| Plans and shifts | `PERFORMANCE_PLAN_CHANGED`, `WORK_SCHEDULE_REPLACED` |
| Employee rating | `EMPLOYEE_RATING_PARTICIPATION_CHANGED`, `EMPLOYEE_RATING_FINALIZED`, `RATING_SCHEME_CREATED` |
| Product classification | `ANALYTICS_PRODUCT_CLASSIFIED`, `PAYROLL_PRODUCT_CLASSIFIED` |
| Payroll formulas and calculation | `PAYROLL_SCHEME_CREATED`, `PAYROLL_CALCULATED`, `PAYROLL_RECALCULATED`, `PAYROLL_REVISION_CREATED` |
| Payroll lifecycle | `PAYROLL_ADJUSTMENT_CREATED`, `PAYROLL_ADJUSTMENT_VOIDED`, `PAYROLL_APPROVED`, `PAYROLL_PAID` |
| User administration | `USER_CREATED`, `USER_CHANGED`, `USER_STORE_ACCESS_CHANGED`, `USER_PASSWORD_RESET` |
| Bootstrap and emergency access | `BOOTSTRAP_ADMIN_CREATED`, `BREAK_GLASS_LOGIN_SUCCEEDED` |
| Synchronization | `MANUAL_SYNC_STARTED`, `SCHEDULED_SYNC_STARTED`, `SYNC_JOB_CANCELLATION_REQUESTED` |
| Finalized reports | `MONTHLY_REPORT_FINALIZED`, `ANNUAL_REPORT_FINALIZED`, `REPORT_BACKFILL_REQUESTED`, `REPORT_BACKFILL_CANCELLATION_REQUESTED` |
| Maintenance | `TECHNICAL_DATA_RETENTION_COMPLETED` |
| Notification operations | `TELEGRAM_DELIVERY_RESEND_REQUESTED` |

Repeated rating finalization returns the existing snapshot and does not add a duplicate audit event.
Manual synchronous imports are recorded after a successful run; durable backfill jobs are recorded
when the job is created. Every accepted scheduled synchronization job is recorded when it is
enqueued. A backfill cancellation request is recorded once; repeating cancellation against an
already requested or terminal job is a state-preserving operation and adds no duplicate audit event.

## Metadata contract

The current metadata schema has `schemaVersion: 1`:

```json
{
  "schemaVersion": 1,
  "reason": "business reason when supplied by the command",
  "before": {
    "status": "CALCULATED",
    "revision": 1
  },
  "after": {
    "status": "APPROVED",
    "revision": 1
  }
}
```

Summaries are deliberately constructed by each use case. JPA entities and arbitrary objects are
rejected. Only nulls, booleans, numbers, bounded strings, UUIDs, enums, temporal values, maps and
bounded collections are accepted.

Keys containing `password`, `secret`, `token`, `authorization`, `cookie` or `credential`
are replaced with `[REDACTED]` at every nesting level. User summaries do not include email or
password data. Text, field count, collection size and serialized UTF-8 metadata size are bounded;
PostgreSQL independently enforces the 32 KiB metadata limit.

Financial events include the action, payroll aggregate or adjustment identifier, authenticated
author, store, database timestamp, command reason when present, and a safe state summary. They do
not serialize full entities, credentials or raw external payloads.

## Consistency and immutability

Business audit writes run in the same Spring transaction as the corresponding state change. A
failure to persist the event therefore rolls back that business command. Synchronous import events
are emitted only after the import has completed successfully.

The transaction also publishes an internal monitoring event. Its structured `BUSINESS_AUDIT` log
and bounded counter are emitted by an `AFTER_COMMIT` listener only after persistence succeeds, so
rolled-back commands do not create misleading off-host audit records. The external record uses the
shared SIEM `event_schema_version=1` envelope and an exact allowlist containing
`audit_category`, `target_ref` and optional `actor_ref`. It never exports the database row or
metadata document. This SIEM schema version is independent from `metadata.schemaVersion`, which
versions the stored action-specific metadata shape.

Flyway V10 installs database immutability protection. The only permitted updates are referential
cleanup performed by `ON DELETE SET NULL` for a removed actor or store. The audit action, target,
metadata, retention and timestamp remain unchanged. Production user removal is normally soft
deactivation, so actor links are retained in ordinary operation.

Flyway V12 adds explicit retention classes and administrative holds. Ordinary `DELETE` remains
forbidden. The maintenance transaction may delete only an expired non-financial row, only after
enabling a transaction-local database guard, and only when no active hold exists. The database
trigger rechecks all three conditions independently from application SQL. Financial history has no
deadline and cannot be deleted by retention.

The mapping, default lifetimes, hold SQL and rollout procedure are documented in
`data-retention.md`.

There is currently no public audit-log query API. Consumers should not read the table directly from
the frontend; a future read endpoint must enforce role and store scope and must expose a dedicated
response DTO.
