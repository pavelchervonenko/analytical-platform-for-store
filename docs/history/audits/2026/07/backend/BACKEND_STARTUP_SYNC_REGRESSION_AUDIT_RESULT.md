---
doc_schema: 1
doc_type: evidence
status: historical
owner: backend
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS_WITH_LIMITS
verdict_scope: "Preserved July 2026 audit evidence; commands and runtime claims require current verification."
source_of_truth:
  - "docs/current/architecture/application.md"
  - "docs/current/integrations/livesklad/synchronization.md"
  - "docs/current/architecture/error-handling.md"
original_content_sha256: 722166137d317af1b706ae460d0ebfd5871ae45360d1c86ae56bf08ad18aa901
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during documentation reform. Current replacements: `docs/current/architecture/application.md`, `docs/current/integrations/livesklad/synchronization.md`, `docs/current/architecture/error-handling.md`.

# Итог ревизии backend startup/sync-регрессий

Дата фиксации: 31 июля 2026 года
Исходный аудит: `BACKEND_STARTUP_SYNC_REGRESSION_AUDIT.md`
Область: backend, sync jobs, LiveSklad, OpenAPI, runtime reproducibility

Тематические документы в `docs/` актуализированы; `docs/archive/legacy-contracts/llm-notifications-design.md` не изменялся. `.env` не открывался. Существующие незакоммиченные изменения не откатывались.

## Результат

| Приоритет | Проблема | Статус | Что сделано и доказательство |
|---|---|---|---|
| P0/P1 | Коллизия `annualReportScheduler` | Исправлено | Инфраструктурный bean переименован в `annualReportTaskScheduler`; добавлен полный startup-тест ролей WORKER/COMBINED с включенными scheduler и запрещенным bean overriding. Проверены восемь специализированных scheduler beans и shutdown контекста. |
| P0/P1 | Provisional identity товара | Исправлено | Добавлен единый `LiveSkladProductIdentityResolver` для classification/sales/returns, connection-scoped lookup, PostgreSQL advisory transaction locks и узкий provisional → actual claim. Проверены оба порядка импорта, разные connections, conflict и конкурентное создание. Миграция V18 → V19 проверена отдельно. |
| P1 | `/cash-items` отклонял missing/null/unknown `type` | Исправлено | Transport DTO стал forward-compatible; fixture покрывает известное, отсутствующее, `null` и будущее неизвестное значение. Бизнес-валидация применяется только к return-позициям. |
| P1 | `/period-quality` возвращал 500 без плана | Исправлено | Исключение как control flow заменено non-throwing `find` API; агрегатор работает в `REPEATABLE_READ`. Unit, controller и Testcontainers transaction regression tests проходят. |
| P1 | Потеря HTTP status/stage/retryability LiveSklad | Исправлено | Добавлены типизированные HTTP/rate-limit/transport/payload failures, безопасные operation/status/error codes и retry taxonomy. `Retry-After` поддерживает секунды и HTTP-date, ограничен одним днем. Чувствительный upstream message не попадает в job summary. |
| P1 | Nullable backend/OpenAPI/TypeScript | Исправлено | `PeriodPlanQualityView.formulaVersion` опубликован как `string | null`; выпущен contract v8, baseline v8 и regenerated TypeScript. Исправлен OpenAPI 3.1 checker, ранее сравнивавший type arrays по ссылке. |
| P1/P2 | Большие периоды и разные пути sync | Исправлено | Contract v9 удаляет четыре синхронных endpoint `/api/sync/stores`, `/api/sync/employees`, `/api/sync/sales`, `/api/sync/returns`. `SyncPanel` и `InitialStoreSetup` создают только durable job; одна кнопка запускает внутренний pipeline `STORES → EMPLOYEES → SALES → RETURNS` с adaptive windows, retry, lease recovery, polling и cancellation. |
| P1/P2 | `WAITING_RETRY` выглядел зависшим | Исправлено | Contract/UI содержат `nextAttemptAt`, `attemptCount/maxAttempts`, phase и safe error summary. Backoff получил bounded exponential delay, детерминированный jitter и конфигурируемый absolute cap. |
| P1/P2 | Cancellation и lease recovery | Исправлено | Проверены PENDING, WAITING_RETRY, RUNNING, terminal transition, exclusivity до safe phase boundary, expired lease recovery и гонка двух workers — claim получает ровно один. Recovery теперь атомарно закрывает связанные `RUNNING sync_runs` как `FAILED` и записывает безопасную диагностику до перехода parent job. Mid-page cancellation/heartbeat остаются отдельным улучшением скорости реакции. |
| P1 | Orphan `sync_run` после SIGKILL | Исправлено | Реальный crash-test выявил child attempt, оставшийся `RUNNING` после восстановления и terminal parent job. Runtime recovery теперь использует pessimistic locks; Flyway V20 исправляет исторические orphan/duplicate attempts и добавляет partial unique index «один RUNNING child на job». Проверены runtime recovery и отдельный upgrade V19 → V20. |
| P1 | Runtime stack расходился с описанием | Исправлено | Фактический стек зафиксирован как Spring Boot 4.1.0 / Java 21; README, Gradle lock и verification metadata согласованы. Docker build переведен с отдельного Gradle 8.14 на repository wrapper 9.0.0. |
| P2 | Docker/build-context и backup-файлы | Исправлено частично | Добавлен `.dockerignore` для dotenv, `*.orig/*.rej/*.bak`, caches/build/node_modules. Security gate запрещает tracked backup artifacts и обход Gradle wrapper. Сами локальные ignored `*.orig` не удалялись. |
| P2 | Demo/preflight | Автоматизировано, E2E ожидает credentials | `prepare-local-demo.sh` проверяет Docker/Compose, readiness, ADMIN и завершённую смену bootstrap-пароля, application identity, contract v9, approved payload shape, допустимые polling-параметры, JSON каждого ответа, известные job statuses и доступный store с timezone/business-day настройками. Retry progress выводится bounded/escaped вместе с `nextAttemptAt` и attempt budget. Полный demo с реальным LiveSklad не запускался; `shellcheck` отсутствует в окружении. |

