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
  - docs/archive/legacy-contracts/database-design.md
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - backend/src/main/resources/db/migration
  - backend/src/main/java/com/storeanalytics/common/database/ExpectedSchemaVersion.java
  - deploy/bin/deploy.sh
  - deploy/bin/rollback.sh
  - deploy/bin/forward-fix.sh
verification_sources:
  - backend/src/test/java/com/storeanalytics/common/database/ExpectedSchemaVersionTest.java
  - backend/src/test/java/com/storeanalytics/MigrationApplicationIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - operations
review_triggers:
  - migration
  - schema-compatibility-change
  - rollback-script-change
supersedes: []
superseded_by: null
---

# Flyway и совместимость схемы

## Действующий контракт

Backend вычисляет максимальную ожидаемую schema version из packaged migration resources. В
source-tree максимальная версия — V48; цепочка также содержит V39.1, которая сортируется между V39
и V40. Runtime API/WORKER не мигрирует БД и должен только read-only проверить фактическую историю.

Существенные поздние изменения:

| Версия | Смысл |
|---|---|
| V39.1 | Первое создание LiveSklad webhook inbox для совместимости веток |
| V42 | Повторяет inbox DDL через `IF NOT EXISTS`; не является единственным источником таблицы |
| V43 | Lease/retry/terminal state и orphan return до появления исходной продажи |
| V44 | Audited, idempotent validated return recovery |
| V45–V48 | Weekly-review snapshots, AI enrichment/jobs/attempts и contract hardening |

## Политика изменения

- Миграции forward-only; опубликованный migration file не переписывается.
- Application rollback не откатывает Flyway.
- Предыдущий runtime можно запускать только на явно разрешённой ему schema.
- При несовместимости требуется reviewed forward-fix или восстановление из проверенного backup,
  а не ручное редактирование `flyway_schema_history`.
- Любая новая migration обновляет schema oracle, migration tests и release compatibility metadata.

## Что доказано

Fresh-schema tests применяют всю packaged-цепочку к PostgreSQL 16. Есть representative populated
upgrade tests, включая поздние weekly-review migrations. `ExpectedSchemaVersionTest` проверяет
oracle packaged version.

## Что не доказано

- Не существует полного populated upgrade matrix из каждой V1–V47 в V48.
- Downgrade V48 в предыдущую schema не реализован и не репетировался.
- Release scripts используют локальный state-файл для compatibility decision. После failed
  migration marker `MIGRATION_IN_PROGRESS` нельзя автоматически примирить с реальным
  `flyway_schema_history`; штатный recovery runbook для этого ещё не подтверждён.
- Нельзя считать локальный state-файл доказательством фактической версии БД.

Поэтому документ описывает только forward compatibility и не разрешает production recovery.
Операторская процедура migration failure должна оставаться draft, пока не появятся staging и
production-read-only evidence по правилам documentation policy.
