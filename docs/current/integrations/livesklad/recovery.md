---
doc_schema: 1
doc_type: current
status: current
owner: integrations
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/validated-return-recovery-runbook.md
  - docs/RELEASE_CANDIDATE_2026-08-24.md
implementation_sources:
  - backend/src/main/resources/db/migration/V44__add_validated_return_recovery.sql
  - backend/src/main/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladReturnRecoveryService.java
  - backend/src/main/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladReturnRecoveryController.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladReturnRecoveryServiceTest.java
  - backend/src/test/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladWebhookStoreIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnRecoveryExpectationTest.java
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
замена backfill: worker заново получает документ LiveSklad и пропускает его через обычную
нормализацию.

## Guards

Создание требует:

- opaque `Idempotency-Key`;
- provider external ID установленного формата;
- ожидаемый номер return document;
- положительную сумму ровно до двух decimals;
- ожидаемое количество позиций `1..10000`;
- непустое bounded reason и authenticated ADMIN actor.

Одинаковый actor/key с теми же ожиданиями возвращает существующую recovery. Другой payload под тем
же key или второй recovery того же external document отклоняется conflict. Request и итог имеют
audit trail.

Worker сравнивает fetched kind, number, amount и position count до изменения facts. Несовпадение
завершается `RETURN_RECOVERY_EXPECTATION_MISMATCH`; проверки нельзя обходить. Transient provider/DB
failures повторяются bounded policy, permanent/exhausted остаются terminal.

Orphan return разрешён после V43 и может связаться с исходной продажей позже. Recovery не должна
создавать вымышленную sale или редактировать соседние документы.

## Историческая операция

Подтверждённое восстановление восьми июльских возвратов завершено и сверено с CRM в historical
release evidence от 2026-08-24. Его нельзя ставить в очередь повторно. Этот документ намеренно не
содержит реальные external IDs или готовую пакетную команду.

Новая операция разрешается только для нового exact target по draft-runbook
[`../../../runbooks/livesklad-return-recovery.md`](../../../runbooks/livesklad-return-recovery.md)
после отдельной авторизации и проверки до/после.
