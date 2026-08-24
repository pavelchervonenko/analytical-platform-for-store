# Store Analytics frontend

Production SPA for the closed manager cabinet. The static prototype remains in `prototype/` as a
visual reference and is not imported by this application.

Current verification on 2026-08-24: `38` Vitest files / `143` tests, ESLint, generated-contract
check and production build pass. The current un-deployed candidate has local desktop/tablet/mobile
visual evidence for `/overview`, `/plan` and `/insights`.

## Stack

- React, Vite and strict TypeScript;
- React Router for client routes;
- TanStack Query for server state and request deduplication;
- Zod validation at the backend transport boundary;
- generated transport types from the versioned backend OpenAPI artifact;
- cookie session with an in-memory CSRF configuration; no bearer token or session storage.

## Application boundaries

- `/overview`, `/employees` and `/employees/:employeeId` accept month, ISO week or a custom
  inclusive date range. The range is URL-owned and comparisons remain backend-owned.
- `/plan`, `/payroll` and `/quality` always use the separately retained calendar `month`; custom
  analytics ranges never change the meaning of monthly workflows.
- `/quality` maps corrections only from stable backend `recommendedAction` values. Unsupported
  mutations, such as manual cost repair, remain explicit diagnostics instead of fake controls.
- `/reports` reads immutable monthly and annual snapshots with server-side year/type filters;
  report revisions and payload totals are displayed exactly as returned by the backend.
- `/admin` is role-gated in the router and split into users/access, durable synchronization jobs,
  guarded manual synchronization, report archive recovery, immutable scheme versions,
  effective-dated payroll classification and the bootstrap category import. Backend authorization
  remains authoritative for every request.
- `/insights` is a feature-gated weekly AI interpretation preview; `/profile` owns active sessions and
  the user-facing Telegram connection lifecycle. ADMIN LLM/Telegram operations remain available only
  behind backend feature flags and operational permissions.
- The UI follows the Apple-inspired hierarchy while retaining translucent liquid-glass navigation.
  Responsive behavior is regression-tested at desktop `1440x1000`, tablet `768x1024` with touch and
  Pixel 7 mobile sizes.
- Query keys contain every store, period, month or resource identifier that affects server state.
  Mutations invalidate dependent authoritative queries instead of recalculating business data in
  the browser.

- Safe API errors keep diagnostics inside the transport layer; correlation IDs, raw proxy details and
  server internals are not rendered to store managers.

## Development

Use Node.js 22.22 or newer. From `frontend/`:

```bash
npm ci
npm run dev
```

The Vite server proxies `/api` to `http://127.0.0.1:8080` by default. Override only the non-secret
development target through `DEV_API_TARGET`. Production must serve the SPA and `/api` from the same
origin so the CSRF cookie can be read and sent safely.

## Verification

```bash
npm run check
```

The regular check first regenerates transport types into a temporary directory and rejects drift
from `src/api/generated/`.

For browser smoke checks, install Chromium once and run the E2E suite against a running backend:

```bash
npm run e2e:install
E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... npm run e2e
```

Add `E2E_MANAGER_EMAIL` and `E2E_MANAGER_PASSWORD` to verify the permanent MANAGER role
boundary. Secrets must come from the shell or the CI secret store; they are never kept in repository
files. Without credentials, Playwright still verifies the anonymous route guard. Credentialed runs
use one worker so desktop/tablet/mobile projects do not race on the same server-side session.

Set `E2E_BASE_URL` to test an already deployed same-origin environment; otherwise Playwright starts
the local production preview on `127.0.0.1:4174`, which proxies `/api` to `DEV_API_TARGET`.

### Local visual review

The visual review command is deliberately separate from production E2E and accepts only loopback
hosts. By default it creates a fresh production build and starts its local Vite preview on
`127.0.0.1:4174`; API requests continue to use the local backend on `127.0.0.1:8080`. The
dedicated preview prevents an older development server from being reused by mistake.

```bash
cp .env.visual.example .env.visual.local
# Fill VISUAL_EMAIL and VISUAL_PASSWORD with local account credentials.
npm run visual:local
```

The default route is `/insights`. Capture any set of affected application routes with a
comma-separated value:

```bash
VISUAL_ROUTES='/overview,/insights,/employees' npm run visual:local
```

Use an already running local frontend when needed:

```bash
VISUAL_BASE_URL=http://localhost:5173 npm run visual:local
```

The command captures full-page desktop, tablet and mobile images in `visual-artifacts/`, verifies
page-level overflow, query errors, browser runtime errors and HTTP `5xx` responses. The directory
is ignored by Git because images can contain business data. Inspect the images after every material
UI change. For an interactive local browser run, use `npm run visual:local:headed`.

The local-only guard rejects production, staging and every other non-loopback `VISUAL_BASE_URL`
or `DEV_API_TARGET`.

The deeper MANAGER lifecycle creates and later disables a test account. Run it only against a local
or staging database prepared for mutations:

```bash
E2E_MUTATING=true E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... npx playwright test e2e/live-acceptance.spec.ts --project=desktop-chromium --grep 'MANAGER'
```

Exact scope, latest results, intentional skips and known risks are recorded in
`docs/FRONTEND_ACCEPTANCE.md`.

Failure artifacts contain screenshots and traces but no video. Treat them as access-controlled CI
artifacts because rendered business data can be sensitive.

When the backend contract changes intentionally:

```bash
./gradlew -p backend generateOpenApi
cp backend/build/openapi/current.json contracts/openapi/current.json
cd frontend
npm run contracts:generate
npm run check
```

Released files under `contracts/openapi/baselines/` are immutable. A compatible change updates
only `current.json`; an intentional incompatible change creates a new versioned baseline and
updates `ApiContractVersion.CURRENT`. Run `./gradlew -p backend checkOpenApiCompatibility` from
the repository root to verify both backend artifact drift and breaking changes.

React Router is pinned to 8.3.0, the first release patched for `GHSA-qwww-vcr4-c8h2`.
Dependency audit is part of production readiness and must remain free of high-severity runtime
findings.

The browser never calls LiveSklad. Business formulas, ranks, payroll totals and quality statuses
remain backend-owned authoritative values.
