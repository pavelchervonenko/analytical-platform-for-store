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
  - docs/current/product/README.md
implementation_sources:
  - frontend/src
  - frontend/src/main.tsx
  - frontend/src/styles.css
verification_sources:
  - frontend/src/api/consumerContract.test.ts
  - frontend/src/dashboard/OverviewPage.test.tsx
runtime_evidence: []
required_reviewers:
  - frontend
  - product
review_triggers:
  - ui-change
  - api-change
  - metric-change
supersedes: []
superseded_by: null
---

# Frontend-контракты

Здесь описываются endpoint, период, cohort, `null` и пользовательская подпись. Формулы не
дублируются: их источник — [product-раздел](../product/README.md).

- [Период и scope](period-and-scope-contract.md)
- [Главная](overview.md)
- [Структура и attach-map](sales-structure-and-attach-map.md)
- [Сотрудники](employees.md)
- [План и смены](plan-and-shifts.md)
- [Зарплата и отчёты](payroll-and-reports.md)
- [Quality actions](data-quality-actions.md)
- [Transport](transport-contracts.md)

Frontend не подменяет `null` нулём, явно называет узкий cohort, не складывает вложенные группы и не
смешивает периоды в одной карточке. Текущие исключения: Overview period mix
([ADR-0002](../../decisions/ADR-0002-overview-period-scope.md)) и employee return attribution
([ADR-0001](../../decisions/ADR-0001-return-employee-attribution.md)).

## Базовая типографика

Интерфейс использует локально поставляемый `Inter Variable` с кириллицей. Базовая шкала ограничена
размерами 12 px для вторичной информации, 14 px для основного текста, 16 px для заголовков разделов,
24 px для заголовков страниц и 28 px для крупных показателей. Основные веса — 400, 500 и 600;
компоненты не должны подключать отдельные UI-шрифты или вводить локальную шкалу без обоснования.
