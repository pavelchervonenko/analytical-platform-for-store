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
  - docs/archive/legacy-contracts/employee-category-kpi-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/service/EmployeeCategoryKpiService.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/EmployeeCategoryKpiRepository.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics/repository/EmployeeCategoryKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/EmployeeCategoryKpiServiceTest.java
  - backend/src/test/java/com/storeanalytics/metrics/web/EmployeeCategoryKpiControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - product-formula
review_triggers:
  - employee-category-kpi-change
  - return-employee-attribution-change
supersedes:
  - docs/archive/legacy-contracts/employee-category-kpi-api.md
superseded_by: null
---

# Employee category KPI API

`GET /api/stores/{storeId}/kpi/employees/categories?periodStart=...&periodEnd=...` группирует
category signed facts по полному employee scope. Category/group membership и missing-cost rules
совпадают с [`category-kpi.md`](category-kpi.md); employee scope — с
[`employee-kpi.md`](employee-kpi.md).

Доли сотрудника используют его полную signed net revenue как знаменатель:

```text
categoryShare = employeeCategoryRevenue / employeeNetRevenue × 100%
```

Нулевой знаменатель даёт недоступную долю, а не ноль. Для отрицательного employee revenue текущее
поведение этого endpoint и rating неодинаково; frontend не должен нормализовать различие сам.

Employee attribution возврата наследует действующее правило исходного продавца. Открытое
расхождение с новым требованием заказчика описано в [`employee-kpi.md`](employee-kpi.md).
