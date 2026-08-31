---
doc_schema: 1
doc_type: archive
status: archived
owner: operations
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "AGENTS.md"
original_content_sha256: c65f3920ccf087c40d97c7a6e099e96c67e802affe671ca42bb0e03f8d710cfc
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `AGENTS.md`.

# Store Analytics: working practices for the production pilot

Status: normative continuation appendix, recorded on 2026-08-11.

Read this file together with
`PRODUCTION_PILOT_CONTINUATION_HANDOFF_2026-08-11.md`. The application is an active customer pilot,
not a disposable test environment. These rules describe how investigation, development, release
and production operation must continue in a new chat.

## 1. Evidence before changes

- Reproduce or measure a problem using application state, job history, API responses, database
  facts and relevant logs.
- Separate an observed symptom, a working hypothesis and a confirmed root cause.
- Verify the deployed image commit and migration version before comparing production with the local
  repository.
- Use business dates for period analysis. Technical timestamps such as `created_at` and
  `detected_at` are not substitutes for a sale or return date.
- Record the pre-change state and measurable acceptance criteria before implementation.

## 2. Fix the source, not the display

- Prefer correcting synchronization, normalization, classification or an upstream source fact over
  hiding a warning in the interface.
- Never fabricate costs, payments, plans, shifts, ratings or payroll values to make a check green.
- Do not manually close data-quality issues. They must close through authoritative reprocessing
  after the source fact or an approved rule is corrected.
- A historical backfill is not a universal repair. Start it only when business dates or source
  references prove older data is required.
- New products must be classified by deterministic, versioned rules or explicit effective-dated
  overrides. Daily manual classification is not an acceptable operating model.

## 3. Small, safe and reversible increments

- Keep each change focused on one verified problem and avoid unrelated refactoring in the release.
- Prefer bounded monthly backfills and short incremental windows over a single long reload.
- Synchronization and normalization must be idempotent: retrying the same window must not duplicate
  facts or corrupt metrics.
- Long work must run as a durable background job with persisted progress, retry policy, lease
  recovery, visible status and an audit trail. An SSH session must not own a business job.
- Never start a duplicate job while another job for the same connection is non-terminal.
- Daily incremental synchronization has priority over historical loading.

## 4. Database and financial-data discipline

- Managed PostgreSQL is the authoritative application datastore. Source payloads remain evidence;
  normalized facts and explicit rules drive analytics.
- Schema changes use ordered Flyway migrations. Never edit an already applied migration.
- Prefer additive, backward-compatible migrations so the previous application image can still run
  during rollback.
- Application rollback does not undo database migrations; release design must account for this
  asymmetry.
- Use read-only SQL for investigation. Direct writes to synchronization jobs, normalized financial
  facts, payroll results or issue status require a separately reviewed recovery procedure.
- Recalculate derived metrics only after facts or rules change, and compare the exact affected
  period before and after.

## 5. API and frontend contracts

- Backend contracts are authoritative and must be reflected in OpenAPI and frontend types.
- Every store-scoped endpoint enforces access on the server; hiding controls is not authorization.
- List endpoints are bounded and paginated with deterministic ordering.
- Return safe DTOs, not raw provider payloads, unrestricted metadata, credentials or unnecessary
  personal data.
- Loading, empty, partial-data, stale-data and authorization states are explicit in the interface.
- Counts and details shown together use the same period and scope, or the difference is clearly
  labelled.

## 6. Security and secrets

- Apply least privilege to database roles, S3 credentials, host users and containers.
- Store secrets only in protected production secret files or an approved secret mechanism.
- Never commit secrets or print them in documentation and chat.
- Never put credentials in image layers, Compose files, shell history, release archives or frontend
  build variables.
- Keep PostgreSQL certificate verification, HTTPS, private managed-service networking and key-only
  SSH enabled.
- Temporary elevated access is narrowly scoped and removed after use.
- Minimize and sanitize diagnostic output before sharing it.

## 7. Testing and release evidence

Select tests at the affected boundary:

- unit tests for rules and calculations;
- PostgreSQL/Testcontainers tests for repository semantics, dates, ACL-sensitive queries and
  migrations;
- API/OpenAPI contract tests for contract changes;
- frontend component tests for states and interactions;
- a regression test for every confirmed production failure mode;
- production smoke tests for HTTPS, health, authorization and the changed user journey.

A successful build is not production acceptance. Acceptance requires a verified backup, immutable
image identity, migration result, healthy containers, public smoke checks, clean fresh logs and a
written release record.

## 8. Collaboration and operational communication

- Explain what a command checks or changes before asking the operator to run it.
- Prefer short command groups with observable output and inspect the result before the next change.
- The operator works from WSL; provide WSL/bash commands unless Windows is genuinely required.
- Never request passwords, tokens or complete secret files in chat. Ask for sanitized output or a
  success/failure result.
- Long operations need a status command or interface view; avoid unexplained silent commands.
- Do not claim success until production evidence and user-visible behavior agree.
- Update the handoff and operations documentation after every accepted release, backfill milestone
  or architectural decision.
- Preserve unrelated and untracked user files. Stage only intentional task files.

## 9. Incident decision order

1. Protect availability and prevent duplicate or unsafe work.
2. Establish the affected stores, business dates and scope.
3. Collect read-only evidence.
4. Confirm the root cause.
5. Choose the smallest authoritative correction.
6. Test it against the real failure mode.
7. Back up and deploy through the release procedure.
8. Verify technical health and business results.
9. Document evidence, residual risks and the next action.
