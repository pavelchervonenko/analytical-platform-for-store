---
doc_schema: 1
doc_type: current
status: current
owner: project
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - deploy/compose.production.yml
  - deploy/env.production.example
  - backend/src/main/resources/application.yml
verification_sources:
  - docs/history/releases/2026/08/v0.1.0-pilot.28-production-verification.md
runtime_evidence:
  - docs/history/releases/2026/08/v0.1.0-pilot.28-production-verification.md
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
**2026-08-31** and must be refreshed from sanitized read-only production evidence after every
deployment, schema change, relevant flag change or topology change.

## Verified production snapshot

| Item | Last verified value |
|---|---|
| Release | `v0.1.0-pilot.28` |
| Commit | `f130e0931f746d822e387292149bd57de4d5493e` |
| Flyway schema | `48` |
| Topology | `web`, `backend-api`, `backend-worker` |
| Service health | all three containers healthy at verification time |
| Backend image | `sha256:275db7f435dfeeb7db499200ee4c63cc14bce73b9e2772173185e10f3449a89f` |
| Web image | `sha256:7e362208a01d98656300662c714e796e0a5197292c865356b8238c5532f2b82d` |

Full provenance and limits are in the
[production verification record](../history/releases/2026/08/v0.1.0-pilot.28-production-verification.md).

## Weekly review

The production snapshot enabled the weekly-review read path, optional AI enrichment and the AI
worker. Automatic snapshot planning and automatic AI planning were disabled. The active AI
contract in code is `weekly-interpretation-v25/schema4`; the first manual canary passed for one
store. That result does not establish automatic rollout for every store.

On 2026-08-31, one deterministic snapshot for the completed week `2026-08-24..2026-08-30`
was generated for each active store. Both snapshots are `PARTIAL` and return through the new
weekly-review read path. No AI job was created. See the
[current-week verification record](../history/canaries/2026/08/weekly-review-current-week-deterministic-snapshots.md).

The legacy weekly insight remains a compatibility fallback in both backend and frontend. It must
not be removed until the fallback and its Telegram dependencies are deliberately retired.

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
