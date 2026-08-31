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
  - docs/employee-kpi-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/service/EmployeeKpiService.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/EmployeeKpiRepository.java
  - backend/src/main/java/com/storeanalytics/sync/service/ReturnSyncPersistence.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics/repository/EmployeeKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/EmployeeKpiServiceTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnSyncIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - product-formula
review_triggers:
  - employee-kpi-change
  - return-employee-attribution-change
  - employee-scope-change
supersedes:
  - docs/employee-kpi-api.md
superseded_by: null
---

# Employee KPI API

`GET /api/stores/{storeId}/kpi/employees?periodStart=...&periodEnd=...` применяет те же signed
facts, cost/null и period rules, что [`store-kpi.md`](store-kpi.md), но группирует их по сотруднику.

Полный result включает active assignments, historical employees с фактами, сотрудников вне
rating roster и строку «не назначен». При одинаковом наборе фактов проверяемый reconciliation:

```text
sum(all employee rows, включая «не назначен») = store KPI
```

Этот endpoint не равен filtered roster на главной странице.

## Открытое расхождение атрибуции

Текущая нормализация относит возврат сотруднику исходной продажи. Код и integration test закрепляют
именно это поведение. Более позднее требование заказчика об attribution по сотруднику строки
возврата ещё не оформлено принятым ADR и не реализовано. До решения employee-level revenue,
categories, rating и attach-rate нельзя объявлять соответствующими новому правилу; store total от
этого распределения не меняется.
