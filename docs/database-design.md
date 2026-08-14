# Database design

Status: current through Flyway V25, revalidated on 2026-08-02. The schema is authoritative; frontend
must use API DTO and never infer database relations directly.

## Confirmed scope

- Two LiveSklad stores are included; all three managers can access both stores.
- Reporting uses `Europe/Kaliningrad`, calendar days from `00:00` to `23:59`, and store hours
  `10:00` to `21:00`.
- Initial sales history starts on `2026-01-01`.
- Repair orders are outside the first-stage sales KPI.
- Returns are recognized on the return date and attributed to the original seller.
- Product category changes apply only to future sales.

## Data layers

### Raw versions

`raw_record_versions` stores a JSONB payload version for replay and diagnostics. An unchanged
entity is deduplicated by connection, optional store scope, entity type, external identity, and
SHA-256 payload hash. Employee payloads are store-scoped because each response also proves store
membership, while the normalized employee identity remains company-wide. Raw data is never queried
by dashboard services.

Sales raw versions are store-scoped and contain the exact list/detail pair used for normalization.
All active stores and every required document detail are fetched before the normalization
transaction starts. This prevents a late upstream failure from leaving a partially refreshed
period. A changed pair creates a new raw version; an unchanged pair only advances its last-seen
run.

Return synchronization stores the cash-item dictionary as a company-scoped raw version, cash
registers as store-scoped raw records, and each return as the deterministic combination of all cash
transactions referencing that document plus its detail. Active details are fetched only after the
70-document ceiling is checked; deleted-only groups need no detail. An unresolved original sale
keeps the raw version as `SKIPPED` and opens a data-quality issue, so the identical version can be
normalized on a later run instead of being discarded.

`sync_jobs` is a durable orchestration cursor, not another fact layer. It stores the requested
period, current phase/window, bounded retry state and worker lease. Every concrete attempt remains
an auditable `sync_runs` row linked by `sync_job_id`. A partial unique index permits only one
non-terminal job for an integration connection. Flyway V20 repairs historical child attempts left
`RUNNING` after their parent job became non-running, closes older duplicate attempts, and enforces
at most one `RUNNING` child attempt per job. Runtime lease recovery locks the parent and its running
children in one transaction, closes interrupted attempts as `FAILED`, records
`SYNC_WORKER_LEASE_EXPIRED`, and only then moves the parent to retry or its terminal state.

Flyway V12 adds bounded technical-data retention. Superseded raw versions and terminal sync
history age out in status-specific batches, while the newest terminal state and maximum
SALES/RETURNS data-through boundary are retained. Open quality evidence is protected. Provenance
foreign keys become nullable with `ON DELETE SET NULL`, so cleanup never removes business facts.
V13 enforces a 16 MiB database safety ceiling for retained raw JSON. V14 persists resumable report
backfill jobs, V15 adds bounded-list indexes, V16 adds short-lived transactional idempotency
receipts, and V17 adds the aggregate revision used for optimistic replacement of a complete
store/day schedule. These operational structures are application infrastructure, not dashboard facts.
Flyway V18 records whether a raw row uses the current retained-field privacy allowlist or is legacy
full vendor JSON, allowing aggregate inventory and controlled cutover without exposing the payload.
Flyway V19 permits exactly one transition from a provisional LiveSklad catalog identity to the
authoritative observed product/service identity while keeping every established source identity
immutable. Flyway V20 repairs historical orphan or duplicate running sync attempts and adds a
partial unique index allowing at most one `RUNNING` child attempt per durable job.

### Normalized operational data

Integration connections, stores, users, employees, products, documents, items, and payments are
normalized into relational tables. Products and employees are company-wide within one connection
because LiveSklad exposes the same external IDs in multiple stores. Store membership and stock are
represented by junction tables.

