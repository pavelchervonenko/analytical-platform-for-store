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
  - docs/current/product/plans-and-shifts.md
implementation_sources:
  - frontend/src/plan-schedule/PlanSchedulePage.tsx
  - frontend/src/plan-schedule/PlanPanel.tsx
  - frontend/src/plan-schedule/DailyPlanTable.tsx
  - frontend/src/plan-schedule/SchedulePanel.tsx
verification_sources:
  - frontend/src/plan-schedule/PlanPanel.test.tsx
  - frontend/src/plan-schedule/DailyPlanTable.test.tsx
runtime_evidence: []
required_reviewers:
  - frontend
  - product
review_triggers:
  - plan-ui-change
  - shift-ui-change
  - plan-formula-change
supersedes: []
superseded_by: null
---

# «План и смены»

План относится к selected month: progress — `month-01..asOf`, target — monthly. Schedule относится
к календарным дням этого месяца.

| Блок | Scope | Null/empty | Подпись |
|---|---|---|---|
| Направления | Month..asOf | Plan absent → setup | Факт/критерий/месяц |
| Прошлый день | День + cumulative gap | Share nullable без revenue | «Факт за день» |
| Будущий день | Month forecast + remaining | Нет future days | «Цель на день» |
| Смены | Даты месяца | День без смен явный | Дата, сотрудник, часы |

Формулы находятся в [product contract](../product/plans-and-shifts.md). Frontend не пересчитывает
achievement по округлённой строке. «Нужно в день» должно называться остатком месячной цели, а
future target — отличаться от факта. При incomplete classification число показывается с quality.
