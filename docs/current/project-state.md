---
doc_schema: 1
doc_type: current
status: current
owner: project
audience:
  - developer
  - operator
last_verified: 2026-08-30
requirement_sources:
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - deploy/compose.production.yml
  - deploy/env.production.example
  - backend/src/main/resources/application.yml
verification_sources:
  - docs/history/releases/2026/08/v0.1.0-pilot.27-production-verification.md
runtime_evidence:
  - docs/history/releases/2026/08/v0.1.0-pilot.27-production-verification.md
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
**2026-08-30** and must be refreshed from sanitized read-only production evidence after every
deployment, schema change, relevant flag change or topology change.

## Verified production snapshot

| Item | Last verified value |
|---|---|
| Release | `v0.1.0-pilot.27` |
| Commit | `ea90ec81c3c33729e86d515e937bd9d82c39e636` |
| Flyway schema | `48` |
| Topology | `web`, `backend-api`, `backend-worker` |
| Service health | all three containers healthy at verification time |
| Backend image | `sha256:ca73220219b27c1aa0b738dedfb19b4d6c3caf2086bb5f146ce214b6769c6feb` |
| Web image | `sha256:1953f68c53755a0390ffe79233ce6fae7b7deea21c96fa3a3e51b050766b595c` |

Full provenance and limits are in the
[production verification record](../history/releases/2026/08/v0.1.0-pilot.27-production-verification.md).

## Weekly review

The production snapshot enabled the weekly-review read path, optional AI enrichment and the AI
worker. Automatic snapshot planning and automatic AI planning were disabled. The active AI
contract in code is `weekly-interpretation-v25/schema4`; the first manual canary passed for one
store. That result does not establish automatic rollout for every store.

The legacy weekly insight remains a compatibility fallback in both backend and frontend. It must
not be removed until the fallback and its Telegram dependencies are deliberately retired.

## Data and return recovery

The validated recovery of the eight known July sale returns is **completed — do not rerun**.
Post-recovery July reconciliation matched the supplied CRM totals for both stores. The evidence is
recorded in [the 2026-08-24 release/reconciliation record](../RELEASE_CANDIDATE_2026-08-24.md#production-preflight-от-2026-08-25).

That completed operation does not prove that every future or historical discrepancy is absent.
Future confidence depends on synchronization coverage, webhook processing, source quality and
period-specific reconciliation.

## Known operational limits

- A failed Flyway migration can leave `MIGRATION_IN_PROGRESS`; the repository has no rehearsed
  automatic reconciliation of that marker with actual `flyway_schema_history`.
- Backup creation and upload are implemented, but a downloaded, decrypted, isolated restore with
  measured RPO/RTO has not been evidenced.
- Repository alert rules are not proof of connected production alert routing.
- Session state is process-local; production must remain at one API replica until a shared session
  registry is implemented and verified.
- The new weekly-review schema4 has no documented Telegram publication bridge; weekly Telegram
  fanout remains a legacy-schema path.

These are tracked capability gaps, not instructions to bypass a release gate.

## Refresh procedure

After a production change, create a new immutable verification record under
`docs/history/releases/YYYY/MM/`, then update only this page's observed values and
`last_verified`. Never copy dynamic production facts back into root indexes, architecture pages or
historical handoffs.
