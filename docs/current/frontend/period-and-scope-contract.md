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
| Store/category KPI | `/kpi`, `/kpi/categories` | selected start..end | Store | GP/margin nullable | Выбранный диапазон |
| Attach | `/kpi/attach-rates` | selected | Store | Rate nullable без базы | Выбранный диапазон |
| Employee KPI/rating | `/kpi/employees`, `/employee-ratings` | selected | Full / roster | Rank nullable | Диапазон + cohort |
| Plan | `/performance-plans/{month}/progress?asOf=` | month-01..asOf | Store | 404 = plan absent | «План месяца» |
| Quality | `/period-quality/{month}?asOf=` | month-01..asOf | Store | Issues | Месяц + asOf |
| Payroll | `/payroll/{month}/*` | Full month | Payroll cohort | calculate/approve gates | Месяц |
| Reports | `/reports/*` | Snapshot month/year | Snapshot cohort | Historical null | Период snapshot |

## Реализованный gap

В week/custom amounts коммерческих карточек selected-period, но `actualSharePercent`, gap и target
месячные. До [ADR-0002](../../decisions/ADR-0002-overview-period-scope.md) amount и percent могут
иметь разные знаменатели. Month mode не доказывает корректность week/custom.

Frontend использует store timezone, а часть normalization backend — fixed Kaliningrad. Другой
timezone требует отдельного end-to-end test.
