# Store Analytics

Closed analytics cabinet for store managers. LiveSklad data is synchronized into PostgreSQL first;
protected backend APIs then expose KPI, data quality, employee ratings, store plans, work schedules,
payroll revisions, immutable monthly and annual reports, and administration.

Current status on 2026-07-24: the backend is covered by 256 tests. The repository contains a
React + Vite + TypeScript SPA; its verification suite includes ESLint, 24 Vitest tests and a
production build.

## Stack

- Java 21;
- Spring Boot 3.5.16 and Spring Security 6.5.11;
- Gradle Kotlin DSL;
- PostgreSQL 16 and Flyway V1–V11;
- springdoc OpenAPI;
- Docker Compose, JUnit, Testcontainers, Checkstyle and JaCoCo;
- React 19, TypeScript 6, Vite 8, TanStack Query and Zod.

## Repository layout

```text
backend/                 Spring Boot backend
frontend/                Production SPA and its tests
docs/                    Product, API, architecture and operational documentation
scripts/                 Safe discovery/review helpers
outputs/                 Prepared review/import artifacts
docker/                  Docker and deployment helper files
```

## Start reading

- `docs/PROJECT_HANDOFF.md` — verified project state and next steps;
- `docs/audit-log.md` — persistent action coverage and safe metadata contract;
- `docs/error-handling.md` — typed errors, stable codes, correlation IDs and logging boundary;
- `docs/observability.md` — backend metrics, health/readiness and safe build information;
- `docs/reports.md` — immutable monthly/annual reports, revisions and backfill;
- `docs/FRONTEND_HANDOFF.md` — frontend terms, business logic, DTO and endpoint tables;
- `docs/frontend-actions.md` — concrete screens, buttons, enable rules and cache invalidation;
- `docs/frontend-contract.md` — transport and compatibility baseline.

## Local development

Use Java 21 and provide required configuration through the already configured local environment or
secret mechanism. `.env.example` documents variable names only; secrets must never be committed,
printed or included in task context, and `.env` must not be opened during Codex work.

Start the development database:

```bash
docker compose -f docker-compose.dev.yml up -d postgres
```

Run the backend from the repository root:

```bash
./gradlew :backend:bootRun
```

Run the full verification suite:

```bash
./gradlew :backend:check
```

Run the frontend in another terminal:

```bash
cd frontend
npm ci
npm run dev
```

Run its full verification suite with `npm run check`.

The backend uses the `dev` profile by default. Runtime OpenAPI is `/v3/api-docs` and Swagger UI is
`/swagger-ui/index.html`; both require an authenticated `ADMIN` whose temporary password has been
changed.
