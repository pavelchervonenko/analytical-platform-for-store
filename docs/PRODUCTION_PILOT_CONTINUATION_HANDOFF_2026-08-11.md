# Store Analytics: production pilot continuation handoff

Status: active production pilot handoff, recorded on 2026-08-11.

This file is the primary continuation point for a new Codex chat. It records the production state,
the decisions already made, the work completed, the current data-quality investigation and the
safe order of the next actions. It intentionally contains no passwords, tokens, private database
addresses, object-storage identifiers or customer credentials.

## 1. Read this first

Before changing code or production, read these documents:

1. [deployment-and-operations.md](deployment-and-operations.md) — architecture, production state
   and normative operations standard;
2. [production-deployment-runbook.md](production-deployment-runbook.md) — release procedure;
3. [synchronization-api.md](synchronization-api.md) — durable LiveSklad jobs and retry behavior;
4. [period-quality-api.md](period-quality-api.md) and
   [data-quality-api.md](data-quality-api.md) — quality gates;
5. [payroll-api.md](payroll-api.md) and
   [payroll-classification-review.md](payroll-classification-review.md) — payroll readiness and
   product classification;
6. [llm-production-operations.md](llm-production-operations.md) and
   [weekly-snapshot-operations.md](weekly-snapshot-operations.md) — AI operation;
7. [PROJECT_HANDOFF.md](PROJECT_HANDOFF.md) — wider repository context.
8. [PRODUCTION_PILOT_WORKING_PRACTICES.md](PRODUCTION_PILOT_WORKING_PRACTICES.md) ? normative
   engineering, security and production-change practices used in this pilot.

Do not treat `PRODUCTION_READINESS_SECURITY_AUDIT_TEMP.md` as authoritative. The maintained
documentation under `docs/` is the source of truth unless this handoff explicitly records a newer
verified production event.

## 2. User goal and operating constraints

The application is already used by customer managers in pilot operation. It is not a disposable
test environment.

The desired outcome is:

- reliable daily LiveSklad synchronization;
- gradual historical loading back to 2026-01-01;
- stable analytics, employee ratings and payroll calculations;
- Yandex AI interpretations grounded only in verified metrics;
- Telegram notifications after separate acceptance;
- simple, reversible releases with backup and verification;
- no prolonged outages or repeated full-period reloads during business use.

Operational constraints:

- two stores are currently connected;
- a few managers share the same business visibility;
- maintenance is preferably performed from 22:00 to 06:00 in Europe/Kaliningrad;
- the historical backfill must be performed one bounded month at a time;
- daily incremental synchronization has priority over historical loading;
- no MFA, VPN or IP allowlist is currently required by the customer;
- secrets must never be written to repository documentation or chat responses.

## 3. Production architecture and current state

- Public application: `https://store-analytics.net`.
- Application host: Ubuntu 24.04 LTS in Timeweb, Saint Petersburg region.
- Runtime: Docker Compose with separate `backend-api`, `backend-worker` and `web` containers.
- Edge: Caddy with automatic HTTPS.
- Database: managed PostgreSQL 16 over the private provider network with TLS `verify-full`.
- Object storage: private Timeweb S3 with versioning, Governance Object Lock and a 100 GB account
  safety limit.
- Database roles are separated for runtime, migrations and backup.
- Host firewall, SSH key-only access, provider monitoring and encrypted logical backups are active.
- Local health and backup systemd timers were verified active after the latest release.

Current deployed application release:

- release ID: `v0.1.0-pilot.10-72a9162`;
- backend image: `store-analytics-backend:v0.1.0-pilot.10`;
- web image: `store-analytics-web:v0.1.0-pilot.10`;
- application commit embedded in both images: `72a9162`;
- Flyway schema version: `34`;
- liveness and readiness: `UP` after deployment;
- previous `pilot.9` application images and release state remain available for application rollback.

Immediately before `pilot.10`, an additional encrypted PostgreSQL logical backup was uploaded to
S3 and verified. The release passed image checksum validation, migration, database ACL repair,
container health checks, HTTPS smoke tests and a separate post-deploy check. No recent backend
`ERROR`, `FATAL` or exception entry was found during acceptance.

Temporary full `NOPASSWD` access used for the release was removed. Do not assume passwordless sudo
is available.

## 4. Changes already deployed

### 4.1 Synchronization reliability

Earlier production releases added durable job recovery, bounded windows, provider rate-limit
handling and retries for missed daily synchronization. Production settings use:

