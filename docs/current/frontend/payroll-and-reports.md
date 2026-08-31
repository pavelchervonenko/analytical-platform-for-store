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
  - docs/current/product/payroll.md
  - docs/current/product/reports.md
implementation_sources:
  - frontend/src/payroll/PayrollPage.tsx
  - frontend/src/reports/ReportsPage.tsx
  - frontend/src/api/queries.ts
verification_sources:
  - frontend/src/payroll/payroll-ui.test.ts
  - backend/src/test/java/com/storeanalytics/salary/web/PayrollControllerTest.java
  - backend/src/test/java/com/storeanalytics/report
runtime_evidence: []
required_reviewers:
  - frontend
  - product
review_triggers:
  - payroll-ui-change
  - report-ui-change
  - report-schema-change
supersedes: []
superseded_by: null
---

# Зарплата и отчёты в интерфейсе

| View | Endpoint | Период | Cohort | Null/partial | Label |
|---|---|---|---|---|---|
| Payroll readiness | `/payroll/{month}/readiness` | Full month | Store | Blocking counts | Готовность |
| Payroll preview | `/payroll/{month}/preview` | Full month | Payroll cohort | Fund/day nullable | Предварительный |
| Run | `/payroll/{month}` или `/payroll-runs/{id}` | Snapshot month | Statements | Stale/revision | Version/status |
| Adjustments | `/payroll-runs/{id}/adjustments` | Run version | Statement | Conflict/version | Type/sum/reason |
| Report list | `/reports?year&type&page&size` | Filters | Snapshots | Empty list | Year/type |
| Report detail | `/reports/{id}` | Snapshot month/year | Snapshot cohort | GP/margin nullable | Period/revision |

Calculate/approve/paid следуют backend lifecycle, а не просто наличию цифр. Negative payable
показывается со знаком, nullable fund блокирует действие.

Monthly employee rows основаны на payroll statements, не full employee KPI. Annual employee payload
также ограничен; UI не называет эти таблицы «все сотрудники» и не использует их как store
reconciliation. Для `ReportsPage` отдельного frontend test suite пока нет.
