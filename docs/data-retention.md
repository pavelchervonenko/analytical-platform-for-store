# Technical data retention

Status: implemented through Flyway V18 and revalidated on 2026-07-27.

## Purpose and safety model

The retention subsystem bounds technical tables without changing financial or finalized business
history. It is a scheduled backend maintenance workflow, not a replacement for database backups,
point-in-time recovery or disaster-recovery procedures.

Physical deletion is disabled by default. With `RETENTION_DELETION_ENABLED=false`, the scheduled
run calculates candidates, writes bounded operational logs and records an audit event, but does not
aggregate or delete rows. Enabling deletion is an explicit deployment decision. The application now
fails closed unless the configuration also carries an approved policy reference, a backup checkpoint
reference and a restore-test timestamp no older than the configured maximum age. These references
make the decision attributable; they do not independently prove that the external procedures ran.

One PostgreSQL transaction-scoped advisory lock prevents concurrent runs across application
instances. Every enabled run processes bounded batches with `FOR UPDATE SKIP LOCKED`. A failure
rolls back the complete run; later schedules continue draining the backlog.

## Default policy

| Data | Default | Important protection |
| --- | --- | --- |
| Normalized `raw_record_versions` | 180 days | Delete only a superseded version; always keep the latest version of an external entity. |
| Failed or skipped raw versions | 365 days | Same latest-version rule; raw sale/return evidence linked to an open quality issue is retained. |
| Successful terminal `sync_runs` | 90 days | Keep the latest terminal run and the maximum successful/partial SALES/RETURNS data-through boundary for each identity. |
| Partial, failed or cancelled `sync_runs` | 365 days | Keep the same terminal/data-through invariants; child errors are removed only with an eligible run. |
| Successful terminal `sync_jobs` | 90 days | Keep the latest terminal job for each connection/job type. |
| Failed or cancelled `sync_jobs` | 180 days | Keep the latest terminal job for each connection/job type. Active jobs are never candidates. |
| Exact inventory observations | 13 calendar months | Atomically aggregate to a daily row before deleting exact observations. |
| Daily inventory rollups | 3 calendar years | Atomically aggregate to a monthly row before deleting daily rows. |
| Monthly inventory rollups | Indefinite | Long-term trend layer; no automatic purge. |
| Resolved or ignored quality issues | 365 days after resolution | Open issues are never candidates. |
| Audit: security | 5 calendar years | An active retention hold prevents deletion. |
| Audit: business | 3 calendar years | An active retention hold prevents deletion. |
| Audit: operational | 1 calendar year | An active retention hold prevents deletion. |
| Audit: financial | Indefinite | Database trigger forbids retention deletion. |

Deleting eligible sync provenance does not delete normalized facts. Flyway V12 changes provenance
foreign keys to `ON DELETE SET NULL`; business identity and values remain intact. Repository
integration tests delete an eligible raw sale version and verify that the normalized sale document
and its financial amounts remain, with only the optional raw pointer cleared. Current inventory in
`store_product_inventory` is not part of the history rollup.

The following data is deliberately outside automatic retention:

- sale/return documents, items and payments;
- payroll schemes, runs, events, adjustments, statements and source fingerprints;
- finalized employee rating snapshots;
- monthly and annual report snapshots and their exact provenance links;
- financial audit events;
- monthly inventory rollups.

These normalized sale/return facts, payroll source fingerprints, immutable payroll/report payloads
and exact monthly-to-annual links are the source evidence required by finalized financial snapshots.
They have no automatic deletion path, while finalized reports themselves are database-immutable and
indefinite. Therefore technical raw/sync cleanup cannot shorten the lifetime of evidence required to
verify a finalized snapshot. A future archival policy must preserve that invariant and must not
silently turn immutable evidence into ordinary disposable technical data.

## Retained LiveSklad payload privacy

LiveSklad responses are no longer stored wholesale. Before the byte limit, SHA-256 and database
write, `RawPayloadPrivacyFilter` projects each payload onto a closed, entity-specific shape. Unknown
fields are omitted recursively. A retained field with an unexpected object/array shape is rejected
without echoing its value. Hash deduplication is intentionally computed over the retained projection,
so a change confined to an unapproved vendor field does not create a new raw version.

| Profile | Retained evidence | Intentionally excluded |
| --- | --- | --- |
| Store | source ID, name, address, color | every other shop field |
| Employee | source ID and display name | contacts, credentials and other customer fields |
| Sale document | list identity/time/type/totals; detail identity/time/type, seller ID/name, shop ID, payment split and normalized position inputs | unknown customer/contact, metadata, internal/debug fields |
| Cash item dictionary | ID, name, type, income/balance flags | all unconsumed dictionary fields |
| Cash register | ID, name and shop ID | all unconsumed register fields |
| Return document | detail identity/time/type, processing employee ID, shop/original-sale links, payment split and position inputs; cash-transaction identity/relations/amount flags | unknown relation attributes, contacts, metadata and debug fields |

Names and the store address remain because they are normalized business evidence displayed in
historical employee/store/report views. Product names, identifiers, quantities, prices, costs,
payment splits and document relations remain because they participate in normalization, payroll,
returns, data-quality investigation or financial reconciliation. Authentication tokens, credentials,
contact attributes and all fields not listed above have no analytical purpose and are not retained.

Flyway V18 adds `payload_policy_version`. Version `1` means the allowlist was applied; version `0`
identifies legacy full vendor JSON created before this policy. A matching legacy row is promoted to
version 1 and its JSON replaced by the retained projection when it is seen again. Dry-run output
includes `legacy_raw_payload_versions`, allowing an existing installation to track the remaining
legacy inventory without exposing payload values. A legacy latest version is not deleted merely
because it is legacy: it remains protected until a newer retained version exists or an explicitly
approved migration/archive process handles it.