- six-hour synchronization windows;
- a three-day incremental overlap;
- hourly creation checks from 03:15 through 08:15 Europe/Kaliningrad;
- one non-terminal job per LiveSklad connection;
- persisted progress after every child window;
- idempotent normalization and safe retry after lease expiry.

### 4.2 Product classification

Release `pilot.9` (`732bdc0`) added automatic classification for new LiveSklad products based on the
approved deterministic rules. This was necessary because one-time assignments for known product
UUIDs did not cover newly appearing source UUIDs.

The earlier controlled remediation assigned all known sold products effective from 2026-01-01 and
removed the then-existing `UNMAPPED` normalized rows. New synchronization must remain the
authoritative way to reconcile source data.

### 4.3 Current-period quality behavior

Release `pilot.10` (`72a9162`) added:

- a shared last-completed-reporting-day cutoff for the current month;
- period-scoped consistency issue counting instead of treating unrelated historical issues as
  current-month blockers;
- frontend query refresh when a manager returns to the browser tab;
- contract and UI propagation of `periodOpenConsistencyIssueCount`;
- deterministic payroll defaults for confirmed MacBook, iPad, Dyson and PlayStation 5 devices,
  while explicit effective-dated product overrides still take priority;
- protection against classifying accessories as device payroll categories.

Flyway V34 creates the deterministic payroll-category resolver. It does not rewrite source facts.

### 4.4 AI and Telegram

YandexGPT weekly interpretation is enabled and had passed production acceptance for both pilot
stores. It uses strict structured output, bounded retries, evidence validation and persisted cost
accounting. Historical data outside the snapshot revision window does not automatically imply that
historical AI reports will be regenerated.

Telegram infrastructure exists, but customer delivery remains deferred until linking and webhook
acceptance are completed.

## 5. Historical loading completed

Initial production data covered 2026-07-01 onward.

On 2026-08-11, an ADMIN started a durable backfill through the production interface for:

- start: `2026-06-01`;
- inclusive end: `2026-06-30`.

The interface reported the job as successfully completed. The application stayed healthy while the
worker processed it. Do not create another June job unless a source correction or controlled
reconciliation specifically requires it.

No May-or-earlier backfill has been accepted yet. The intended direction is one month at a time,
with a quality gate after every month.

## 6. June quality result

For the selected store, the June period-quality view showed:

- no missing-period/source-coverage error;
- no unmapped-product error;
- store plan absent;
- employee shifts absent, including six employees with sales but no worked shifts;
- rating plan coverage incomplete;
- payroll blocked because the plan is absent;
- payroll not yet calculated;
- rating not yet finalized;
- nine June positions with unexpected zero cost;
- five June consistency issues.

Interpretation:

- the June source synchronization and product classification succeeded;
- plan and shift problems are expected manual configuration gaps, not LiveSklad synchronization
  failures;
- payroll and rating must not be calculated or finalized until the source issues are diagnosed and
  real plans/shifts are entered;
- do not invent historical plans or shifts merely to make quality checks green.

## 7. Important quality UI limitation discovered

The quality page currently combines two differently scoped APIs:

- the upper `What requires action` section uses the selected-month period-quality endpoint;
- the lower `Source data problems` section uses the store-wide data-quality endpoint and therefore
  lists every open issue for the store across all loaded periods.

Consequently, the lower block displayed 28 global events even though the June period had only five
period-scoped consistency issues and nine zero-cost items. This is a real UX/product gap, not proof
that June contains all 28 events.

The global issue groups visible after the June backfill were:

| Issue code | Displayed count | Meaning |
|---|---:|---|
| `RETURN_ORIGINAL_DOCUMENT_MISSING` | 8 | A return could not be linked to its original sale |
| `ZERO_UNEXPECTED_COST` | 18 | A non-service sale item has zero cost |
| `RETURN_ZERO_UNEXPECTED_COST` | 1 | A non-service returned item has zero cost |
| `SALE_PAYMENT_MISMATCH` | 1 | Payment total differs from sale net amount |

The displayed `detected_at` value is the synchronization detection time, not necessarily the
document business date. It cannot be used to decide which month contains the source document.

The current `Подробнее` dialog shows only a grouped code, severity, event count and latest detection
time. It does not show the affected business date, source document or product. Therefore the
current interface is insufficient for safe root-cause remediation.

## 8. Current root-cause hypotheses — not yet conclusions

### 8.1 Returns without original sales

A June return may reference a sale from May or an earlier month. In that case, a bounded May
backfill may allow the return to link during reconciliation. However, do not load May solely on this
assumption. First determine the business dates and source identifiers of the affected returns and
their referenced sales.

