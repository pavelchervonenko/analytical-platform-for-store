# Store Analytics frontend

Production SPA for the closed manager cabinet. The static prototype remains in `prototype/` as a
visual reference and is not imported by this application.

## Stack

- React, Vite and strict TypeScript;
- React Router for client routes;
- TanStack Query for server state and request deduplication;
- Zod validation at the backend transport boundary;
- cookie session with an in-memory CSRF configuration; no bearer token or session storage.

## Application boundaries

- `/overview`, `/employees` and `/employees/:employeeId` accept month, ISO week or a custom
  inclusive date range. The range is URL-owned and comparisons remain backend-owned.
- `/plan`, `/payroll` and `/quality` always use the separately retained calendar `month`; custom
  analytics ranges never change the meaning of monthly workflows.
- `/quality` maps corrections only from stable backend `recommendedAction` values. Unsupported
  mutations, such as manual cost repair, remain explicit diagnostics instead of fake controls.
- `/admin` is role-gated in the router and split into users/access, durable synchronization jobs,
  immutable scheme versions and effective-dated payroll classification. Backend authorization is
  still authoritative for every request.
- Query keys contain every store, period, month or resource identifier that affects server state.
  Mutations invalidate dependent authoritative queries instead of recalculating business data in
  the browser.

## Development

Use Node.js 20.19 or newer. From `frontend/`:

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

The browser never calls LiveSklad. Business formulas, ranks, payroll totals and quality statuses
remain backend-owned authoritative values.
