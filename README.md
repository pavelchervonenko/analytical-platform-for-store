# Store Analytics

Закрытая аналитическая платформа для руководителей магазинов. Данные LiveSklad сначала
нормализуются в PostgreSQL, после чего backend предоставляет KPI, качество данных, показатели
сотрудников, планы и смены, зарплату, отчеты, ИИ-разбор и административные операции.

Текущее проверенное production-состояние хранится только в
[docs/current/project-state.md](docs/current/project-state.md). Корневой README намеренно не
копирует release, schema, image digests или feature flags, чтобы не становиться вторым
противоречащим источником.

## Стек

- Java 21, Spring Boot 4.1.0 и Spring Security 7.1.0;
- PostgreSQL 16, Flyway и Hibernate validation;
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

Начинать с [индекса документации](docs/README.md). Ключевые точки:

- [current project state](docs/current/project-state.md) — единственная сводка проверенного runtime;
- [политика документации](docs/maintenance/documentation-policy.md) — источники истины и lifecycle;
- [план реформы](docs/maintenance/documentation-reform-plan.md) — текущий прогресс консолидации;
- [полный реестр](docs/maintenance/documentation-inventory.md) — судьба каждого старого материала.

Документы с датой/версией и старые handoff/status-файлы являются историческими снимками. Их нельзя
использовать вместо current-контракта или актуального runbook.

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