## Inventory rollups

Detailed observations are grouped by store, product and business date in the configured retention
zone. A daily row preserves opening, closing, minimum and maximum quantity, closing retail/cost
amounts, whether stock reached zero, observation count, and first/last observation timestamps.

Daily rows are later grouped by calendar month. A monthly row preserves opening, closing, minimum
and maximum quantity, closing prices, observed days, days with stockout, total observation count,
and first/last observation timestamps.

Each rollup is one PostgreSQL statement: aggregate/upsert succeeds before its source rows are
deleted in the same transaction. Repeated execution is safe. Daily retention must exceed
`SYNC_MAXIMUM_BACKFILL_DAYS`; the service refuses to run if configuration could discard the
resolution needed by the permitted backfill window.

## Audit retention classes and holds

`AuditRetentionPolicy` assigns a class when an event is created. The class and deadline are stored
on the immutable audit row.

- `FINANCIAL`: plans, shifts, payroll formulas/classification/calculation/lifecycle, finalized
  monthly/annual reports and report backfill requests.
- `SECURITY`: user creation, user changes, store-access changes and password resets.
- `BUSINESS`: rating participation/finalization/formulas and analytics classification.
- `OPERATIONAL`: manual sync, sync cancellation and retention-run completion.

New `AuditAction` values must be added explicitly to the exhaustive Java switch; this prevents a
new command from inheriting an accidental default lifetime.

An authorized database administrator may place a documented legal/investigation hold:

```sql
INSERT INTO audit_retention_holds (audit_log_id, reason, placed_by)
VALUES (:audit_log_id, :reason, :administrator_user_id);
```

Release preserves the hold history:

```sql
UPDATE audit_retention_holds
SET released_at = clock_timestamp(), released_by = :administrator_user_id
WHERE audit_log_id = :audit_log_id
  AND released_at IS NULL;
```

There is intentionally no public hold endpoint yet. Ordinary SQL `DELETE` against `audit_log`
continues to fail. Only the retention transaction can enable the transaction-local database guard,
and the trigger independently rechecks deadline, class and active holds.

## Schedule, configuration and rollout

The default schedule is daily at 03:30 in `Europe/Kaliningrad`.

| Variable | Default |
| --- | --- |
| `RETENTION_SCHEDULING_ENABLED` | `true` |
| `RETENTION_DELETION_ENABLED` | `false` |
| `RETENTION_POLICY_APPROVAL_REFERENCE` | `UNAPPROVED` |
| `RETENTION_BACKUP_CHECKPOINT_REFERENCE` | `UNVERIFIED` |
| `RETENTION_RESTORE_TESTED_AT` | `1970-01-01T00:00:00Z` |
| `RETENTION_MAXIMUM_RESTORE_TEST_AGE` | `90d` |
| `RETENTION_CRON` | `0 30 3 * * *` |
| `RETENTION_DELETE_BATCH_SIZE` | `10000` |
| `RETENTION_ROLLUP_BATCH_SIZE` | `1000` |
| `RETENTION_NORMALIZED_RAW` | `180d` |
| `RETENTION_PROBLEM_RAW` | `365d` |
| `RETENTION_SUCCESSFUL_SYNC_RUN` | `90d` |
| `RETENTION_UNSUCCESSFUL_SYNC_RUN` | `365d` |
| `RETENTION_SUCCESSFUL_SYNC_JOB` | `90d` |
| `RETENTION_UNSUCCESSFUL_SYNC_JOB` | `180d` |
| `RETENTION_CLOSED_QUALITY_ISSUE` | `365d` |
| `RETENTION_DETAILED_INVENTORY` | `P13M` |
| `RETENTION_DAILY_INVENTORY` | `P3Y` |
| `RETENTION_ZONE` | `Europe/Kaliningrad` |
| `RETENTION_AUDIT_SECURITY` | `P5Y` |
| `RETENTION_AUDIT_BUSINESS` | `P3Y` |
| `RETENTION_AUDIT_OPERATIONAL` | `P1Y` |

Recommended production rollout:

1. Apply V12–V18 and keep deletion disabled.
2. Observe dry-run candidate counts, including `legacy_raw_payload_versions`, over several runs.
3. Approve class lifetimes with the data/legal owner and record the policy reference.
4. Create a backup checkpoint, complete a restore rehearsal and record both references/timestamp.
5. Enable deletion in one environment; compare candidates, affected and remaining-candidate counts.
6. Reconcile normalized financial row counts/hashes and finalized report integrity after the purge.
7. Monitor duration, backlog and database load; stop enabling further environments on a mismatch.
8. Keep PostgreSQL autovacuum healthy; logical deletion does not immediately shrink table files.

## Logs, audit and metrics

A completed run logs only its generated run ID, mode and aggregate candidate/affected/remaining
counts. Failures include the full stack trace only in backend logs. Every lock-owning run records
`TECHNICAL_DATA_RETENTION_COMPLETED` as a system audit event with those safe summaries and the
policy/backup references, never source payload values.

| Metric | Tags | Meaning |
| --- | --- | --- |
| `storeanalytics.maintenance.retention.duration` | `outcome=success|skipped|failure` | End-to-end maintenance duration. |
| `storeanalytics.maintenance.retention.affected` | bounded `target` | Cumulative rows aggregated or deleted. |
| `storeanalytics.maintenance.retention.last.success.timestamp` | none | Unix timestamp of the last lock-owning successful run; `NaN` before one completes. |

Alerting thresholds belong to deployment configuration. At minimum, alert when no successful run
has completed beyond the expected daily interval, when failures repeat, or when duration/backlog
keeps growing.
