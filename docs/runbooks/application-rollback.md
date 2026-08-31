---
doc_schema: 1
doc_type: runbook
status: draft
owner: operations
audience:
  - operator
last_verified: 2026-08-31
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - staging
  - production-read-only
operation_type: reversible-write
environments:
  - staging
  - production
risk_level: high
source_of_truth:
  - deploy/bin/rollback.sh
  - deploy/bin/release-safety.sh
  - deploy/compose.production.yml
verification_evidence:
  - level: static
    scope: previous release and runtime-schema compatibility refusal
    verified_at: 2026-08-31
    evidence: scripts/tests/deploy-release-safety-test.sh
required_reviewers:
  - operations
  - backend
review_triggers:
  - rollback-change
  - migration
  - compatibility-range-change
supersedes: []
superseded_by: null
---

# Rollback application containers

## Цель и область

Вернуть previous API/worker/web images только на совместимой database schema. Процедура не
откатывает Flyway и не восстанавливает данные.

## Влияние и требуемая авторизация

Перезапускаются application services; background jobs прерываются. Требуются operations owner и
подтверждение backend reviewer о совместимости previous runtime.

## Предусловия

- `previous.env` и `database-schema-version` существуют, regular/root-owned и относятся к exact host.
- Live Flyway version успешно сверена read-only и совпадает с числовым state.
- Previous images доступны по immutable digests; current incident не связан с corruption данных.

## Критерии остановки

- State равен `MIGRATION_IN_PROGRESS`, отсутствует или расходится с live Flyway.
- Previous runtime range не включает live schema.
- Есть failed migration, data corruption или неизвестный write после несовместимого schema change.

## Preflight и точный target

Зафиксировать incident/change ID, host, DB, current/previous release и digests, live schema и
previous runtime min/max. Выполнить preflight предыдущего env без вывода secret values.

## Процедура

1. Подтвердить stop criteria и отсутствие параллельного deploy.
2. Запустить один раз: `sudo /opt/store-analytics/deploy/bin/rollback.sh`.
3. Скрипт поднимает previous API, затем worker, затем web и выполняет smoke.
4. Проверить actual image digests, health, queues и инцидентный бизнес-инвариант.

## Повторный запуск и конкурентность

Повтор до определения текущих container/state запрещён. Jobs должны быть idempotent/leased; их
повторная обработка проверяется отдельно.

## Rollback или forward-fix

Это и есть application rollback. Если скрипт отказал по schema compatibility, обход запрещён:
выпускается compatible forward-fix. Restore применяется только при доказанном повреждении данных.

## Evidence и известные пробелы

Сохранить before/after releases/digests, live schema, health, smoke и причины решения. Нет полной
staging rehearsal для каждой поддерживаемой schema pair; state-файл остаётся дополнительным, а не
authoritative источником live schema. Поэтому status — draft.
