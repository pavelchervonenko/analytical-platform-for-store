---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/archive/legacy-contracts/employee-rating-api.md
  - docs/archive/discoveries/analytics-business-rules-draft.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/repository/EmployeeKpiRepository.java
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeRatingService.java
  - backend/src/main/resources/db/migration/V4__add_employee_performance_rating.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics/repository/EmployeeKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/performance/repository/EmployeeRatingIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/performance/service/EmployeeRatingServiceTest.java
runtime_evidence: []
required_reviewers:
  - product
  - backend
review_triggers:
  - employee-scope-change
  - rating-formula-change
  - return-attribution-change
supersedes: []
superseded_by: null
---

# Сотрудники и рейтинг

| Состав | Кто входит | Назначение |
|---|---|---|
| Full employee KPI | Назначенные/исторические с фактами, вне рейтинга, «Не назначен» | Store reconciliation |
| Rating eligible | Active employee + active assignment + participates | Возможность участия |
| Rating candidate | Eligible + минимум одна смена | Benchmark и место |
| Overview roster | Активные назначенные участники; смена не обязательна | Управленческий subtotal |

Overview roster не обязан сходиться со store total.

## Rating v1

Четыре направления имеют вес `25%`, cap `150`:

```text
Contribution = clamp(employee revenue / mean(candidate revenue) * 100, 0, 150)
Efficiency = clamp((employee revenue / hours) /
  (sum(candidate revenue) / sum(candidate hours)) * 100, 0, 150)
Structure = 50% * clamp(accessory share / target * 100, 0, 150)
  + 50% * clamp(service share / target * 100, 0, 150)
Attach = average(clamp(employee rate / store rate * 100, 0, 150))
Overall = sum(score * weight / 100) * 100 / available coverage
```

Attach участвует при employee denominator `>=3` и положительном store benchmark. Место присваивается
при coverage `>=75%`, ранжирование dense. Нет смены — не candidate; нулевые/отрицательные часы —
efficiency `null`; малая attach-база — score `null`.

Простые UI-номинации «лидер» пока не требуют минимального оборота, поэтому малая база способна дать
неустойчивый вывод.

Текущий сотрудник возврата расходится с методикой заказчика; employee KPI/rating периода с
возвратами не полностью авторитетны до [ADR-0001](../../decisions/ADR-0001-return-employee-attribution.md).
