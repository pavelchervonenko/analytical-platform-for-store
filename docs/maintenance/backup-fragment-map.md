---
doc_schema: 1
doc_type: current
status: current
owner: project
audience:
  - developer
last_verified: 2026-08-31
requirement_sources:
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - docs/maintenance/documentation-inventory.tsv
verification_sources:
  - git diff --no-index for every current/.orig pair
runtime_evidence: []
required_reviewers:
  - information-architecture
  - operations
review_triggers:
  - backup-artifact-change
  - documentation-deletion
supersedes: []
superseded_by: null
---

# Backup artifact fragment map

This map records the complete comparison required before deleting the five ignored `.orig` files.
They are not Git history, current contracts or runtime artifacts. No deletion is authorized by this
file alone: a separate historical reviewer sign-off must name every path and return `PASS`.

## Method

For each pair, the current and backup SHA-256, line count and full
`git diff --no-index --unified=2` were inspected on 2026-08-31. A fragment is considered preserved
when the current file contains it or a newer immutable history/current contract preserves its
meaning. Obsolete status, contradicted implementation claims and superseded operator commands are
not migrated into current documentation.

## Results

### `docs/AI_WEEKLY_REDESIGN_WORKLOG.md.orig`

- Backup: `95c5a495d35faeeea54f2b51288ba5560d856be2a7e0a049de7fa96e928cb828`, 636 lines.
- Current pair: `b9f247ad223f2e2cf5b3157db52e0f16cbd9ef3c47469454446e0620e9517efd`, 752 lines.
- Diff summary: 11 backup-only insertions and 127 current-only insertions.
- Backup-only fragments are earlier stage/status statements, the pre-v23 AI ownership rule and an
  obsolete continuation point. Later v23–v25 calibration and rollout records supersede them.
- Unique durable fragment: none. Disposition: delete after reviewer sign-off.

### `docs/database-design.md.orig`

- Backup: `cc9a54dcb509c0f3ab0a96ddda2293102a6be3d44a751950e8b03ba92a5b2475`, 294 lines.
- Current pair: `0e55b5fa3f3e83bf61127999a366886d59c0c9e0b7a2a3f9800c0eef21a6713a`, 299 lines.
- Diff summary: 3 backup-only insertions and 8 current-only insertions.
- Backup-only text is an older verification date and a pre-V48 description of budget reservation.
  It is less complete than the current V48 contract.
- Unique durable fragment: none. Disposition: delete after reviewer sign-off.

### `docs/livesklad-webhook-receiver.md.orig`

- Backup: `cbf03b96007ee436098a4ee60dbf7ad8b64cf054186f1c3ad9d46c52a3faf0f2`, 101 lines.
- Current pair: `dff4cad840e452a858ad0ef4a41ce48be1fdbed0063bf05dc90fb326026b75fd`, 245 lines.
- Diff summary: 11 backup-only insertions and 155 current-only insertions.
- Backup-only text describes the earlier state where order-return events were retained but not
  processed and uses the older V42-only rollout wording. Later code and current text supersede it.
- Unique durable fragment: none. Disposition: delete after reviewer sign-off.

### `docs/weekly-review-v2-backend.md.orig`

- Backup: `2ec8c94616de0b814b1d7022bc46372999ba1f541428c63153acbd80f314c97c`, 198 lines.
- Current pair: `07429d944f6184c49f33e9b919c3a2cf7017553e6a3cac8b93c4bcaabdeec41e`, 218 lines.
- Diff summary: 25 backup-only insertions and 45 current-only insertions.
- Backup-only fragments describe the pre-hardening v22 contract, pre-V48 rollout status and an
  obsolete frontend activation plan. Immutable v22 rollout evidence and later v25 contracts retain
  the historical meaning without treating it as current.
- Unique durable fragment: none. Disposition: delete after reviewer sign-off.

### `docs/weekly-review-v22-ai-contract.md.orig`

- Backup: `88be95acbc18b4d3bb03786bfb93321a09d8134c91e53b26a24756546d1ce54b`, 172 lines.
- Current pair: `16ef2423ea2436b41c4ad993b8cfcd2e51524f55850e5323ee2653566fd6a19f`, 175 lines.
- Diff summary: 3 backup-only insertions and 6 current-only insertions.
- Backup-only fragments are the earlier date and pre-V48 planner/attempt wording. Later immutable
  rollout records preserve that stage; the current pair contains the hardening details.
- Unique durable fragment: none. Disposition: delete after reviewer sign-off.

## Removal gate

Before changing any inventory row to `tracking=removed; action=removed`:

1. the base revision must already classify that path as `delete-candidate`;
2. the separate sign-off evidence must name the exact path and this map;
3. the sign-off must be tracked historical evidence with `PASS` or `PASS_WITH_LIMITS`;
4. deletion must be a dedicated commit and `python3 scripts/check-documentation.py --strict` must
   pass after the warning allowlist is removed.
