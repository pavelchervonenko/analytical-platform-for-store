---
doc_schema: 1
doc_type: runbook
status: draft
owner: security
audience:
  - operator
last_verified: 2026-08-31
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - staging
  - production-read-only
operation_type: destructive
environments:
  - staging
  - production
risk_level: critical
source_of_truth:
  - backend/src/main/java/com/storeanalytics/maintenance/DataRetentionService.java
  - backend/src/main/java/com/storeanalytics/common/config/DataRetentionProperties.java
  - backend/src/main/resources/application.yml
verification_evidence:
  - level: static
    scope: fail-closed authorization, dry-run, cutoffs and batch deletion behavior
    verified_at: 2026-08-31
    evidence: backend/src/test/java/com/storeanalytics/maintenance
required_reviewers:
  - security-privacy
  - operations
  - backend
review_triggers:
  - retention-change
  - deletion-target-change
  - restore-drill
  - legal-requirement-change
supersedes: []
superseded_by: null
---

# Включение удаления по retention

## Цель и область

Перевести technical retention из dry-run в bounded deletion для заранее утверждённых таблиц и
cutoffs. Процедура не распространяется на financial facts, finalized reports или новые payload
таблицы без отдельного contract change.

## Влияние и требуемая авторизация

Удаление необратимо в рабочей БД. Требуются customer data owner, security/privacy, operations,
backend reviewer и разовая авторизация exact targets/cutoffs/change window.

## Предусловия

- Утверждённая policy с юридическим основанием и owner.
- Fresh encrypted backup и успешный isolated restore drill с измеренным RPO/RTO.
- Несколько production dry-run результатов стабильны и reviewed.
- Нет retention holds, active backfill/recovery или расследования, затрагивающего candidates.

## Критерии остановки

- Approval/backup references только формально непустые, но не разрешаются в immutable evidence.
- Restore evidence stale, future-dated или не относится к exact database.
- Candidate counts/cutoffs неожиданны, включают protected data или нарушают backfill horizon.
- Runtime flags/worker/release/schema отличаются от change record.

## Preflight и точный target

Зафиксировать DB/store scope, release/schema, zone, каждую target table, cutoff, candidate count,
batch sizes, approval/backup/restore evidence IDs и maximum restore age. Запустить dry-run без
изменения flags и сравнить с предыдущими результатами.

## Процедура

1. Снять и проверить fresh backup checkpoint; не использовать только provider status.
2. Повторить isolated restore/business checks.
3. В staging включить deletion с теми же cutoffs/batches и проверить before/after invariants.
4. Подготовить exact production config change; независимо проверить ссылки и counts.
5. В approved window включить deletion и разрешить один scheduler run.
6. Немедленно вернуть deletion в disabled при ошибке/неожиданных counts; сохранить audit.
7. Проверить remaining candidates, protected facts/reports, sync/backfill и audit holds.

## Повторный запуск и конкурентность

Advisory lock исключает параллельный retention run; batches позволяют продолжение. Повтор допустим
только после review предыдущего affected/remaining result.

## Rollback или forward-fix

Удалённые строки нельзя вернуть application rollback. Восстановление — только isolated restore и
controlled data recovery/DB switch; поэтому stop criteria важнее retry.

## Evidence и известные пробелы

Сохранить exact config hash, approvals, backup/restore IDs, before/after counts, audit run ID и
business invariants. Code проверяет строки references, но не их существование; production restore
и deletion rehearsals не доказаны. Runbook остаётся draft.