`store_performance_plans` stores monthly targets per store; there are no employee-specific plans.
Its JPA version is exposed as a strong ETag for conditional updates. `employee_work_shifts` stores
actual `0.01..11.00` hours for each worked day and preserves inactive entries for audit.
`work_schedule_day_revisions` is the concurrency aggregate for the complete store/date roster:
revision 0 is the virtual empty day, the first replacement persists revision 1, and every later
replacement advances it under the store lock. `rating_schemes` stores immutable effective-dated
formula versions.
`employee_rating_snapshots` stores explicitly finalized results for exact store/date periods. The
business payload is append-only, has a SHA-256 integrity hash, author name snapshot and a unique
`(store_id, period_start, period_end)` key. Periods without a snapshot remain live calculations.

Payroll is an audited snapshot layer. `payroll_schemes` contains immutable month-effective formula
versions, product overrides refine safe category defaults, and each `payroll_run` stores one store
month revision with its plan decision and quality counters. Daily pools, equal employee allocations,
manual deductions, final statements and lifecycle events are persisted. Approved or paid revisions
are never recalculated in place.
Migration `V9` adds five component SHA-256 fingerprints to every newly calculated run: sales and
returns, shifts, plan, effective classification and payroll scheme. Existing rows may keep all
fingerprint columns null and are reported as legacy `UNKNOWN`; partial fingerprints are rejected by
a database constraint. Approval/payment require a current complete fingerprint.

Migration `V21` adds the internal monotonic `calculation_generation`. Every in-place recalculation,
including recalculation after an adjustment is added or voided, advances it and therefore advances
the JPA optimistic `version` even when all source fingerprints and monetary totals are unchanged.
Clients can consequently use `run.version` as a reliable concurrency token for payroll mutations.

Flyway V22 adds immutable weekly analytics snapshots, durable snapshot jobs, provider-neutral LLM
jobs, bounded provider attempts and immutable published interpretations. V23 adds the Telegram
notification outbox. V24 indexes the crash-safe snapshot-to-LLM reconciliation handoff. V25
enforces at most one unfinished `STARTED` or `RESPONSE_RECEIVED` provider attempt per LLM job.

V26 adds idempotent event fan-out receipts and V27 records Telegram membership updates without
retaining arbitrary incoming messages. V28 separates short-lived link-confirmation deliveries from
business notifications.

V29 adds explicit manual-delivery provenance: `manual_resend_of`, `requested_by` and
`resend_reason`. A database trigger permits this shape only for an unexpired terminal business
delivery requested by an active administrator, and requires an exact copy of all delivery content,
recipient, subscription, event, TTL and retry-policy fields. The source row remains immutable.

V30 preserves the exact UTF-8 bytes of finalized report JSON by storing validated JSON text instead
of normalizing it through `jsonb`; existing hashes are re-anchored once during migration.
V31 separates the immutable raw provider response from the canonical response accepted by backend
validation. `llm_analysis_attempts.validated_response_body` and `validated_response_hash` are
written atomically only for successful validation; publication refuses raw or hash-mismatched data.
V32 applies the customer-confirmed CARE split (`ELITE`/`PRIVILEGE` warranty,
`Check Premium`/`ULTIMATE` protection), renames the protection label and repairs affected normalized
source facts. Finalized reports and published LLM interpretations remain immutable.
V35 applies the customer-confirmed monetary accessory correction for the exact 14-product scope,
repairs effective assignments and normalized item category snapshots, and preserves original
sale/return amounts. Finalized reports remain immutable.

`audit_log` is the unified command history. Flyway V10 makes actor and store references nullable
on later hard deletion and bounds safe versioned metadata to 32 KiB. Flyway V12 assigns immutable
retention classes/deadlines and permits only guarded deletion of expired non-financial entries
without an active hold. All other update/delete attempts remain rejected. See `audit-log.md` and
`data-retention.md`.

### Immutable snapshots

Every sale item stores the analytics category, condition, product name, amounts, and cost quality
used when it was normalized. Later product reclassification does not rewrite old items.
`employee_rating_snapshots` makes a finalized rating independent from later sales, returns, shifts,
plans, ranking eligibility and formula calibration. There is no update/delete application API;
reads validate the stored hash before deserialization.

