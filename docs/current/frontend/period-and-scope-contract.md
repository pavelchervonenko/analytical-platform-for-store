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
  - docs/current/product/periods.md
implementation_sources:
  - frontend/src/stores/WorkspaceProvider.tsx
  - frontend/src/stores/RangePeriodSelector.tsx
  - frontend/src/dashboard/OverviewPage.tsx
verification_sources:
  - frontend/src/stores/RangePeriodSelector.test.tsx
  - frontend/src/dashboard/OverviewPage.test.tsx
runtime_evidence: []
required_reviewers:
  - frontend
  - product
review_triggers:
  - period-selector-change
  - dashboard-query-change
  - timezone-change
supersedes: []
superseded_by: null
---

# Период и scope интерфейса

`WorkspaceProvider` хранит store, mode, `periodStart`, `periodEnd`, `month`, `asOfDate`.

| Потребитель | Endpoint | Период | Cohort | Null/partial | Подпись |
|---|---|---|---|---|---|
| Overview facts | `/overview-metrics?scope=` | selected start..end | SELLERS default / STORE | GP/margin/share nullable | Выбранный диапазон |
| Sales structure | `/overview-metrics?scope=` | selected start..end | Selected overview scope | GP/margin nullable | Выбранный диапазон + scope |
| Store/category details | `/kpi/categories` | selected start..end | Store | GP/margin nullable | «Весь магазин» |
| Attach | `/kpi/attach-rates` | selected | Store | Rate nullable без базы | Выбранный диапазон |
| Employee KPI/rating | `/kpi/employees`, `/employee-ratings` | selected | Full / roster | Rank nullable | Диапазон + cohort |
| Plan | `/performance-plans/{month}/progress?asOf=&scope=` | month-01..asOf | Selected overview scope | 404 = plan absent | «План месяца» + scope |
| Quality | `/period-quality/{month}?asOf=` | month-01..asOf | Store | Issues | Месяц + asOf |
| Payroll | `/payroll/{month}/*` | Full month | Payroll cohort | calculate/approve gates | Месяц |
| Reports | `/reports/*` | Snapshot month/year | Snapshot cohort | Historical null | Период snapshot |

## Граница selected period и month plan

В тёмном блоке amount, quantity и share всегда используют одинаковые `periodStart..periodEnd` и
scope. Month target/gap показывается там только в month mode. В week/custom месячный plan остаётся
отдельным блоком month-01..`asOf`; недельный или пропорциональный план не изобретается.

Параметр `overviewScope` не меняет выбранные даты. Отсутствующее/неизвестное значение означает
`SELLERS`; переход на `STORE` сохраняется в URL. Backend plan API сохраняет `STORE` как transport
default для прежних потребителей, поэтому Overview всегда передаёт scope явно.

Frontend использует store timezone, а часть normalization backend — fixed Kaliningrad. Другой
timezone требует отдельного end-to-end test.
