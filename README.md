# Store Analytics

Closed analytics cabinet for store managers. LiveSklad data is synchronized into PostgreSQL first;
protected backend APIs then expose KPI, data quality, employee ratings, store plans, work schedules,
payroll revisions, immutable monthly and annual reports, and administration.

Current application status on 2026-08-06: the complete backend check passes with 674 tests in
239 suites and no failures/errors/skips. A real local test database migrated V29→V30, both existing
report revisions passed integrity verification and authenticated API/SPA acceptance. Browser
acceptance traversed the principal user and ADMIN sections on desktop, tablet and mobile, including
the complete test MANAGER lifecycle, without page overflow, HTTP 500 or runtime errors. The React +
Vite + TypeScript SPA also passes ESLint, 25 Vitest files / 90 tests, generated-contract verification
and a production build.

This is application-level acceptance, not production launch approval. Caddy/TLS/trusted proxy,
immutable image promotion, production database roles/secrets, backup/restore, monitoring, rollback
and staging-through-domain acceptance remain mandatory in `docs/production-deployment-runbook.md`.
The YandexGPT 5.1 weekly interpretation path has separately passed a real end-to-end acceptance run
on aggregated pseudonymized metrics; production LLM/Telegram feature flags remain disabled until
server-side staging and operational drills are complete.

## Stack

- Java 21;
- Spring Boot 4.1.0 and Spring Security 7.1.0;
- Gradle Kotlin DSL;
- PostgreSQL 16 and Flyway V1–V30;
- springdoc OpenAPI 3.0.3 and native Jackson 3;
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
- `docs/authentication-api.md` — session, CSRF, password and self-service revocation contract;
- `docs/audit-log.md` — persistent action coverage and safe metadata contract;
- `docs/error-handling.md` — typed errors, stable codes, correlation IDs and logging boundary;
- `docs/observability.md` — backend metrics, health/readiness and safe build information;
- `docs/resource-limits.md` — API body/cardinality budgets, bulkheads and staging load acceptance;
- `docs/supply-chain-security.md` — Gradle wrapper and dependency integrity baseline;
- `docs/data-retention.md` — technical-data lifetimes, rollups and safe rollout procedure;
- `docs/reports.md` — immutable monthly/annual reports, revisions and backfill;
- `docs/FRONTEND_HANDOFF.md` — frontend terms, business logic, DTO and endpoint tables;
- `docs/FRONTEND_ACCEPTANCE.md` — verified browser scenarios, viewport coverage and remaining risks;
- `docs/frontend-actions.md` — concrete screens, buttons, enable rules and cache invalidation;
- `docs/frontend-contract.md` — transport and compatibility baseline;
- `docs/telegram-linking-and-webhook.md` — безопасная привязка аккаунта и webhook boundary;
- `docs/telegram-delivery-worker.md` — durable Telegram delivery и operator recovery;
- docs/telegram-staging-acceptance.md — обязательный staging preflight перед production;
- docs/llm-production-operations.md — порядок приёмки, включения и остановки LLM-контура;
- docs/yandexgpt-staging-acceptance.md — фактический acceptance и server-side production gate YandexGPT;
- docs/llm-response-validation.md — граница фактов, safety-нормализация и validation retry;
- docs/daily-store-pulse.md — отдельная ежедневная backend-проекция и утренняя сводка.

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