If the referenced sale does not exist in LiveSklad or the reference is malformed, loading more
months will not fix the issue.

### 8.2 Unexpected zero cost

Older history generally will not repair a zero cost supplied on a June sale item. Each affected
position must be classified into one of these cases:

1. physical product with an incorrect zero cost in LiveSklad;
2. service/warranty/protection whose analytics category or upstream `work` flag is wrong;
3. legitimate edge case that needs an explicit approved business rule.

Flyway V33 already resolved open zero-cost issues for categories that explicitly permit zero cost.
Therefore remaining issues should not be silently ignored or globally reclassified.

### 8.3 Payment mismatch

The affected sale must be inspected by comparing the normalized sale net total with its active
payment components. Possible causes include incomplete upstream payments, deleted components,
rounding or an unsupported document state. Do not alter financial facts merely to make the totals
equal.

## 9. Highest-priority next work

### P0 — Diagnose the exact June source issues

Produce a read-only diagnostic report for each selected store containing only June issues:

- issue code and severity;
- normalized business date;
- safe source document reference;
- product name/reference for item-level issues;
- sale/return totals, cost and payment sums needed to explain the invariant;
- referenced original sale for unmatched returns;
- whether the referenced sale falls before 2026-06-01;
- no raw payloads, credentials, customer personal data or unrestricted source responses.

Do not manually change issue status. Issues must close through authoritative reprocessing after the
source data or approved classification is corrected.

### P1 — Fix period-aware quality drill-down

Recommended backend design:

- add an authenticated, store-authorized, paginated endpoint for quality issues filtered by
  `periodStart`, `periodEnd`, status and optional issue code;
- resolve issue entity IDs through normalized sale/return tables;
- use the raw-return date only for unmatched return documents;
- return a safe DTO rather than `metadata` or raw payloads;
- keep store-access authorization and ADMIN-only source references where necessary;
- use an explicit maximum page size and deterministic ordering.

Recommended frontend design:

- make the lower issue list follow the selected month by default;
- label any all-history view explicitly;
- show affected rows inside `Подробнее` with business date, safe document reference and product;
- retain grouped counts but allow drilling into individual events;
- distinguish period issue count from store-wide issue count;
- add loading, empty, pagination and authorization states.

Required tests:

- repository integration tests for sale documents, sale items, return documents and return items;
- unmatched return filtering by raw-return business date;
- cross-store authorization tests;
- OpenAPI and frontend contract tests;
- frontend tests proving that changing the selected month changes the detailed issues;
- regression test that no raw payload or credential field is returned.

### P1 — Decide whether May is required

Load May only if the diagnostic proves that June unmatched returns reference May sales, or after
the product owner explicitly chooses to continue the month-by-month historical rollout.

After each monthly backfill verify:

1. job status and retries;
2. sales and returns coverage;
3. unmapped products;
4. unexpected/missing costs;
5. return linkage and financial consistency issues;
6. latest daily incremental job;
7. application and database health.

### P1 — Verify the latest daily incremental synchronization

Because a historical job can temporarily occupy the only active LiveSklad connection job slot,
verify that the daily rolling incremental job completed after the June backfill and covers the
latest completed reporting day. If the historical job overlapped the 03:15–08:15 creation window,
the daily job may have been deferred until the next checkpoint or next morning.

### P2 — Enter real June business inputs

After source issues are understood:

- enter the actual June store plan for each store;
- enter and confirm real employee shifts;
- verify rating eligibility;
- calculate payroll in draft;
- review results before approval;
- finalize ratings only when the historical inputs are accepted.

### P2 — Historical reports and AI

Do not assume the June backfill automatically creates historical weekly AI interpretations. Define
a separate controlled report/snapshot backfill policy after data quality, plans and shifts pass.
Keep Yandex AI cost accounting and per-generation limits active.

## 10. Safe production workflow

For every production change:

1. inspect the current deployed release; do not trust an old documentation version blindly;
2. confirm the candidate commit is a descendant of the deployed commit;
3. run backend tests, frontend contract/lint/tests/build and migration checks;
4. build immutable backend and web images with commit/release labels;
5. verify image checksums after transfer;
6. create and verify an encrypted PostgreSQL backup before migration;
7. refuse deployment while a synchronization job is `RUNNING`;
8. copy the current release environment and change only release/image metadata;
9. apply Flyway migrations and reassert database ACLs;
10. start backend API/worker, wait for health, then start the edge;
11. run HTTPS, liveness, readiness and security-header smoke tests;
12. inspect fresh API/worker logs;
13. keep the previous release state for rollback;
14. remove transfer archives and temporary elevated sudo rules.

