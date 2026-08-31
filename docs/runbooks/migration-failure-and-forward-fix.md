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
operation_type: recovery
environments:
  - staging
  - production
risk_level: critical
source_of_truth:
  - deploy/bin/deploy.sh
  - deploy/bin/forward-fix.sh
  - deploy/bin/release-safety.sh
  - backend/src/main/resources/db/migration
verification_evidence:
  - level: static
    scope: marker behavior and forward-fix compatibility checks
    verified_at: 2026-08-31
    evidence: scripts/tests/deploy-release-safety-test.sh
required_reviewers:
  - operations
  - backend
  - security-privacy
review_triggers:
  - migration
  - migration-failure
  - release-state-change
supersedes: []
superseded_by: null
---

# Сбой миграции и forward-fix

## Цель и область

Безопасно локализовать неуспешную migration и выбрать reviewed forward-fix либо isolated restore.
Текущая версия документа не разрешает вручную исправлять Flyway history или release-state.

## Влияние и требуемая авторизация

Recovery затрагивает production schema и writers; требуется incident commander, operations и
backend migration reviewer. Restore дополнительно требует customer/backup owner.

## Предусловия и критерии остановки

- Остановить rollout; не стартовать worker и не выполнять повторную migration.
- Сохранить logs, candidate/current env hashes и timestamps без secret values.
- Остановиться, если exact DB/host неизвестны, backup недоступен или изменение Flyway history не
  объяснено reviewed migration.
- Никогда не менять `database-schema-version` и `flyway_schema_history` «для разблокировки».

## Preflight и точный target

Exact target: incident ID, host fingerprint, DB host/name, failed release/digest, expected source и
target schema. Read-only ролью получить ordered Flyway history: version, description, installed_at,
success и checksum. Проверить состояние API/worker и отключить writers только по incident decision.

## Процедура

1. Классифицировать: migration не запускалась; transaction откатилась; non-transactional/partial
   effect; migration успешна, но state marker не обновлён.
2. Сравнить live schema/history с migration SQL и backup checkpoint.
3. Подготовить новую immutable image с новой forward-only migration; не редактировать опубликованный
   migration файл.
4. Проверить forward-fix на clone/isolated restore из того же source state.
5. Только после review выполнить `forward-fix.sh <exact-forward-fix-env>`.
6. Подтвердить Flyway version, schema invariants, ACL, API/worker readiness и business reconciliation.

## Повторный запуск и конкурентность

Параллельные deploy/migration/recovery запрещены. После неизвестного обрыва новый запуск допускается
только после повторного live inspection.

## Rollback или forward-fix

Flyway undo отсутствует. Container rollback разрешён лишь если live schema совместима. Partial или
повреждённая schema требует forward-fix либо restore в новый DB target с последующим controlled
switch; in-place speculative repair запрещён.

## Evidence и известные пробелы

Сохранить sanitized Flyway rows/checksums, image/release IDs, decision, before/after invariants и
reconciliation. Критический пробел: штатные `rollback.sh` и `forward-fix.sh` требуют числовой
state-файл и не умеют безопасно восстановить его из live Flyway history после
`MIGRATION_IN_PROGRESS`. До реализации и rehearsal runbook остаётся draft.
