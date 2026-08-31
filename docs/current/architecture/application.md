---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/architecture.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics
  - backend/src/main/resources/application.yml
  - backend/build.gradle.kts
verification_sources:
  - backend/src/test/java/com/storeanalytics/BackgroundSchedulingArchitectureTest.java
  - backend/src/test/java/com/storeanalytics/ApplicationSchedulingRolesIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/ApplicationApiRoleIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
review_triggers:
  - backend-module-change
  - runtime-role-change
  - scheduler-change
supersedes:
  - docs/architecture.md
superseded_by: null
---

# Архитектура приложения

## Назначение и границы

Документ описывает статическую архитектуру приложения и обязанности runtime-ролей. Он не
утверждает, какой релиз, образ, флаг или роль фактически запущены в production: такое состояние
фиксируется только в [`../project-state.md`](../project-state.md).

## Состав системы

Репозиторий содержит React/Vite/TypeScript SPA и один Spring Boot backend. Backend организован
package-by-feature; основными доменами являются `auth`, `store`, `employee`, `product`, `sales`,
`sync`, `metrics`, `performance`, `salary`, `report`, `quality`, `audit`, `maintenance` и
`integration`.

Публичные HTTP DTO принадлежат входному адаптеру `web`, JPA-модель и persistence — домену,
внешние transport DTO — соответствующему `integration/*`. Метрики, payroll и отчёты читают
нормализованные данные PostgreSQL и не обращаются к LiveSklad напрямую.

## Runtime-роли

`app.runtime.role` принимает четыре значения:

| Роль | Обязанность |
|---|---|
| `API` | Интерактивный HTTP без scheduled/background work |
| `WORKER` | Фоновые очереди, планировщики и operational probes |
| `COMBINED` | Совмещённый контур для локальной разработки |
| `MIGRATION` | Одноразовый Flyway-запуск без web/JPA/schedulers |

Неизвестная роль останавливает startup. В API/WORKER Flyway не применяет миграции: runtime
проверяет ожидаемую packaged-схему read-only. Scheduled-компоненты разрешены только в `WORKER` и
`COMBINED`, что защищено архитектурным тестом.

## Границы выполнения

- HTTP error mapping централизован в `common.web`; feature-код выбрасывает типизированные
  business exceptions.
- Фоновые семейства используют раздельные concurrency-one scheduler-ы, чтобы sync, reports,
  retention и probes не занимали один и тот же поток.
- Высокорисковые команды используют транзакционную идемпотентность и сохраняют receipt вместе с
  бизнес-изменением.
- Durable jobs используют БД-состояние, lease и bounded retry; HTTP-запрос не является владельцем
  длительной операции.
- Runtime-сессии пока process-local. Масштабирование API выше одной реплики требует общего Spring
  Session registry; иначе revoke и concurrent-session limit расходятся между процессами.

## Слои данных

1. Версионированные raw-наблюдения внешнего источника.
2. Нормализованные operational facts.
3. Снимки классификации и финансовых атрибутов позиции.
4. Immutable rating, payroll и report revisions.
5. Durable job/inbox/outbox lifecycle records.

Подробный контракт — в [`database.md`](database.md), миграционный — в
[`migrations.md`](migrations.md), HTTP boundary — в [`error-handling.md`](error-handling.md).

## Подтверждённые ограничения

- `ProjectStructureTest` не является реальной защитой package boundaries. До появления
  ArchUnit/эквивалентного теста граница package-by-feature поддерживается review, а не CI.
- Наличие адаптера или scheduler-а в коде не доказывает его включение в production.
- Репозиторные health/metrics-компоненты не доказывают подключённый alert routing.

## Проверка

Роли и запрет scheduler-ов в API проверяются `BackgroundSchedulingArchitectureTest`,
`ApplicationSchedulingRolesIntegrationTest` и `ApplicationApiRoleIntegrationTest`. Изменение
runtime-role, scheduler family или package ownership требует обновить документ и соответствующие
тесты в одном изменении.