`report_snapshots` stores immutable finalized monthly and annual report revisions. A monthly
snapshot is created atomically with `PAID` payroll and references that exact payroll revision.
An annual snapshot references the exact monthly revisions through `annual_report_months`; the
first calendar year may start at `stores.reporting_started_on`. Source and exact serialized-payload
SHA-256 hashes, schema/template versions and revision provenance make every document independently
verifiable. The immutable payload uses validated JSON `text`, not `jsonb`, because byte-level
integrity must survive a database round-trip without key-order or whitespace normalization.
Migration V30 converts existing JSONB documents, re-anchors their hashes once, and leaves the
finalized-row immutability trigger enabled afterward. PostgreSQL validates paid-payroll ownership,
calendar boundaries and annual provenance;
triggers reject update/delete of finalized snapshots and annual links. Ordinary dashboard reads
remain dynamic and are not persisted. See `reports.md`.

## Main invariants

- External entities have a unique `(connection_id, external_id)` identity.
- Manual entities without an external connection use `(source_system, external_id)`.
- Connection/source and store/connection pairs are protected by composite foreign keys. Conditional
  manual-or-same-connection inventory and employee assignment relations are protected by model
  validation and PostgreSQL triggers.
- Employee external identity is mandatory and immutable. One LiveSklad employee can be assigned to
  multiple stores of its connection; a manual employee can be assigned to any store.
- A complete employee sync fetches every active store before normalization. Missing assignments
  and employees are deactivated only after all source pages have been fetched successfully;
  reappearing records are reactivated without resetting application-owned ranking eligibility.
- Sales synchronization uses an explicit period of at most 31 days and a safe ceiling of 70 detail
  requests per synchronous run. The ceiling is checked before any detail request or fact write.
- Sale list/detail identity, document number, timestamp, type, and store must agree before
  normalization.
- Products are identified by connection and LiveSklad nomenclature ID. A historical backfill cannot
  overwrite product attributes observed in a newer sale.
- Source corrections update documents, items, and payment components in place with optimistic
  locking. Missing facts are soft-deleted and reappearing facts are reactivated.
- Return cash transactions are grouped by document ID, so mixed payment components fetch and
  normalize one detail. Return amounts stay positive; `document_kind = RETURN` supplies the metric
  sign. The return document uses the original sale employee, and linked items copy the original
  classification snapshot.
- Explicit return deletions carry their source timestamp and raw version. Older delete/active
  payloads cannot overwrite newer document state; period-level absence still uses soft deletion.
- Every table with an `updated_at` column uses a monotonic database trigger as the timestamp owner;
  Hibernate reads generated timestamps back into the current persistence context.
- All nineteen mutable aggregates have a `version bigint` column mapped with `@Version`; native
  writes to those tables are forbidden unless they also implement the optimistic-lock protocol.
- Java enum mappings and PostgreSQL `CHECK` value sets are kept identical.
- Quantity and money fields use explicit `numeric(19, 3)` and `numeric(19, 2)` contracts in both
  JPA and PostgreSQL; model constructors reject values that PostgreSQL would round or overflow.
- Category assignment time ranges for one product cannot overlap.
- A return document must reference an original sale, and a return item may reference its original
  sale item.
- Inventory observations cannot be updated after insertion. Expired exact rows are atomically
  rolled into daily summaries, expired daily rows into indefinite monthly summaries, and source
  rows are deleted only in the same successful transaction.
- Zero cost is valid for services and is a data-quality issue for other categories.
- Deleted documents do not participate in metrics.
- A store and exact rating period have at most one finalized snapshot; finalization is serialized by
  a pessimistic store lock and repeated requests return the existing row.
- A rating snapshot payload must be a JSON object and its lowercase SHA-256 must contain 64 hex
  characters; application reads also verify header identity, period and formula code.
- A monthly report revision references one exact `PAID` payroll revision; an annual revision
  references the exact finalized monthly revisions required by its store/year boundary.
- Finalized report payloads and annual provenance links are append-only. A correction creates a new
  revision with a reason and never updates an existing document.
