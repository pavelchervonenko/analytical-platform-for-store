# Store Analytics

Закрытая аналитическая платформа для руководителей магазинов. Данные LiveSklad сначала
нормализуются в PostgreSQL, после чего backend предоставляет KPI, качество данных, показатели
сотрудников, планы и смены, зарплату, отчеты, ИИ-разбор и административные операции.

Актуальность: 2026-08-24.

## Текущее состояние

- production: `v0.1.0-pilot.22`, commit `2e8f9c2`, Flyway `V44`;
- production уже использует раздельные `backend-api`, `backend-worker` и `web`, HTTPS/Caddy и
  управляемый PostgreSQL 16;
- прием возвратных webhook LiveSklad и worker возвратов продаж включены;
- worker возвратов заказов остается выключенным до canary настоящего `ORDER_RETURN`;
- текущий релиз-кандидат проверен локально, но еще не отправлен и не развернут;
- `v21/schema3` прошла семантическую оценку, однако production default ИИ пока `v4/schema2`.

Точный состав кандидата: [docs/RELEASE_CANDIDATE_2026-08-24.md](docs/RELEASE_CANDIDATE_2026-08-24.md).

Последний полный кандидатный прогон:

- backend: `925` тестов без failures/errors/skipped;
- frontend: `38` файлов / `143` теста, ESLint, contract check и production build;
- ИИ: `58` unit tests и `26/26` семантических кейсов;
- Checkstyle, OpenAPI compatibility, security, supply-chain и release-safety checks;
- локальная визуальная проверка измененных страниц на desktop/tablet/mobile.

Эти результаты относятся к текущему кандидату и не означают, что он уже находится в production.

## Стек

- Java 21, Spring Boot 4.1.0 и Spring Security 7.1.0;
- PostgreSQL 16, Flyway `V1–V44`, Hibernate validation;
- Gradle Kotlin DSL, JUnit, Testcontainers, Checkstyle и JaCoCo;
- React 19, TypeScript 6, Vite 8, TanStack Query и Zod;
- Docker Compose и Caddy для production.

## Структура репозитория

```text
backend/                 Spring Boot API, workers, migrations and tests
frontend/                React SPA, unit/e2e/visual tests
docs/                    Current contracts, runbooks and historical audits
scripts/                 Verification and operator tooling
deploy/                  Production Compose and deployment support
docker/                  Local/development container assets
```

## Документация

Начинать с [docs/README.md](docs/README.md). Основные точки входа:

- [docs/PROJECT_HANDOFF.md](docs/PROJECT_HANDOFF.md) — состояние системы и открытые границы;
- [docs/FRONTEND_HANDOFF.md](docs/FRONTEND_HANDOFF.md) — актуальный UI-контракт;
- [docs/livesklad-webhook-receiver.md](docs/livesklad-webhook-receiver.md) — возвратные webhook;
- [docs/validated-return-recovery-runbook.md](docs/validated-return-recovery-runbook.md) —
  восстановление подтвержденных пропущенных возвратов;
- [docs/production-deployment-runbook.md](docs/production-deployment-runbook.md) — release/rollback;
- [docs/AI_INTERPRETATION_V21_WEEKLY_CANARY_2026-08-17.md](docs/AI_INTERPRETATION_V21_WEEKLY_CANARY_2026-08-17.md) —
  статус `v21/schema3`.

Файлы с датой/версией в имени являются историческими снимками. Для текущих действий нельзя
использовать старый release note вместо актуального runbook.

## Локальная разработка

Требуются Java 21, Node.js из диапазона `engines` в `frontend/package.json` и Docker.

Запустить PostgreSQL:

```bash
docker compose -f docker-compose.dev.yml up -d postgres
```

Backend:

```bash
./gradlew :backend:bootRun
./gradlew :backend:check
```

Frontend:

```bash
cd frontend
npm ci
npm run dev
npm run check
```

Для материального UI-изменения дополнительно выполнить локальный `npm run visual:local`, задать
`VISUAL_ROUTES` только для затронутых страниц и вручную просмотреть desktop/tablet/mobile снимки
в `frontend/visual-artifacts/`. Нельзя направлять visual check на production или staging.

Runtime OpenAPI: `/v3/api-docs`; Swagger UI: `/swagger-ui/index.html`. Оба интерфейса защищены.
Секреты и учетные данные передаются только через окружение/secret storage и не фиксируются в
репозитории, документации, screenshots или shell history.
