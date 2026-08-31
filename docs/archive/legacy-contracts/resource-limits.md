---
doc_schema: 1
doc_type: archive
status: archived
owner: operations
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "docs/current/architecture/resource-limits.md"
original_content_sha256: 2fbbc7f1945f0dc796ab0f30a8a72ecdd54fdf368cc1d830574e9ec54f0e0805
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/architecture/resource-limits.md`.

# Runtime resource limits and background bulkheads

Status: backend limits implemented on 2026-07-27; production-sized load evidence remains a staging
acceptance gate.

## Runtime budgets

`app.resources` is the single validated source for the embedded Tomcat and Hikari settings. Spring
Boot's native properties reference these values, so a deployment cannot tune the application value
without tuning the actual server or pool. Startup fails when a value is non-positive, exceeds a
safety ceiling, or violates a relationship such as `minimumIdle <= maximumPoolSize`.

The checked-in defaults are deliberately conservative:

| Boundary | Default |
| --- | ---: |
| HTTP request headers | 8 KiB |
| API request body (actual bytes) | 2 MiB |
| Form POST / swallowed body | 2 MiB / 2 MiB |
| Connection / keep-alive timeout | 10 s / 20 s |
| Connections / accept queue | 512 / 100 |
| Request threads / queue / minimum spare | 64 / 128 / 8 |
| Keep-alive requests / parsed parameters | 100 / 256 |
| Hikari maximum / minimum idle | 10 / 2 |
| Hikari acquisition / validation timeout | 5 s / 3 s |
| Hikari idle / maximum lifetime / keepalive | 10 min / 30 min / 2 min |
| Graceful shutdown phase | 45 s |

Production API starts with a maximum pool of 10. A split worker must set `DB_POOL_MAX_SIZE=5` and
normally `DB_POOL_MIN_IDLE=1`, matching the database budget in
[deployment-and-operations.md](deployment-and-operations.md). For `N_api` API replicas and
`N_worker` workers, enforce:

```text
N_api * API_POOL_MAX + N_worker * WORKER_POOL_MAX + migration/backup/monitoring reserve
    <= 50% of the PostgreSQL provider connection limit
```

The remaining half is an operational reserve, not spare application capacity. Pool and Tomcat
values may be changed only together with staging measurements, PostgreSQL connection limits and
container CPU/memory envelopes.

Graceful shutdown stops new request acceptance and gives active lifecycle components 45 seconds.
Each scheduler waits at most 30 seconds for its current task. A task that can exceed the deployment
termination grace period must remain resumable through its durable job/lease protocol.

## Scheduler bulkheads

Every `@Scheduled` method names a scheduler explicitly. An architecture test rejects a new method
that falls back to Spring's shared scheduler.

| Scheduler | Concurrency | Work owned |
| --- | ---: | --- |
| `syncWorkerScheduler` | 1 | Durable LiveSklad synchronization job step |
| `syncControlScheduler` | 1 | Scheduled incremental-job creation |
| `reportBackfillScheduler` | 1 | Durable report-backfill job step |
| `annualReportScheduler` | 1 | Annual report finalization scan |
| `liveSkladProbeScheduler` | 1 | External availability probe |
| `retentionScheduler` | 1 | Retention rollup/deletion run |
| `metricsScheduler` | 1 | Cached database metric refreshes |
| `cleanupScheduler` | 1 | Login-throttle and idempotency cleanup |

The single-thread setting is an intentional bulkhead: no family overlaps with itself, and a blocked
external probe cannot consume report, retention, metrics, cleanup or synchronization capacity. The
separate sync control scheduler ensures a slow upstream job step cannot prevent the next durable job
from being enqueued. PostgreSQL advisory locks and durable claim/lease rules remain the cross-replica
concurrency control; thread pools alone are not distributed locks.

## API cardinality limits

- General list endpoints use `PageParameters`: page size `1..100`, page `0..10000`.
- Sync/report job lists accept `limit=1..100`.
- Analytics periods are at most 366 inclusive days.
- Work-schedule reads are at most 31 inclusive days and 10,000 fetched rows. A daily replacement
  accepts at most 500 shifts; the service repeats the DTO check so non-HTTP callers cannot bypass it.
- Payroll bulk classification accepts at most 500 assignments and a 2,000-character reason, in both
  DTO and service boundaries.
- Report backfill permits at most one active job per store and a validated global active-job limit.
- Sync backfill duration, LiveSklad pages/records/positions, raw bytes and JSON complexity have
  separate validated ceilings documented in [synchronization-api.md](synchronization-api.md) and
  [security-hardening.md](security-hardening.md).

`RequestBodyLimitFilter` enforces the generic API body boundary before Spring Security. A declared
`Content-Length` above the limit is rejected before downstream processing. Requests with unknown,
chunked or understated length are wrapped in a byte-counting stream and rejected as soon as byte
`limit + 1` is observed. The limit is measured on encoded bytes, cannot be bypassed through
`InputStream.skip`, and applies to `/api` independently from the domain DTO constraints.

Both rejection paths return HTTP `413` with code `PAYLOAD_TOO_LARGE`, the standard `ApiError`
shape, the authoritative correlation ID, `Cache-Control: no-store` and
`X-Content-Type-Options: nosniff`. The response never includes the configured limit, declared
length or request content. `maxSwallowSize` must be at least both the generic body limit and form
limit so the container can safely discard a rejected body; startup validation enforces this
relationship. Caddy's public request-body ceiling must be equal or stricter, while this backend
boundary remains mandatory for direct/internal traffic.

JSON endpoints also retain domain-specific item/string/period constraints. Any future multipart,
generic upload or asynchronous streaming endpoint needs its own storage/part/stream policy and
tests before the route is enabled; this JSON/form boundary must not be treated as an upload quota.

## Reproducible staging load test

The k6 scenario is [production-readiness.js](../../../backend/load-tests/production-readiness.js). It runs
the typical-day dashboard, monthly payroll, annual report and sync control plane concurrently. The
default acceptance thresholds are:

- typical day and sync control: p95 < 500 ms, p99 < 1 s;
- monthly payroll: p95 < 1 s, p99 < 2 s;
- annual report: p95 < 1.5 s, p99 < 3 s;
- HTTP failure rate below 1% in every scenario.

Run only against an isolated staging copy with production-like cardinality. Create one dedicated
load-test account per VU because normal sessions are concurrency-limited. Store the following JSON
outside the repository in a mode-0600 file; never use real employee credentials:

```json
[
  {"email":"load-user-01@example.invalid","password":"generated-test-secret"}
]
```

Example invocation (the credential file path is not secret, its contents are):

```bash
k6 run \
  -e LOAD_BASE_URL=https://staging.example.invalid \
  -e LOAD_STORE_ID=00000000-0000-0000-0000-000000000000 \
  -e LOAD_DAY=2026-07-01 \
  -e LOAD_PAYROLL_MONTH=2026-07 \
  -e LOAD_REPORT_ID=00000000-0000-0000-0000-000000000000 \
  -e LOAD_TEST_USERS_FILE=/run/secrets/store-load-users.json \
  backend/load-tests/production-readiness.js
```

Before starting, seed the selected day, a complete monthly payroll and a finalized annual report.
To exercise a real sync storm, run an active synchronization job concurrently; the k6 scenario then
proves that the administrative control plane and ordinary reads keep their SLO while the worker is
busy. Capture k6 JSON output, Hikari active/pending/max, Tomcat busy/max threads, JVM/CPU/memory,
PostgreSQL connections/locks/slow queries and worker queue age in the release evidence. A local
empty-database run is not production acceptance evidence.