- Dashboard periods remain dynamic and are not inserted into the report archive.
- Unknown categories use `UNMAPPED`; excluded products use `EXCLUDE`.
- High-risk command receipts are unique by `(actor_id, idempotency_key)`, retain only the canonical
  request SHA-256 rather than the request body, and store the exact successful response until a
  bounded expiry. The receipt and payroll mutation commit or roll back together.
- Notification events are immutable business/operator facts. Ordinary `NOTIFICATION` deliveries
  must reference one event and an `ACTIVE` subscription owned by the recipient.
- Lifecycle service messages share the same leased/retryable outbox but are distinguished by
  `delivery_kind`. A `LINK_CONFIRMATION` has no event, is valid only for its recipient's
  `PENDING_CONFIRMATION` subscription and is unique per subscription.
- Exact Telegram text and its SHA-256 are persisted before the provider call. Confirmation,
  revocation and bot-unavailable transitions cancel waiting deliveries or set
  `cancel_requested` on a leased delivery; the worker repeats eligibility immediately before
  creating the provider attempt.

## Deliberate validation boundaries

- Cross-row reconciliation of document, item, and payment amounts is performed by backend services.
  Mismatches are retained for diagnostics and registered as data-quality issues instead of rejecting
  the entire external synchronization transaction.
- `store_product_inventory` is the current-state projection, while
  `store_product_inventory_history` is its append-only sequence of observed stock states.
- Integration credentials and access tokens are not stored in PostgreSQL. A connection stores only
  a safe credential-source reference; credentials remain in environment variables or secret storage.

## DB/JPA contract verification

Automated tests apply V1 through V25 to a fresh PostgreSQL 16 database and verify all tables,
entities, and repositories. They compare every physical column with Hibernate metadata, compare
enum mappings
with PostgreSQL `CHECK` constraints, compare numeric precision/scale, and verify generated
timestamps and optimistic locking. A full application graph is persisted through public model APIs.

Negative integration tests also prove that PostgreSQL rejects cross-connection references, invalid
inventory ownership, and updates to inventory history. Cross-row document/item/payment
reconciliation intentionally remains a synchronization/service concern because inconsistent source
facts must be retained and reported as data-quality issues.

Sales integration tests additionally prove list/detail HTTP mapping, pagination, idempotent
normalization, raw-version reuse, corrections, soft deletion and reactivation, data-quality issue
resolution, full rollback on a detail failure, capacity rejection before detail reads, business
date conversion, and protection against stale product backfills. Web tests cover the public
success and sanitized 400/422/502 error contracts.

Return integration tests additionally cover cash-item/register/transaction/detail HTTP mapping,
original document and item links, original-seller attribution, classification snapshot reuse,
idempotency, explicit deletion and reactivation, skipped-to-normalized recovery when a return
arrives before its sale, and rollback before any cash-register/raw write on a detail failure.

Authentication integration tests cover database-backed email login, server sessions, CSRF cookie
rotation, forced temporary-password replacement, role checks, store-scoped manager access, first
administrator safety rules and immediate invalidation of stale security sessions. Synchronization
job tests cover phase progression, connection-level exclusion, cancellation, child-run linkage,
adaptive windows, retries, rate-limit reserve, and sanitized failures.

## Retention verification

Integration tests prove atomic daily/monthly rollups, superseded-only raw cleanup, open
quality evidence protection, latest terminal run/job preservation, status-specific technical
purges, financial audit preservation, active holds, orphan sync-run repair and payroll optimistic
version advancement. Migration tests build an empty database through V28 and exercise representative
upgrades, verifying checksums, retained data, backfills and the resulting application model.

## Deferred modules

Standalone repair analytics remains deferred. Weekly AI analysis and the Telegram notification
outbox/linking lifecycle are implemented in V22-V28. Store plans, shifts and the versioned
employee-rating formula are implemented in V4; the confirmed payroll workflow is implemented in
V5, actual shift hours in V7, source freshness in V9, and recalculation concurrency tokens in V21.
