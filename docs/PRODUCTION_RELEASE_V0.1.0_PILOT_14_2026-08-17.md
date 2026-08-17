# Production release `v0.1.0-pilot.14` — 2026-08-17

## Result

Release `v0.1.0-pilot.14-1ee30b6` was deployed successfully to the pilot production VPS on
2026-08-17. The application remained on Flyway schema version `38`; this release contained no
database migration.

Runtime after acceptance:

- backend API: `store-analytics-backend:v0.1.0-pilot.14`, healthy;
- backend worker: `store-analytics-backend:v0.1.0-pilot.14`, healthy;
- web: `store-analytics-web:v0.1.0-pilot.14`, healthy;
- embedded source revision for both images: `1ee30b6`;
- previous application release retained for rollback: `v0.1.0-pilot.13-06c24ac`.

The canonical `/etc/store-analytics/release.env` and release-state `current.env` both point to
`v0.1.0-pilot.14-1ee30b6`.

## Release coordinates

- source commit: `1ee30b6df72e9c9a89ac539d7667fcd18c8a5f3f`;
- backend archive image digest: `sha256:8c9c41a975ad2734f5058bf97d1ecd01f26ad55c3bc2971e9f4dc347b9c61880`;
- web archive image digest: `sha256:572d4eb5e964c6fdd7d6fa4d122de1ab9380f6926d4664ea61638ceec6bc1b69`;
- archive SHA-256 verification: passed on the build host and again on the VPS;
- OCI revision/version label verification: passed before deployment and on running containers.

## Scope and AI activation decision

The code includes the bounded weekly AI interpretation work through prompt/contract generation
`v21` and its evaluation tooling. Production activation was intentionally not changed during this
release:

- active prompt version: `weekly-interpretation-v4`;
- active content schema version: `2`;
- generation/publication flags and all existing production secrets were copied unchanged.

The `v21` automatic 26-scenario matrix passed `26/26` with no provider or semantic failures and an
observed provider cost of `29.3576 RUB`. Its blinded manual review was not finalized, so switching
the production default to `v21` remains a separate, controlled change.

## Pre-release evidence

- backend `check`: 864 tests, 0 failures; OpenAPI, Checkstyle, supply-chain and operator-security
  gates passed;
- frontend `check`: contracts, lint, 123 tests in 34 files and production build passed;
- LLM evaluator/review Python tests: 58 passed;
- production active sync jobs/runs: 0;
- production active snapshot jobs: 0;
- production active/running LLM jobs: 0;
- pre-release schema version: 38;
- verified encrypted backup:
  `postgres/daily/2026/08/17/store-analytics-20260817T122333Z.dump.gpg`.

The backup service finished with `Result=success` and `ExecMainStatus=0`; the upload script also
verified the remote object size.

## Deployment and acceptance evidence

The standard deployment path was used: preloaded immutable images, one-shot migration validation,
least-privilege database ACL repair, API/worker readiness, web edge restart and public smoke.

- Flyway validated all 38 migrations and reported that schema `app` was up to date;
- API, worker and web were independently inspected as `running/healthy`;
- public frontend HTTPS, `/livez` and `/readyz` passed;
- HSTS and `X-Content-Type-Options` checks passed;
- public `/actuator/prometheus` remained unavailable with HTTP 404;
- fresh 15-minute logs contained 0 ERROR/FATAL entries for API, worker and web;
- final readiness after cleanup returned `{"status":"UP"}`.

The first HTTPS attempt during the intentional web-container replacement reached the short Caddy
restart window. The deployment smoke retry then succeeded, and the independent post-deployment
smoke also passed without retry-visible failure.

## Rollback and cleanup

Both backend and web images for `v0.1.0-pilot.13` were confirmed to remain present on the VPS.
Rollback coordinates are stored in `/var/lib/store-analytics/release-state/previous.env`.

For these locally preloaded, tag-only images, copy `previous.env` to a separate root-owned 0600
release-env before passing it to `deploy.sh`. Do not pass `previous.env` directly to `deploy.sh`,
because the deploy script rotates `current.env` into that path before consuming the candidate.
The current `rollback.sh` also performs an unconditional registry pull, so it should only be used
after registry availability for these exact image references has been confirmed.

All transferred archives and release helper scripts were removed from `/tmp`. The temporary
`/etc/sudoers.d/store-analytics-release-temporary` rule was removed, the sudoers configuration was
validated, and a final `sudo -n true` check failed with `a password is required`, as expected.
