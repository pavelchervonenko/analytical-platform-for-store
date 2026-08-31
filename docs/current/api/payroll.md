---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/payroll-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/salary
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/salary/service/PayrollComputationEngineTest.java
  - backend/src/test/java/com/storeanalytics/salary/service/PayrollCalculationServiceTest.java
  - backend/src/test/java/com/storeanalytics/salary/service/PayrollReadinessServiceTest.java
  - backend/src/test/java/com/storeanalytics/salary/web/PayrollControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - product-formula
review_triggers:
  - payroll-formula-change
  - payroll-state-change
  - payroll-classification-change
supersedes:
  - docs/payroll-api.md
superseded_by: null
---

# Payroll API

## Readiness и расчёт

Store-scoped API предоставляет readiness, preview, calculation, revision list/detail/comparison,
adjustments, approve и paid transitions согласно OpenAPI v10. `canCalculate` означает техническую
возможность построить scenario; более строгий `canApprove` требует закрытых blocking quality gaps.

Calculation создаёт immutable revision lineage. После APPROVED/PAID новая ревизия требует reason.
High-risk mutations используют `Idempotency-Key` и optimistic `runVersion`. Effective changes
increment version; stale command возвращает typed conflict.

## Формула текущей схемы

Три monthly plan statuses — revenue, accessory share и service share — управляют только своими
ставками. Daily fund состоит из процентов оборота accessories/services, процентов gross profit
PlayStation subscription/paid repair и unit rates двух technology tiers. Фонд дня делится поровну
между участниками смены; часы сохраняются для audit, но не являются весом.

Employee month amount — сумма daily shares минус advance и active adjustments. Итог может быть
отрицательным. Return уменьшает фонд месяца/дня возврата; classification берётся по effective
snapshot исходной продажи.

`UNMAPPED`, missing cost для GP-dependent components и день с ненулевым фондом без shift делают
результат неполным и блокируют approval. `freshness` сравнивает fingerprints sales/returns, shifts,
plan, classification и formula; STALE revision нельзя approve/pay до явного recalculation.

## Состояния и ошибки

Основные typed conflicts: `PAYROLL_STATE_CONFLICT`, `PAYROLL_SOURCE_DATA_CHANGED` и
`IDEMPOTENCY_KEY_CONFLICT`. Missing revision reason — `INVALID_ARGUMENT`. Историческая revision не
переписывается после conflict или recalculation.

Admin payroll scheme и product payroll-category assignment endpoints входят в OpenAPI v10, но
изменение formula/category является versioned business change и требует отдельного product review.
