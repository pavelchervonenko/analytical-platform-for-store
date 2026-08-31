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
  - production-drill
operation_type: recovery
environments:
  - staging
  - production
risk_level: critical
source_of_truth:
  - deploy/bin/backup-postgres.sh
  - deploy/backup.env.example
  - deploy/systemd/store-analytics-backup.service
  - deploy/systemd/store-analytics-backup.timer
verification_evidence:
  - level: static
    scope: backup creation, encryption, manifest and upload-size verification
    verified_at: 2026-08-31
    evidence: deploy/bin/backup-postgres.sh
required_reviewers:
  - operations
  - security-privacy
  - backend
review_triggers:
  - backup-change
  - restore-drill
  - database-change
  - storage-provider-change
supersedes: []
superseded_by: null
---

# Backup restore и disaster recovery

## Цель и область

Проверить backup через isolated restore и восстановить сервис после потери/повреждения БД. Этот
draft не разрешает in-place restore или production cutover без отдельной авторизации exact target.

## Влияние и требуемая авторизация

Restore создаёт новую БД/кластер; production switch меняет источник данных и останавливает writers.
Требуются incident commander, database/backup owner, security custodian ключа и customer owner.

## Предусловия

- Exact encrypted object и manifest выбраны по incident recovery point.
- Decryption key получен из независимого approved custody, не записан в shell history.
- Есть изолированный PostgreSQL совместимой major version, отдельные credentials/network и место.
- Определены business reconciliation queries и допустимая потеря данных.

## Критерии остановки

- Object/manifest/checksum не совпали или key custody не подтверждена.
- Target указывает на production database либо допускает overwrite.
- `pg_restore --list`, restore, Flyway history или integrity checks дают ошибку.
- RPO выходит за customer-authorized предел или нет плана reconcile post-backup writes.

## Preflight и точный target

Record: incident/drill ID, source object key/hash/time, isolated target host/database, PostgreSQL
version, expected schema/release, operator/reviewer и network boundary. Проверить target пуст и не
используется приложением.

## Процедура

1. Скачать encrypted object и manifest в защищённый temporary workspace.
2. Сравнить size и SHA-256 с manifest; только затем расшифровать.
3. Выполнить `pg_restore --list`; восстановить в новую изолированную БД без owner/ACL reuse.
4. Проверить Flyway history, constraints, row counts, audit immutability и согласованные бизнес-
   агрегаты; измерить download/decrypt/restore/verification duration.
5. Для drill удалить isolated target по отдельному approved cleanup.
6. Для incident подготовить runtime roles/ACL, exact release и controlled endpoint switch; сначала
   API, затем worker, затем web/smoke/reconciliation.

## Повторный запуск и конкурентность

Restore всегда получает новый target name. Повтор не перезаписывает предыдущий результат. Writers
не переключаются, пока target не прошёл проверку.

## Rollback или forward-fix

До cutover rollback — оставить текущий endpoint. После cutover возврат возможен только если старый
DB сохранён read-only и divergence оценён; автоматический dual-write merge отсутствует.

## Evidence и известные пробелы

Сохранить object hash/time, target identity, versions, durations, integrity/reconciliation delta и
RPO/RTO. В репозитории нет restore script, скачивания/checksum remote object, measured drill,
stale-backup alert, Object Lock или off-provider evidence. Runbook остаётся draft.
