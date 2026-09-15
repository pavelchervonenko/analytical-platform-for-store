---
doc_schema: 1
doc_type: current
status: current
owner: integrations
audience:
  - developer
  - operator
last_verified: 2026-09-15
requirement_sources:
  - docs/archive/legacy-contracts/validated-return-recovery-runbook.md
  - docs/history/releases/2026/08/RELEASE_CANDIDATE_2026-08-24.md
implementation_sources:
  - backend/src/main/resources/db/migration/V44__add_validated_return_recovery.sql
  - backend/src/main/resources/db/migration/V49__guard_existing_orphan_return_relink.sql
  - backend/src/main/resources/db/migration/V51__allow_zero_net_return_recovery.sql
  - backend/src/main/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladReturnRecoveryService.java
  - backend/src/main/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladReturnRecoveryController.java
  - backend/src/main/java/com/storeanalytics/sync/service/ReturnSyncPersistence.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladReturnRecoveryServiceTest.java
  - backend/src/test/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladWebhookStoreIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnRecoveryExpectationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnOrphanRelinkExpectationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnSyncIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - integration
  - backend-data
  - operations
review_triggers:
  - recovery-contract-change
  - return-normalization-change
  - recovery-authorization-change
supersedes: []
superseded_by: null
---

# Validated return recovery

## Назначение

ADMIN endpoint `/api/admin/integrations/livesklad/returns/recoveries` создаёт durable manual
recovery только для подтверждённого sale return. Это не ручная вставка финансового факта и не
замена backfill: worker заново получает один документ LiveSklad и пропускает его через обычную
нормализацию. Source-tree поддерживает два режима:

- `MISSING_RETURN` создаёт отсутствующий return fact;
- `EXISTING_ORPHAN_RELINK` повторно нормализует существующий orphan return только для
  материализации доказанных original document/item и employee links.

Этот контракт описывает source-tree, а не факт production rollout. Перед операцией проверяется
актуальный [`../../project-state.md`](../../project-state.md) и наличие режима в deployed OpenAPI.

## Guards

Создание требует:

- opaque `Idempotency-Key`;
- provider external ID установленного формата;
- ожидаемый номер return document;
- неотрицательную сумму ровно до двух decimals; `0.00` разрешено для item-bearing возврата без
  движения оплаты;
- ожидаемое количество позиций `1..10000`;
- непустое bounded reason и authenticated ADMIN actor.

Одинаковый actor/key с теми же ожиданиями возвращает существующую recovery. Другой payload под тем
же key или второй recovery того же external document и режима отклоняется conflict. Один документ
может иметь не более одной recovery каждого режима. Request и итог имеют audit trail.

Worker сравнивает fetched kind, number, amount и position count до изменения facts. Несовпадение
завершается `RETURN_RECOVERY_EXPECTATION_MISMATCH`; проверки нельзя обходить. Transient provider/DB
failures повторяются bounded policy, permanent/exhausted остаются terminal.

Zero-net не означает пустой документ: `expectedPositionCount` остаётся строго положительным, а
fetched позиции, quantity, суммы и cost проходят обычную нормализацию и post-check. Отрицательный
`expectedNetAmount` отклоняется API, service guard, worker expectation и database constraint.

Для `EXISTING_ORPHAN_RELINK` дополнительно обязательны exact external IDs исходной продажи и её
сотрудника, exact текущее состояние employee на возврате и для каждой позиции — return position
ID, original sale position ID, product ID, quantity, net и nullable cost. Nullable
`expectedCurrentEmployeeExternalId` имеет строгую семантику: `null` требует отсутствующего employee,
а external ID требует точного совпадения с уже сохранённым processing/original employee. До записи
проверяются fetched LiveSklad detail и текущее состояние БД: return активен, относится к тому же
магазину, ещё не связан, его employee совпадает с ожиданием, а исходная продажа/позиции активны и
полностью совпадают с ожиданиями. После нормализации повторно проверяются document/item/employee
links и неизменность guarded amounts. Любой mismatch откатывает всю транзакцию; соседние документы
не обрабатываются.

Связывание может привести аналитический snapshot возврата к snapshot исходной позиции согласно
обычной return-нормализации. Поэтому после операции обязательна повторная сверка STORE, SELLERS,
employee attribution и категорий за затронутый период.

## Историческая операция

Подтверждённое восстановление восьми июльских возвратов завершено и сверено с CRM в historical
release evidence от 2026-08-24. Его нельзя ставить в очередь повторно. Этот документ намеренно не
содержит реальные external IDs или готовую пакетную команду.

Новая операция разрешается только для нового exact target по draft-runbook
[`../../../runbooks/livesklad-return-recovery.md`](../../../runbooks/livesklad-return-recovery.md)
после отдельной авторизации и проверки до/после.
