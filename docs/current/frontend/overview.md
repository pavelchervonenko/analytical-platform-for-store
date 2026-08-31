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
  - docs/current/product/business-metrics.md
  - docs/current/product/plans-and-shifts.md
implementation_sources:
  - frontend/src/dashboard/OverviewPage.tsx
  - frontend/src/dashboard/OverviewManagementSections.tsx
  - frontend/src/api/queries.ts
verification_sources:
  - frontend/src/dashboard/OverviewPage.test.tsx
runtime_evidence: []
required_reviewers:
  - frontend
  - product
review_triggers:
  - dashboard-ui-change
  - dashboard-query-change
  - metric-change
supersedes: []
superseded_by: null
---

# Главная страница

| Блок | Источник | Период | Cohort | Null/partial | Label |
|---|---|---|---|---|---|
| Freshness | `/data-status` | Latest coverage | Store | Date nullable | «Данные по…»/причина |
| Revenue/GP/margin | `/kpi` | Selected | Store | GP/margin nullable | Selected period |
| Accessories/services/additional | `/kpi/categories` | Selected | Store | Group can be absent | Selected period |
| Plan | `/performance-plans/{month}/progress` | Month..asOf | Store | No plan state | «План месяца» |
| Team | `/kpi/employees` + `/employee-ratings` | Selected | Overview roster | Score/rank nullable | «Основные продавцы» |
| Attach map | `/kpi/attach-rates` + rating | Selected | Store + roster | Rate/base nullable | Empty-state reason |
| Quality | `/period-quality/{month}` | Month..asOf | Store | Warning/error | Готовность данных |

«Чистая выручка», «Допы», «Аксессуары», «Услуги» — store totals, включая вне рейтинга и без
сотрудника. Team — subtotal roster и не равен магазину. `null` GP/margin не показывается как zero.

`ManagementSummary` сейчас предпочитает month plan share при selected-period amount; week/custom
карточка внутренне несогласована. Целевой UX — ADR-0002. UI показывает проценты с одним знаком, но
не должен пересчитывать achievement по округлённой строке.
