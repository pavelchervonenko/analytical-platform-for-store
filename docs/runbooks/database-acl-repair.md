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
  - deploy/bin/repair-production-database-acls.sh
  - backend/src/main/resources/db/migration
verification_evidence:
  - level: static
    scope: intended grants, revokes, default privileges and role search_path
    verified_at: 2026-08-31
    evidence: backend/src/test/java/com/storeanalytics/common/database/MigrationLeastPrivilegeIntegrationTest.java
required_reviewers:
  - operations
  - security-privacy
  - backend
review_triggers:
  - database-role-change
  - migration
  - acl-script-change
  - database-target-change
supersedes: []
superseded_by: null
---

# Восстановление database ACL

## Цель и область

Вернуть least-privilege grants для `store_runtime`, `store_migrator` и `store_backup_reader` после
migration/role drift. Процедура не создаёт роли и не меняет пароли.

## Влияние и требуемая авторизация

Скрипт изменяет role search paths, grants/revokes и default privileges в schema `app`. Требуются
database owner, operations и security reviewer.

## Предусловия

- Exact database и три существующие роли подтверждены read-only.
- Migrator/runtime/backup password files относятся к target и mode `0600`.
- Нет активной migration; application impact от revokes оценён.

## Критерии остановки

- Любая target переменная не задана явно или совпадает только с hardcoded default скрипта.
- DB certificate/host/name или role membership не совпадает с change record.
- Обнаружены дополнительные grants/owners, назначение которых не исследовано.
- Нет staging rehearsal для текущей PostgreSQL/schema версии.

## Preflight и точный target

Record: DB cert host/hostaddr/port/name, schema, role names, release/schema и secret path basenames.
Read-only запросами сохранить owners, role attributes/memberships, schema/table/sequence/function
privileges и default ACL. Секреты и password hashes не выводить.

## Процедура

1. Явно передать скрипту все DB target и password/CA file variables из reviewed change record; не
   полагаться на defaults.
2. Запустить `repair-production-database-acls.sh` один раз.
3. Повторить read-only ACL snapshot и сравнить только с approved least-privilege matrix.
4. Проверить API/worker readiness и одну read/write application transaction; backup role — только
   SELECT, runtime — без CREATE/TRUNCATE, public — без function execute.

## Повторный запуск и конкурентность

GRANT/REVOKE/ALTER DEFAULT PRIVILEGES идемпотентны в ожидаемой модели, но повтор запрещён при
неизвестном target или параллельной migration.

## Rollback или forward-fix

Rollback — применить заранее снятый reviewed ACL snapshot, а не выдавать broad privileges. При
ошибке модели исправляется script/migration и повторяется точечный forward-fix.

## Evidence и известные пробелы

Сохранить before/after ACL matrix, target/release/schema и functional checks. Скрипт содержит
infrastructure-specific defaults и не читает target из проверенного release env; до исправления и
staging rehearsal runbook остаётся draft.
