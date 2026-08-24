# Architecture Notes

Status: current application architecture, revalidated on 2026-08-24. The repository contains one
Spring Boot backend module and a React/Vite/TypeScript SPA. Frontend boundaries and actions are
documented in `FRONTEND_HANDOFF.md` and `frontend-actions.md`.

The backend has explicit runtime roles configured by `app.runtime.role`: `API` serves interactive
traffic without scheduled/background work, `WORKER` owns scheduled work and operational probes,
`COMBINED` preserves the single-process local-development topology, and `MIGRATION` runs Flyway
once in a minimal non-web context without JPA or schedulers. Unknown role values fail startup.
Every `@Scheduled` component is architecture-tested to allow only `WORKER` and `COMBINED`.
API/worker force runtime Flyway off and verify the packaged schema version through read-only JDBC.
The one-shot migrator applies validated lock/statement timeouts and bounded Flyway lock retries;
CI covers empty-schema and previous-version upgrade paths, while production-like lock timing remains
a staging acceptance concern. Production currently runs schema V44. V42–V43 add the durable
LiveSklad webhook inbox/processing state; V44 adds validated return recovery.

## First-stage boundaries

The project starts as one Spring Boot backend module inside a multi-project Gradle repository. This
keeps the first stage simple, while leaving room for future modules or services.

Current package boundaries:

- `auth`: DB login, process-local sessions, opaque self-service session revocation, password
  lifecycle, roles, store access and user administration.
- `store`: store-level settings and multi-store ownership.
- `employee`: normalized employees and store assignments.
- `product`: products, categories, analytics classification.
- `sales`: normalized sales documents and items.
- `integration.livesklad`: bounded LiveSklad client, return-webhook receiver/inbox, sale/order
  workers and validated recovery.
- `sync`: durable background sync runs, scheduling, idempotency, retry and cancellation.
- `metrics`: KPI and dashboard calculations.
- `performance`: store plans, work shifts, versioned employee-rating formulas and calculation.
- `salary`: salary calculations and approval workflow.
- `report`: immutable monthly/annual report finalization, aggregation, backfill and HTTP API.
- `manualinput`: manual indicators and adjustments.
- `audit`: immutable while retained, classed action journal with safe before/after summaries.
- `maintenance`: bounded technical-data retention, inventory rollups and operational metrics.
- `settings`: business settings for formulas and integrations.

## Package layout

The backend uses package-by-feature first and technical layers second:

```text
com.storeanalytics
|-- StoreAnalyticsApplication.java
|-- common
|   |-- config
|   |-- exception
|   |-- persistence
|   |-- security
|   `-- web
|-- <feature>
|   |-- model
|   |-- repository
|   |-- service       # only when the feature has use cases
|   `-- web           # only when the feature exposes HTTP endpoints
|-- integration
|   |-- connection
|   |   |-- model
|   |   `-- repository
|   `-- livesklad
|       |-- client
|       |-- dto
|       `-- exception
`-- sync
    |-- model
    |-- repository
    |-- service
    |-- web
    |-- exception
    `-- support
