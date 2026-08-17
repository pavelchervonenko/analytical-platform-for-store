# Production release `v0.1.0-pilot.15` — 2026-08-17

## Result

Release `v0.1.0-pilot.15-22fc9c4` was deployed successfully to the pilot production VPS on
2026-08-17. Flyway advanced the `app` schema from version `38` to version `39` by applying
`V39__complete_confirmed_payroll_and_product_classification.sql`.

Runtime after acceptance:

- backend API: `store-analytics-backend:v0.1.0-pilot.15`, healthy;
- backend worker: `store-analytics-backend:v0.1.0-pilot.15`, healthy;
- web: `store-analytics-web:v0.1.0-pilot.15`, healthy;
- embedded source revision for both images: `22fc9c4`;
- previous application release retained for rollback: `v0.1.0-pilot.14-1ee30b6`.

The canonical `/etc/store-analytics/release.env` and release-state `current.env` both point to
`v0.1.0-pilot.15-22fc9c4`.

## Release coordinates

- source commit: `22fc9c4d8cbec87a6f796e41f65ecb89bf53525c`;
- backend image ID: `sha256:ffd5287fd609551bf06df041ff08d08031e9163781c3640a5e085331b0c57f07`;
- web image ID: `sha256:0eaf7f2a88540bbed68ade450c742da0501edd19ae4150b3774fe8f41dc1fb0c`;
- backend archive SHA-256:
  `c4c042062526d53844538ac3057a30b03d60badf619584ceb378eeec02e3cc79`;
- web archive SHA-256:
  `b0ca90024bcc61f0e6219c40ac1e712ab76d7534617702f99f59bbdc7002d148`;
- archive verification: passed on the build host and again on the VPS;
- OCI revision/version label verification: passed before deployment and on running containers.

## Scope

This release completes the customer-confirmed product and payroll classification rules:

- `GLASS_IPHONE` and `GLASS_SAMSUNG` now use payroll category `ACCESSORY`;
- Apple Pencil, Magic Mouse and Magic Keyboard assigned to the approved `IPAD_MAC` analytics
  category resolve to payroll category `TECH_TIER_2`;
- automatic classification recognizes the confirmed service and accessory name variants while
  preserving conservative `UNMAPPED` behavior for ambiguous positions.

No frontend behavior changed relative to `pilot.14`; the web image was reproduced with the new
release identity so both runtime images carry one release coordinate.

## Pre-release evidence

- candidate commit is a descendant of production commit `1ee30b6`;
- full backend `check`: 873 tests, 0 failures; Checkstyle, OpenAPI, supply-chain and
  operator-security gates passed;
- focused classification and migration suite: 50 tests passed;
- production active sync, snapshot, LLM and report jobs: 0;
- pre-release schema version: `38:true`;
- production deployment artifacts matched the candidate checksums;
- verified encrypted backup:
  `postgres/daily/2026/08/17/store-analytics-20260817T181226Z.dump.gpg`.

The backup service completed with `Result=success` and `ExecMainStatus=0`; the upload script also
verified the remote object size.

## Deployment and acceptance evidence

The standard deployment path was used: preloaded immutable images, one-shot migration,
least-privilege database ACL repair, API/worker readiness, web edge replacement and public smoke.

- Flyway validated 39 migrations and applied V39 successfully;
- API, worker and web were independently inspected as `running/healthy`;
- public frontend HTTPS, `/livez` and `/readyz` passed;
- HSTS and `X-Content-Type-Options` checks passed;
- public `/actuator/prometheus` remained unavailable with HTTP 404;
- fresh logs contained 0 ERROR/FATAL entries for API, worker and web;
- final external `/livez` and `/readyz` checks returned `{"status":"UP"}`;
- no application jobs became active during acceptance.

The first HTTPS attempt during the intentional web-container replacement reached the short Caddy
restart window. The deployment smoke retry succeeded, and the independent post-deployment smoke
also passed.

## Classification acceptance

The production current-month source facts were classified again using the effective payroll
rules after V39:

| Store | Effective `UNMAPPED` | Glass positions as `ACCESSORY` | `IPAD_MAC` positions as `TECH_TIER_2` |
|---|---:|---:|---:|
| МАГАЗИН | 0 | 199 | 24 |
| МобиСфера | 0 | 57 | 14 |

Both glass analytics categories were also inspected directly and contain
`payroll_category_code=ACCESSORY`.

The existing August payroll run for `МАГАЗИН` is an immutable pre-V39 snapshot: revision 1 still
records `unmapped_item_count=202` and `calculation_complete=false`. It must be recalculated through
the normal payroll workflow to create a new revision and clear the stale warning. No automatic
business-data recalculation was performed as part of infrastructure deployment. No current-month
stored payroll run was returned for `МобиСфера`.

## Rollback and cleanup

Both backend and web images for `v0.1.0-pilot.14` remain present on the VPS, and its coordinates
are retained in `/var/lib/store-analytics/release-state/previous.env`. V39 is additive and keeps
the prior application schema-compatible for container rollback.

Transferred image archives and the release runner were removed from `/tmp`. The narrowly scoped
temporary sudoers rule and root-owned runner were removed, `/etc/sudoers` validation passed, and
the final `sudo -n true` check failed with `a password is required`, as expected.
