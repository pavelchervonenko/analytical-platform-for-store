# LiveSklad synchronization orchestration

Status: implemented ADMIN-only API, revalidated on 2026-07-31. Concrete buttons, polling and
cancellation rules are in `docs/frontend-actions.md`.


Synchronization jobs are durable database records processed by a background worker. A historical
backfill is never tied to the lifetime of one HTTP request and can resume after an application
restart.

## Job lifecycle

Each job runs these phases in order:

1. `STORES` refreshes the store dictionary.
2. `EMPLOYEES` refreshes employees and store assignments.
3. `SALES` synchronizes one bounded time window.
4. `RETURNS` synchronizes the same window after its sales.
5. `ORDERS` synchronizes issued order positions changed in the same window.
6. The cursor advances and phases 3–5 repeat until the exclusive period end.

The default data window is one day. If a window exceeds the existing 70-detail safety ceiling, the
worker halves it down to a minimum of 15 minutes before declaring it impossible. A failed process
lease becomes retryable after expiration; repeated normalization is safe because child pipelines
are idempotent and raw payloads are hash-deduplicated.

Only one non-terminal job may exist for one integration connection. A job retains only sanitized
failure summaries; detailed child attempts and their counters remain in `sync_runs`, linked through
`sync_runs.sync_job_id`.

Expired-lease recovery is atomic. The coordinator locks the expired parent and every linked
`RUNNING` child attempt, marks interrupted children `FAILED` with a bounded generic summary, writes
`SYNC_WORKER_LEASE_EXPIRED` diagnostics, and then moves the parent to `WAITING_RETRY`, `FAILED`, or
`CANCELLED` according to its retry/cancellation state. A database partial unique index prevents a
job from owning two running child attempts. Flyway V20 repairs historical violations before
creating that index.

## Administrative API

All endpoints require an authenticated `ADMIN` session and a valid CSRF token for unsafe requests.

```text
POST /api/sync/jobs/backfill
GET  /api/sync/jobs?limit=20
GET  /api/sync/jobs/{jobId}
POST /api/sync/jobs/{jobId}/cancel
```

Backfill request dates are inclusive calendar dates in `Europe/Kaliningrad`:

```json
{
  "periodStart": "2026-01-01",
  "periodEndInclusive": "2026-01-03"
}
```

Creation returns `202 Accepted`. Processing is asynchronous; poll the job endpoint. Cancellation is
immediate for queued/retrying jobs and cooperative for a currently running phase. A running source
request is allowed to finish, then the job becomes `CANCELLED` without starting another phase.
Calling cancel for a terminal `SUCCESS`, `FAILED` or `CANCELLED` job is idempotent and leaves its
terminal state unchanged. `GET /api/sync/jobs` accepts `limit=1..100` and defaults to 20.

## Runtime configuration

Safe defaults are defined in `application.yml`:

```text
SYNC_WORKER_ENABLED=true
SYNC_WORKER_DELAY=5s
SYNC_WINDOW_SIZE=6h
SYNC_MAX_ATTEMPTS=5
SYNC_LEASE_DURATION=2h
SYNC_RETRY_INITIAL_DELAY=1m
SYNC_RETRY_MAX_DELAY=15m
SYNC_INCREMENTAL_OVERLAP_DAYS=3
SYNC_MAXIMUM_BACKFILL_DAYS=730
```

The worker is enabled by default. Automatic daily job creation is deliberately disabled until
deployment:

```text
SYNC_SCHEDULE_ENABLED=false
SYNC_SCHEDULE_CRON="0 15 3-8 * * *"
SYNC_SCHEDULE_ZONE=Europe/Kaliningrad
```

When scheduling is enabled, the worker checks the same incremental window hourly from 03:15
through 08:15. The first successful check creates the job; checks made while a job is active are
idempotent no-ops. A later checkpoint recreates the same rolling window only after an earlier job
has reached a terminal state. This gives a rate-limited or restarting worker additional recovery
opportunities while keeping completed-day data available during the morning.

Every created job re-reads the last three completed calendar days. The job persists progress after
each six-hour child window, so a later provider failure does not discard earlier windows. A
LiveSklad rate limit in a sales, returns, or orders phase halves the current child window down to the
15-minute floor and schedules the retry no earlier than the provider `Retry-After` boundary.
Permanent payload or configuration failures still require operator intervention. The rolling
overlap captures late corrections without duplicating normalized facts.

The return discovery feed can include a cash adjustment whose referenced document resolves to an
ordinary `sale`, not a `saleReturn`. Such a record is retained as sanitized raw evidence and skipped
with `PARTIAL_SUCCESS`; it must not create or mutate a return fact. The sale document can legitimately
predate the cash-transaction child window, so only the cash transaction is required to be inside that
window. A real `saleReturn` detail is still required to fall inside the requested child window.

## Source request budget

The client observes LiveSklad `remainRequest` and `expireDate`. It preserves five requests as a
reserve and postpones the current phase until the reported window resets. An HTTP `429` is treated
as retryable. For sales, returns, and orders, the persisted child window is reduced before retry and
the
delay is the greater of local backoff and the source `Retry-After`, capped by the configured
absolute maximum. Tokens, credentials, full payloads, customer data, and upstream response bodies
are never written to job errors or logs. Order detail synchronization uses the same 70-record safety
ceiling and window reduction policy.

## Initial backfill

Build and test the mechanism locally on one or two days first. After deployment, create the full
job for `2026-01-01` through the latest completed day and monitor it through `GET /api/sync/jobs`.
Because every completed child run is committed independently, restarting the backend does not
restart the backfill from the beginning.
