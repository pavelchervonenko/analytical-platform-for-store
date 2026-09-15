---
doc_schema: 1
doc_type: current
status: current
owner: project
audience:
  - developer
  - operator
last_verified: 2026-09-15
requirement_sources:
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - deploy/compose.production.yml
  - deploy/env.production.example
  - backend/src/main/resources/application.yml
verification_sources:
  - docs/history/releases/2026/09/v0.1.0-pilot.33-production-verification.md
  - docs/history/releases/2026/09/v0.1.0-pilot.32-production-verification.md
  - docs/history/releases/2026/09/v0.1.0-pilot.30-production-verification.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-08_MAGAZIN.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-08_MOBISFERA.md
runtime_evidence:
  - docs/history/releases/2026/09/v0.1.0-pilot.33-production-verification.md
  - docs/history/releases/2026/09/v0.1.0-pilot.32-production-verification.md
  - docs/history/releases/2026/09/v0.1.0-pilot.30-production-verification.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-08_MAGAZIN.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-08_MOBISFERA.md
required_reviewers:
  - information-architecture
  - operations
review_triggers:
  - production-deploy
  - database-migration
  - production-flag-change
  - topology-change
supersedes:
  - dynamic status sections in README.md and docs/README.md
superseded_by: null
---

# Current project state

This is the only repository page allowed to summarize the currently verified production release.
It records an observation, not a live dashboard. Dynamic values below were last observed on
**2026-09-15** and must be refreshed from sanitized read-only production evidence after every
deployment, schema change, relevant flag change or topology change.

## Verified production snapshot

| Item | Last verified value |
|---|---|
| Release | `v0.1.0-pilot.33` |
| Commit | `ee1667fbf48f2bf9ab6e09ffa2da421c1f2d7e96` |
| Flyway schema | `51` |
| Topology | `web`, `backend-api`, `backend-worker` |
| Service health | all three containers healthy at verification time |
| Backend image | `sha256:6b14191f2433e3464d77286eb0edb0d2b9040f2e244409d792259710fa11d65b` |
| Web image | `sha256:4345da51408442dfbaf837387f16fa85b93bbf964bd5ff842481d05f07db7324` |
| Weekly Review AI | exact manual generation and dedicated worker enabled; automatic snapshot and AI planners disabled |
| Weekly Review AI model | `yandexgpt-5.1` |

The current deployment provenance, schema-51 no-op rehearsal, transient outer-wrapper web-health
observation, independent post-deploy PASS and two exact AI canaries are preserved in the
[pilot.33 production verification record](../history/releases/2026/09/v0.1.0-pilot.33-production-verification.md).
The current runtime identity and sanitized read-only business-data verification are preserved in
the August LiveSklad reconciliations for
[МАГАЗИН](../history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-08_MAGAZIN.md)
and
[МобиСфера](../history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-08_MOBISFERA.md).

## Weekly review

Release `v0.1.0-pilot.33` deployed the exact Weekly Review AI preflight/approval contract. Manual
AI generation and its dedicated worker are enabled; automatic snapshot planning and automatic AI
planning remain disabled. Automatic classification and other deferred data mutations also remain
disabled.

On 2026-09-15, one immutable snapshot for the completed week `2026-09-07..2026-09-13` was created
for each active store. Both snapshots are `PARTIAL`. Their separately authorized single-call
canaries passed structural and semantic validation, cost 2.137600 RUB in total, created no weekly
notification event and now return `AI_ENHANCED_READY` through the normal read path. No AI job was
active and no active-version job had failed at final verification. Exact identities, hashes, costs
and evidence are preserved only in the
[pilot.33 verification record](../history/releases/2026/09/v0.1.0-pilot.33-production-verification.md).

The legacy weekly insight remains a compatibility fallback in both backend and frontend. It must
not be removed until the fallback and its Telegram dependencies are deliberately retired.

Release `v0.1.0-pilot.30` aligns employee cards with the approved rating roster and streamlines the
manager-facing presentation. It does not rewrite the persisted review for `2026-08-24..2026-08-30`.
An owner-approved exact-target operation also enabled one newly synchronized seller assignment for
rating participation in `МАГАЗИН`; the corresponding `МобиСфера` assignment remains excluded.

## Data and return recovery

The validated recovery of the eight known July sale returns is **completed — do not rerun**.
Post-recovery July reconciliation matched the supplied CRM totals for both stores. The evidence is
recorded in [the 2026-08-24 release/reconciliation record](../history/releases/2026/08/RELEASE_CANDIDATE_2026-08-24.md#production-preflight-от-2026-08-25).

That completed operation does not prove that every future or historical discrepancy is absent.
Future confidence depends on synchronization coverage, webhook processing, source quality and
period-specific reconciliation.

Release `v0.1.0-pilot.28` assigns newly synchronized or deliberately reprocessed returns to the
employee of the original sale. It did not run a historical return reprocessing operation. Returns
whose original sale is not loaded therefore remain unresolved until the missing source periods are
loaded and a separately approved, scoped reprocessing operation is performed.

Release `v0.1.0-pilot.31` accepts delayed cash-return events without interpreting their absence in
an earlier fetch as permission to delete the merchandise return. The 2026-09-13 August read-only
reconciliation found no missing, extra or deleted in-period return facts and required no backfill.

Release `v0.1.0-pilot.32` deployed guarded recovery/relink and bounded-classification tooling but
did not execute it. All remaining January–March and May/July corrections stay in the separate
approval queue; no backfill or resynchronization was triggered by deployment.

## Known operational limits

- A failed Flyway migration can leave `MIGRATION_IN_PROGRESS`; the repository has no rehearsed
  automatic reconciliation of that marker with actual `flyway_schema_history`.
- The 2026-09-15 release rehearsal proved download, decryption and isolated restore of a fresh
  encrypted production backup, with 4-second restore at schema 51 and an 11-second schema-51 no-op
  migration path in the rehearsal environment. This is not a full disaster-recovery or measured
  end-to-end RPO test.
- Application rollback from pilot.33 to the previous pilot.32 runtime on schema 51 sustained
  readiness in rehearsal. That does not authorize an unreviewed rollback or prove database restore.
- Repository alert rules are not proof of connected production alert routing.
- Session state is process-local; production must remain at one API replica until a shared session
  registry is implemented and verified.
- Automatic Weekly Review snapshot and AI planners remain disabled. A future completed week needs
  a new exact snapshot, network-free preflight and separately authorized bounded provider call.
- The two current reports are `PARTIAL`; successful AI enrichment does not remove deterministic
  source-data limitations.
- The new weekly-review schema4 has no documented Telegram publication bridge; weekly Telegram
  fanout remains a legacy-schema path.

These are tracked capability gaps, not instructions to bypass a release gate.

## Refresh procedure

After a production change, create a new immutable verification record under
`docs/history/releases/YYYY/MM/`, then update only this page's observed values and
`last_verified`. Never copy dynamic production facts back into root indexes, architecture pages or
historical handoffs.
