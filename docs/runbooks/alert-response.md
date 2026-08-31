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
risk_level: medium
source_of_truth:
  - backend/src/main/java/com/storeanalytics/common/observability
  - deploy/bin/monitor-health.sh
  - monitoring/prometheus
verification_evidence:
  - level: static
    scope: health, metrics authorization and repository alert artifacts
    verified_at: 2026-08-31
    evidence: backend/src/test/java/com/storeanalytics/common/observability
required_reviewers:
  - operations
  - backend
review_triggers:
  - alert-change
  - health-contract-change
  - incident
supersedes: []
superseded_by: null
---

# Реакция на alert

## Цель и область

Подтвердить сигнал, ограничить влияние и передать recovery соответствующему runbook. Документ не
доказывает, что repository rules загружены в production monitoring.

## Влияние и требуемая авторизация

Read-only triage выполняет on-call. Restart, pause worker/schedule, silence или retry являются
reversible writes и требуют operations owner; data repair/migration переходят в incident.

## Предусловия и точный target

Зафиксировать alert name/source, firing time, environment, store/service/job scope, release/schema,
correlation ID и responder. Не копировать payload, credentials или personal identifiers.

## Критерии остановки

- Alert source/rule не удаётся подтвердить либо target неоднозначен.
- Требуется DB write, migration, credential rotation или data deletion.
- Readiness/schema mismatch, backup stale/failure или повторяющийся poison job повышает severity.

## Preflight

Проверить public `/livez` и `/readyz`, actual container health/digests, live Flyway version,
соответствующие bounded metrics/queue counts и recent sanitized logs. Для backup alert дополнительно
проверить systemd result и object age без публикации bucket credentials/path.

## Процедура

1. Acknowledge и проверить, что сигнал не stale/duplicate.
2. Сопоставить с deploy/flag/backfill/provider change.
3. Ограничить blast radius минимальным обратимым действием: остановить конкретный planner/worker,
   но не очищать queue и не менять данные.
4. Проверить recovery signal и пользовательское влияние.
5. При нарушении данных/schema/security открыть incident и сохранить evidence.

## Проверка результата

Alert перешёл в recovery по исходному signal, health/queue trend стабилен, новые errors не растут.
Silence не считается recovery и должен иметь owner/expiry.

## Повторный запуск и rollback

Restart допускается один раз после установления причины. Если симптом возвращается, бесконечный
restart запрещён. Rollback — отменить минимальное действие/flag; schema/data recovery выполняется
отдельно.

## Evidence и известные пробелы

Сохранить alert fingerprint, bounded metrics/log refs, действие и recovery time. Нет runtime
evidence загрузки rules, конечной доставки и stale-backup alert; до fire/recovery rehearsal runbook
остаётся draft.