```

Package responsibilities:

- `model`: JPA entities, identifiers, enums, immutable value/input records, and domain state
  owned by the feature.
- `repository`: Spring Data repositories for the feature's models.
- `service`: application use cases and transaction orchestration.
- `web`: inbound HTTP adapter: controllers, REST request/response DTOs, and web mappers.
- `integration/*/client`: outbound HTTP adapters.
- `integration/*/dto`: transport models defined by the external system.
- `common`: only infrastructure genuinely shared by several features.

Do not create empty layer packages in advance. A feature that currently has only persistence
models needs only `model` and `repository`. There is no global `dto`, `mapper`, or `util` package:
DTOs and mappers stay next to their transport boundary, while reusable helpers use a package named
after their responsibility, such as `sync.support`. Global exception translation belongs to
`common.web`; feature exceptions remain inside their feature.

The HTTP error boundary is centralized. Expected domain failures extend `BusinessException` and
select a stable `BusinessErrorCode`; feature packages own concrete exception types. Validation,
security and framework errors use the shared `ApiErrorCode` catalog. Every request receives a
server-generated request ID in `X-Correlation-ID`, the error body and `request.id` logging MDC.
A valid incoming value is retained only as the separate untrusted `client.correlation_id` hint.
Unexpected exceptions are logged with their full stack trace and request ID, while clients receive
only `500 INTERNAL_ERROR`. Generic `IllegalStateException` and
`IllegalArgumentException` are deliberately not mapped to business statuses. See
`error-handling.md`.


Observability is implemented at stable boundaries. Feature-owned collectors cache operational
gauges instead of querying PostgreSQL during a metrics scrape. Synchronization services record
end-to-end timers, while one MVC interceptor aggregates KPI, payroll and report request latency with
bounded tags. Liveness has no dependencies; readiness includes application state, PostgreSQL and
Flyway, while cached LiveSklad health remains outside readiness to avoid restart loops caused by an
external outage. Build metadata is generated by Gradle and exposes no environment or source-control
details. See `observability.md`.

Runtime capacity is fail-closed as well as security. A validated `app.resources` boundary supplies
the actual Tomcat and Hikari properties, graceful shutdown is explicit, and every scheduled method
names a concurrency-one family scheduler. Sync execution/control, report work, external probes,
retention, cached metrics and cleanup cannot consume each other's scheduler thread. Backend
cardinality limits bound periods, pages, bulk commands and the largest unpaged work-schedule
response. A generic pre-security filter separately enforces the actual encoded byte size of every
`/api` request body, including chunked and understated-length requests. The staging SLO scenario
and database connection-budget equation are documented in
[resource-limits.md](resource-limits.md).

Technical retention is an infrastructure workflow under `maintenance`. It uses one transactional
advisory lock, bounded PostgreSQL batches and atomic aggregate-before-delete statements. Physical
deletion is deployment-disabled by default and additionally requires attributable policy, backup and
fresh restore-test references. LiveSklad JSON is projected through an entity-specific retained-field
allowlist before hashing/persistence; V18 distinguishes current policy payloads from legacy full raw.
The latest raw/sync identity, open quality evidence and all normalized financial/finalized snapshots
are protected; audit deletion is additionally enforced by an immutable retention class, deadline,
hold table and database trigger. Delete runs report remaining candidates for reconciliation. The job
never calls external systems. See `data-retention.md`.

Entity construction conventions:

- every entity keeps a protected no-argument constructor exclusively for JPA;
- application code uses a public constructor or a named public factory that establishes defaults
  and validates invariants before persistence;
- composite identifiers are ordinary constructible value objects and require persisted owner IDs;
- semantically related parameters are grouped into immutable records instead of long positional
  constructors;
- categorical fields constrained by the database are mapped to enums, with automated tests keeping
  their values synchronized with PostgreSQL `CHECK` constraints;
- timestamps owned by PostgreSQL remain non-insertable/non-updatable in JPA and use Hibernate
  `@Generated` so the current persistence context receives values produced by defaults and
  update triggers;
- every entity with `updated_at` is optimistic-lock protected by a `version bigint` column and
  JPA `@Version`;
- aggregate-wide replacements use one aggregate revision rather than combining child versions:
  work schedule owns a monotonic store/date revision, including a virtual empty revision 0;
- HTTP writes that can overwrite another user's edit require a strong ETag precondition; domain
  conflicts remain `409`, missing preconditions use `428`, and stale tags use `412`;
- money and quantity mappings declare the same precision and scale as PostgreSQL, and constructors
  reject implicit rounding or overflow;
- source/connection ownership is checked in model construction and, where direct SQL could bypass
  it, by foreign keys or PostgreSQL triggers;
- native `UPDATE` statements against versioned tables must not be introduced unless they also
  increment and check `version`; ordinary writes should go through repositories.

## Data layers

1. Raw external versions: deduplicated JSONB payload versions and stable external identifiers.
2. Normalized operational data: stores, employees, products, cash registers, sale/return documents,
   items, and payments.
3. Item snapshots: category and financial facts captured when a sale item is normalized.
4. Rating snapshots: explicitly finalized immutable employee-rating results with integrity hashes.
5. Report snapshots: immutable monthly revisions created after payroll payment and annual revisions
   composed only from exact monthly snapshots, with hashes and provenance.
6. Payroll revisions: monthly plan decision, daily pools, allocations with actual-hours snapshots,
   deductions, statements and audit.
7. Inventory trend rollups: daily summaries after exact history expires and indefinite monthly
   summaries after daily history expires.

Dashboard APIs must read from PostgreSQL, not directly from LiveSklad.
They read normalized tables, never raw JSON payloads.

KPI repositories use set-based SQL aggregation over immutable item category snapshots. Store,
employee and category views share the same signed fact rules for sales and returns. Category KPI
returns every reference category except `EXCLUDE`, including zero and inactive rows, and derives
the overlapping `PHONES`, `DEVICES` and `ADDITIONAL_REVENUE` groups from stable category flags.
Missing cost invalidates only the dependent category or group metrics; revenue and quantity remain
available.

Attach-rate is a separate set-based SQL projection. Its numerator requires a relevant device in the
same original sale document, while its denominator uses every signed relevant device fact in the
period. Return additions reuse their original sale as the pairing context. Mixed-condition warranty
sales and unknown device conditions are exposed as data-quality counters rather than guessed.

Average KPI is another set-based projection that reads current and immediately preceding equal-day
periods in one query. It exposes average receipt, additional revenue per phone and average unit price
for every reference category except `EXCLUDE`. Each metric retains its signed numerator and
denominator for auditability. Display values are rounded only after division, while dynamics use
unrounded averages; nonpositive denominators and a zero previous value produce no calculated value
instead of a misleading zero.

Store-plan progress is a read-only composition in the `performance` boundary over the effective
store plan, store/category KPI and store data status. Revenue is achieved by amount; accessory,
service and additional-revenue directions are achieved independently by their unrounded share of
actual store revenue. The response owns calendar pace, forecast, focus and data-quality semantics,
so clients render the result instead of reimplementing business formulas.

The `quality` feature provides two read-only frontend boundaries. Its store-wide overview combines
synchronization freshness and persisted open data-quality issues and is limited to active stores
accessible to the current user. Its period endpoint orchestrates source coverage, effective store
plan, snapshot-aware employee rating and payroll readiness/freshness for an explicit month and
cutoff date. Both expose stable codes, safe messages and recommended actions, never raw payloads,
issue metadata or upstream document identifiers. Specialized features continue to own their
formulas and blocking rules; the quality boundary only composes their results.

Employee rating uses a two-state lifecycle in the `performance` feature. Before finalization, the
query service calculates a `LIVE` result from normalized sales facts, store plans, immutable formula
versions and the manually maintained `0.01..11.00` actual-hours roster. Finalization is allowed only
after the period end in the store timezone and persists the exact JSON result plus SHA-256 under a
unique `(store_id, period_start, period_end)` key. A pessimistic store lock makes concurrent and
repeated finalization idempotent. Snapshot reads verify the hash and never call live aggregation.
Employee directory and card services use the same snapshot-aware query boundary, so historical
dynamics cannot bypass finalization. Payroll does not consume rating scores and keeps its confirmed
equal-per-participant daily allocation while snapshotting actual hours for audit.
Payroll calculation and source-version control share one `PayrollCalculationSourceData`: daily
fund inputs are aggregated from the same raw sale facts that feed component SHA-256 fingerprints.
Every run reports `CURRENT`, `STALE` or legacy `UNKNOWN` plus stable reason codes for sales/returns,
shifts, plan, classification and formula changes. Approval and payment fail with a dedicated `409`
until a stale run is explicitly recalculated; stored hashes remain an internal implementation detail.

High-risk payroll commands cross a transactional idempotency boundary before changing payroll.
A short-lived receipt is scoped to authenticated actor and opaque key, records action/resource and a
canonical request SHA-256, and stores the exact successful response in the same transaction as the
business mutation. A PostgreSQL transaction advisory lock serializes concurrent retries. Reuse for
a different identity fails with stable `409`; expired receipts are removed in bounded worker batches.

The report archive is a separate write model rather than a cache for dashboard queries. Paying a
payroll run atomically finalizes a monthly report revision that points to that exact payroll
revision. Annual finalization runs only for an ended calendar year, composes exact finalized monthly
revisions, and allows the store's first year to start at `reporting_started_on`. Corrections always
append a new immutable revision with a reason; neither the scheduler nor administrative backfill
rewrites an existing document.

Administrative report backfill is a durable `report_backfill_jobs` workflow, not an HTTP-bound
transaction. Twelve monthly steps and one annual step advance a persisted cursor under a database
lease. Each step locks the job row and commits snapshot creation together with cursor progress;
`FOR UPDATE SKIP LOCKED` coordinates replicas, while one partial unique index prevents concurrent
active backfills for the same store.

Store, employee, sale, and return synchronization use the same application boundary: all required
upstream pages/details are fetched and validated before one normalization transaction begins.
Vendor payloads are versioned in `raw_record_versions`; retries reuse identical hashes. A missing
business dependency may produce `PARTIAL_SUCCESS` and a retryable raw `SKIPPED` state, while
transport/protocol failures fail the run without partial normalized writes.

A durable `sync_jobs` orchestration layer runs those boundaries as short independent steps. It
stores the current phase and exclusive period cursor, uses a database lease for crash recovery,
prevents concurrent jobs for one connection, and links every child attempt through
`sync_runs.sync_job_id`. Source-capacity failures shrink the current window; transient transport and
rate-limit failures are retried with bounded backoff. Expired-lease recovery uses pessimistic locks
to close every still-running child attempt and record its generic recovery error in the same
transaction that advances the parent job. A partial unique index allows only one running child per
job; Flyway V20 repairs historical orphan/duplicate attempts before establishing the invariant.

## External integrations

Every external system should be added as an adapter under `integration/*`. The core metrics and salary services should depend on normalized data, not on vendor-specific API responses.

Implemented adapters:

- LiveSklad — normalized operational data source;
- YandexGPT — bounded weekly interpretation adapter with structured output;
- Telegram Bot API — account linking, webhook, durable fanout and delivery.

Planned separately: amoCRM conversation analytics and MAX integration.
