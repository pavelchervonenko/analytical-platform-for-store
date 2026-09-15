---
doc_schema: 1
doc_type: runbook
status: draft
owner: integrations
audience:
  - operator
last_verified: 2026-09-15
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
  - backend/src/main/resources/db/migration/V49__guard_existing_orphan_return_relink.sql
  - backend/src/main/resources/db/migration/V51__allow_zero_net_return_recovery.sql
  - backend/src/main/java/com/storeanalytics/sync/service/ReturnSyncPersistence.java
verification_evidence:
  - level: static
    scope: both recovery modes, zero-net guard, persisted exact expectations, atomic relink and rollback
    verified_at: 2026-09-15
    evidence: backend/src/test/java/com/storeanalytics/sync/service/ReturnSyncIntegrationTest.java
required_reviewers:
  - integration
  - backend-data
  - operations
review_triggers:
  - recovery-contract-change
  - return-normalization-change
  - production-recovery
supersedes:
  - docs/archive/legacy-contracts/validated-return-recovery-runbook.md
superseded_by: null
---

# Восстановление подтверждённого sale return

## Статус и граница применения

Runbook `draft`: production recovery high-risk и требует staging + fresh production-read-only
evidence для нового exact target. Наличие V49 в source-tree не означает, что режим уже доступен в
production: deployed schema/API подтверждаются отдельно. Ранее завершённый июльский пакет
missing-return восстановлен и сверён; его повтор запрещён. Здесь нет реальных external IDs,
credential или готовой batch-команды.

## Авторизация и scope

Операцию одобряет production owner после независимого operations/integration review. Один request —
один подтверждённый return document. Mass fire-and-forget запрещён. Несколько исправлений кода и
миграций можно подготовить и выпустить одним deployment, но data-recovery выполняются после него
последовательно с отдельным preflight и post-check каждого документа.

Нужны: магазин, включительный период, номер, exact provider external ID, expected non-negative net
amount, expected positive position count, reconciliation reference и уникальный idempotency key.
`0.00` допустимо только для доказанного item-bearing возврата без движения оплаты; оно не отменяет
проверку позиций, quantity и cost. Customer PII в request/reason не включается.

Режим выбирается явно:

- `MISSING_RETURN`: return fact должен отсутствовать;
- `EXISTING_ORPHAN_RELINK`: return fact должен существовать, быть активным orphan без original
  links; ожидания включают nullable `expectedCurrentEmployeeExternalId`, original sale/employee
  external IDs и для каждой позиции exact return/original position IDs, product ID, quantity, net
  и nullable cost. `null` для current employee означает строгую проверку отсутствия employee;
  непустое значение должно точно совпасть с уже сохранённым employee.

## Предусловия

- CRM/source evidence подтверждает тип дефекта и все контрольные значения.
- External ID получен из LiveSklad, а не выведен из номера.
- Backup/health/queues подтверждены fresh read-only evidence.
- Нет активной sync/recovery, меняющей тот же документ/период.
- ADMIN session и CSRF используются штатным API; cookies/tokens не попадают в history/log.
- Deployed schema/API действительно поддерживают выбранный mode; source-tree или старый документ
  не считаются runtime evidence.
- Для zero-net target deployed OpenAPI, service guard и constraint должны принимать `0.00`;
  локально подготовленная V51 до deployment этого не доказывает.

## Критерии остановки

- Для `MISSING_RETURN` target уже существует; для `EXISTING_ORPHAN_RELINK` target отсутствует,
  удалён, уже связан или его current employee не совпадает с exact ожиданием.
- Recovery того же external ID и mode уже существует.
- Любое ожидание не совпадает с fetched document.
- Неожиданный store, employee, original sale/item, product, quantity, amount, cost, item composition
  или document kind.
- Health ухудшился, queue/5xx растёт или reconciliation delta увеличилась.

## Preflight

1. Read-only подтвердить mode-specific DB precondition и существование evidence проблемы.
2. Проверить отсутствие recovery того же external ID и active conflicting job.
3. Для orphan-relink сопоставить source и DB до позиции: current return employee, original
   sale/employee, return/original position IDs, product, quantity, net и cost; убедиться, что
   исходные sale/items активны и находятся в том же магазине.
4. Зафиксировать дооперационные STORE, SELLERS, employee и category totals за exact period.
5. Выполнить dry review request без отправки и независимый sign-off exact target.

## Процедура

1. Отправить один `POST /api/admin/integrations/livesklad/returns/recoveries` с выбранным `mode`,
   exact expectations, reason/reference и новым opaque `Idempotency-Key` через штатный
   authenticated client. Для orphan-relink передать exact nullable
   `expectedCurrentEmployeeExternalId` и полный `expectedOriginalLinks`.
2. Сохранить recovery ID и correlation ID без payload/cookie.
3. Читать `GET .../recoveries/{recoveryId}` до terminal state; не создавать второй key во время
   ожидания.
4. При `PROCESSED` read-only проверить document kind/number/amount/cost/items, original
   document/item links, employee attribution и before/after aggregates.
5. Выполнить CRM ↔ application reconciliation того же exact включительного периода.
6. Только после PASS переходить к следующему независимо одобренному документу.

## Retry и forward-fix

Повтор идентичного request с тем же key безопасно возвращает существующую recovery. Новый key —
новое production mutation и требует новой авторизации. Один external document допускает не более
одной recovery каждого mode. `RETURN_RECOVERY_EXPECTATION_MISMATCH` не обходится; facts при mismatch
не должны измениться.

Нет SQL rollback, который безопасно отменяет recovery. При дефекте остановить пакет, сохранить
evidence, отключить worker при необходимости и подготовить reviewed forward-fix. Запрещено
вставлять financial rows, менять terminal state или expectation columns вручную.

## Evidence и rehearsal

Historical evidence содержит authorization, exact non-secret target reference, before/after
invariants, terminal state, correlation ID, reconciliation delta и reviewer verdict. Подготовленная
очередь документов не заменяет fresh preflight: между сверкой и общим deployment source или DB могли
измениться. Для статуса `current` требуется staging rehearsal обоих modes и свежая
production-read-only проверка exact target; существующее историческое восстановление не переносит
разрешение на новые документы.