## Дополнительные исправления

- `Idempotency-Key` для report backfill опубликован обязательным; contract baseline был корректно повышен.
- Trusted proxy/client IP проверен spoofing- и NAT-сценариями.
- Усилены raw payload, JSON complexity/cardinality и request-size limits.
- Password policy оставлена с минимальной длиной 12 символов; добавлены canonicalization, compromised-password blocklist, session и bootstrap hardening.
- Operator-facing sync error summary ограничен 300 символами.
- Gradle wrapper download timeout увеличен с 10 до 60 секунд: чистая Docker-сборка воспроизводимо падала на первоначальной загрузке.
- В dependency verification добавлены только два независимо сверенных SHA-256 для `junit-bom-5.13.3.module` и `opentelemetry-bom-1.49.0.module`. Хэши совпали для Maven Central и Gradle Plugin repository.
- Health/release schema oracle обновлен до фактической V20.

## Выполненные проверки

- OpenAPI checker: 5/5 тестов; generated/current/baseline v9 compatibility gate — успешно.
- Frontend `npm run check`: generated contract check, ESLint, 56/56 Vitest, TypeScript и production Vite build — успешно.
- Durable job unit/integration tests — успешно, включая конкурентный claim двух workers.
- Startup role/scheduler integration tests — успешно.
- Operator security regression suite дополнена проверками demo payload preflight и безопасного `WAITING_RETRY` output; suite проходит.
- Docker Compose v5.0.1 и доступность Docker daemon проверены; фактический approved category payload проходит новый локальный preflight.
- Product identity, classification, sales, returns, V18 → V19 identity migration и V19 → V20 sync-run recovery migration tests — успешно.
- Repository security gate — успешно.
- Gradle supply-chain integrity — успешно: 441 components, 802 artifacts.
- Docker image `store-analytics-backend:codex-audit` собран успешно; runtime Java 21.0.11, user `app`, UID 100, `/app/app.jar` присутствует.
- Реальный LiveSklad E2E выполнен на отдельной PostgreSQL: durable sync, rate-limit retry, идемпотентный повтор, category import, restart/SIGKILL, cancellation, API/auth/security limits и сверка данных.
- Единый полный `backend:check` — успешно: 463 теста, 0 failures, 0 errors; Checkstyle, OpenAPI v9 generation/compatibility, operator security и supply-chain gates прошли в одном run.
- `git diff --check` — успешно.

## Выполненная contract v9 migration

Миграция выполнена атомарно для backend и frontend:

1. Удалены четыре direct controller, два period request DTO и obsolete manual audit adapter; доменные sync services остались исполнителями job.
2. `ManualSyncPanel` и direct API functions удалены. `SyncPanel` показывает одну кнопку, период, четыре последовательных этапа, job history и cancellation.
3. Empty-store bootstrap создаёт короткий durable job и опрашивает его вместо прямого `syncStores`.
4. Выпущены `ApiContractVersion=9`, immutable baseline v9, current OpenAPI и regenerated TypeScript. Consumer test требует durable endpoint и запрещает публикацию четырёх старых paths.

## Что осталось

1. Если нужна более быстрая RUNNING-cancellation, добавить cancellation checkpoints между страницами/detail calls и heartbeat/lease renewal. Текущее поведение безопасное, но кооперативное на границе фазы.
2. Установить `shellcheck` и прогнать им `scripts/prepare-local-demo.sh` и security scripts; `bash -n` и executable regression suite уже проходят.
3. После штатного restart тестового backend повторить короткий SIGKILL smoke уже на бинарнике с V20; runtime и upgrade regression tests автоматизированы и проходят.
4. При желании удалить локальные ignored `*.orig` после ручного подтверждения, что они больше не нужны. Они не tracked и не попадают в Docker context.
## Production-граница

Известные P0 backend-дефекты аудита закрыты; divergent manual sync paths устранены contract v9. Оставшиеся пункты относятся к скорости кооперативной отмены, реальному интеграционному smoke/data reconciliation и стабильности инфраструктуры тестового запуска.
