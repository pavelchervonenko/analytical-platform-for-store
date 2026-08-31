---
doc_schema: 1
doc_type: runbook
status: draft
owner: integrations
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
risk_level: high
source_of_truth:
  - backend/src/main/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladReturnRecoveryService.java
  - backend/src/main/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladReturnRecoveryController.java
  - backend/src/main/resources/db/migration/V44__add_validated_return_recovery.sql
verification_evidence:
  - level: static
    scope: validation, idempotency, expectation matching and audited durable processing
    verified_at: 2026-08-31
    evidence: backend/src/test/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladReturnRecoveryServiceTest.java
required_reviewers:
  - integration
  - backend-data
  - operations
review_triggers:
  - recovery-contract-change
  - return-normalization-change
  - production-recovery
supersedes:
  - docs/validated-return-recovery-runbook.md
superseded_by: null
---

# Восстановление подтверждённого sale return

## Статус и запрет повтора

Runbook `draft`: production recovery high-risk и требует staging + fresh production-read-only
evidence для нового exact target. Ранее завершённый июльский пакет восстановлен и сверён; его
повтор запрещён. Здесь нет его external IDs или готовой batch-команды.

## Авторизация и scope

Операцию одобряет production owner после независимого operations/integration review. Один запуск —
один подтверждённый return document. Mass fire-and-forget запрещён.

Нужны: магазин, включительный период, номер, exact provider external ID, expected positive net
amount, expected position count, reconciliation reference и уникальный idempotency key. Customer
PII в request/reason не включается.

## Предусловия

- CRM/source evidence подтверждает missing return и контрольные значения.
- External ID получен из LiveSklad, а не выведен из номера.
- Backup/health/queues подтверждены fresh read-only evidence.
- Нет активной sync/recovery, меняющей тот же документ/период.
- ADMIN session и CSRF используются штатным API; cookies/tokens не попадают в history/log.

## Критерии остановки

- Target уже существует или ранее recovery завершена.
- Любое ожидание не совпадает с fetched document.
- Неожиданный store, employee, amount, item composition или document kind.
- Health ухудшился, queue/5xx растёт или reconciliation delta увеличилась.

## Preflight

1. Read-only подтвердить отсутствие return fact и существование evidence проблемы.
2. Проверить отсутствие recovery того же external ID и active conflicting job.
3. Зафиксировать дооперационные store/employee/category totals за exact period.
4. Выполнить dry review request без отправки и независимый sign-off exact target.

## Процедура

1. Отправить один `POST /api/admin/integrations/livesklad/returns/recoveries` с exact expectations,
   reason/reference и новым opaque `Idempotency-Key` через штатный authenticated client.
2. Сохранить recovery ID и correlation ID без payload/cookie.
3. Читать `GET .../recoveries/{recoveryId}` до terminal state; не создавать второй key во время
   ожидания.
4. При `PROCESSED` read-only проверить document kind/number/amount/items, исходную связь при её
   наличии и before/after aggregates.
5. Выполнить CRM ↔ application reconciliation того же exact включительного периода.
6. Только после PASS переходить к следующему независимо одобренному документу.

## Retry и forward-fix

Повтор идентичного request с тем же key безопасно возвращает существующую recovery. Новый key —
новое production mutation и требует новой авторизации. `RETURN_RECOVERY_EXPECTATION_MISMATCH` не
обходится; facts при mismatch не должны измениться.

Нет SQL rollback, который безопасно отменяет recovery. При дефекте остановить пакет, сохранить
evidence, отключить worker при необходимости и подготовить reviewed forward-fix. Запрещено
вставлять financial rows, менять terminal state или expectation columns вручную.

## Evidence и rehearsal

Historical evidence содержит authorization, exact non-secret target reference, before/after
invariants, terminal state, correlation ID, reconciliation delta и reviewer verdict. Для статуса
`current` требуется staging rehearsal текущего API и свежая production-read-only проверка exact
target; существующее историческое восстановление не переносит разрешение на новые документы.