Database migrations are not automatically reversed by application rollback. New migrations must be
backward-compatible with the previous application release whenever practical.

## 11. SSH and operational access notes

The developer works from WSL, not PowerShell. A typical SSH control connection is:

```bash
ssh -M -S ~/.ssh/store-analytics-prod-control \
  -o ControlPersist=2h \
  -o IdentitiesOnly=yes \
  -i ~/.ssh/store-analytics-prod \
  -fN pavel@store-analytics.net
```

Public health can be checked without a session:

```bash
curl -fsS https://store-analytics.net/livez
curl -fsS https://store-analytics.net/readyz
```

A stale control socket occurred after an interrupted silent bootstrap command. Before removing a
socket, inspect the associated SSH process and attempt `ssh -O exit`. Do not delete arbitrary SSH
files or kill an interactive user shell.

The server-side `bootstrap-pilot-data.sh` flow returned HTTP 401 even after the operational admin
password file was refreshed. Browser ADMIN authentication works, and the June job was therefore
started through the UI. Treat the script authentication mismatch as unresolved; do not repeatedly
retry it or expose the password while diagnosing it.

Prefer production UI/API actions that create audit records. Use read-only SQL only for diagnostics,
and never insert synchronization jobs directly in the database.

## 12. Repository state

Current branch:

```text
codex/pilot-production-deployment
```

Recent relevant commits:

```text
b858d68 docs: record pilot10 production rollout
72a9162 fix: stabilize pilot data quality handling
d1507c4 docs: record pilot9 classification rollout
732bdc0 fix: auto-classify new LiveSklad products
629d668 docs: record pilot8 sync and AI acceptance
e6a3126 fix: nightly sync provider limit recovery
```

The production application image contains `72a9162`; `b858d68` is the later local operational
documentation commit.

Tracked files were clean when this handoff was created. These untracked user/local artifacts were
present and must not be added, deleted or overwritten without explicit review:

```text
BACKEND_STARTUP_SYNC_REGRESSION_AUDIT.md
BACKEND_STARTUP_SYNC_REGRESSION_AUDIT_RESULT.md
PRODUCTION_READINESS_SECURITY_AUDIT_TEMP.md
docker/local-integration/
scripts/run-local-telegram-update-bridge.sh
```

No Git push was performed as part of the `pilot.10` deployment. Do not assume the current branch or
the two latest commits exist on a remote repository.

## 13. Verification already completed for pilot.10

- full backend test suite excluding the separate operator script security test passed with
  Testcontainers;
- focused payroll repository integration tests passed against PostgreSQL;
- backend Checkstyle passed;
- frontend OpenAPI contract verification passed;
- frontend lint passed;
- all 105 frontend tests passed;
- production frontend build passed;
- Docker image builds passed;
- Flyway validated 34 migrations and applied V34 successfully;
- runtime EXECUTE permission for the V34 function was verified;
- post-deploy containers were healthy;
- public liveness/readiness were `UP`;
- production logs were clean during the acceptance window.

Do not rerun the complete test suite merely to rediscover this evidence unless code has changed or a
new release is being prepared.

## 14. Acceptance criteria for the next checkpoint

The next checkpoint is complete when:

- the exact five June consistency issues are identified by business date and source entity;
- all nine June zero-cost positions are explained and assigned an owner/action;
- it is known whether May data is required for any June return linkage;
- the quality drill-down design is implemented or a safe temporary diagnostic report exists;
- the most recent daily incremental synchronization is confirmed successful;
- no new `UNMAPPED_PRODUCT` regression exists;
- production remains healthy and customer-visible workflows are unaffected;
- this handoff and the main production record are updated with verified results.

## 15. Suggested opening message for the next chat

Copy this message into a new chat:

> Continue the Store Analytics production pilot from
> `docs/PRODUCTION_PILOT_CONTINUATION_HANDOFF_2026-08-11.md`. Read that file completely, then read
> the linked deployment, synchronization and quality documents. Do not change production or code
> until you verify the current Git and deployed release state. The immediate task is to identify the
> exact five June consistency issues and nine June zero-cost positions, determine whether May data
> is needed for unmatched returns, and design a period-aware safe quality drill-down. Preserve all
> untracked user files listed in the handoff. Production is actively used by customer managers, so
> avoid duplicate synchronization, long blocking work and unverified data rewrites.
