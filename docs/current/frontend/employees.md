---
doc_schema: 1
doc_type: current
status: current
owner: frontend
audience:
  - developer
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/current/product/employees-and-rating.md
  - docs/current/product/sales-and-returns.md
implementation_sources:
  - frontend/src/employees
  - frontend/src/api/queries.ts
verification_sources:
  - frontend/src/employees/EmployeeCardPage.test.tsx
  - backend/src/test/java/com/storeanalytics/performance/service/EmployeeRatingServiceTest.java
runtime_evidence: []
required_reviewers:
  - frontend
  - product
review_triggers:
  - employee-page-change
  - employee-scope-change
  - rating-change
supersedes: []
superseded_by: null
---

# Экран сотрудников

| View | Endpoint | Период | Cohort | Null/partial | Label |
|---|---|---|---|---|---|
| Directory | `/employees` | Selected | Accessible employees | May have no facts | «Сотрудники» |
| Card | `/employees/{id}` | Selected + comparison | One employee | Metrics nullable | Имя + обе даты |
| Full KPI | `/kpi/employees` | Selected | Full financial cohort | GP nullable | «Все факты» |
| Rating | `/employee-ratings` | Selected | Eligible/candidate | Score/rank nullable | Причина без места |

Командный блок показывает distribution roster, карточка — конкретного сотрудника; store-level
вывод не дублируется как персональный. Full financial cohort шире roster, поэтому узкий список
обязан иметь соответствующий label.

Comparison modes `PREVIOUS_PERIOD`/`PREVIOUS_WEEK` показывают обе пары дат. Нет предыдущей базы —
«нет данных», не нулевой рост. Employee values с возвратами требуют ADR-0001 warning.
