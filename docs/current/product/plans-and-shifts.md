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
  - docs/store-plan-progress-api.md
  - docs/analytics-business-rules-draft.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/performance/service/StorePlanProgressService.java
  - backend/src/main/java/com/storeanalytics/performance/model/StorePlanTargets.java
  - frontend/src/plan-schedule/PlanSchedulePage.tsx
verification_sources:
  - backend/src/test/java/com/storeanalytics/performance/service/StorePlanProgressServiceTest.java
  - backend/src/test/java/com/storeanalytics/performance/web/StorePlanProgressControllerTest.java
runtime_evidence: []
required_reviewers:
  - product
  - backend
  - frontend
review_triggers:
  - plan-formula-change
  - shift-management-change
  - period-semantics-change
supersedes: []
superseded_by: null
---

# Планы и смены

План — месячный контракт магазина. Факт считается от первого числа до включительной `asOf`.

## Выручка

```text
Completion = actual revenue / monthly revenue target * 100%
CurrentDailyPace = actual revenue / elapsed days
ProjectedAmount = actual revenue + CurrentDailyPace * remaining days
Remaining = max(monthly target - actual revenue, 0)
RequiredPerRemainingDay = Remaining / remaining days
```

## Направления доли

Для `ACCESSORY`, `SERVICE`, `ADDITIONAL`:

```text
ActualShare = direction amount / actual net revenue * 100%
TargetAmountToDate = actual net revenue * target share / 100%
Achieved = direction amount * 100 >= target share * actual net revenue
CriterionCompletion = ActualShare / target share * 100%
ShareGap = ActualShare - target share
```

При неположительной выручке доля недоступна. `TargetAmountToDate` — ориентир относительно уже
полученной выручки, не отдельный месячный денежный план. Модель пока не валидирует
`additionalShareTarget = accessoryShareTarget + serviceShareTarget`.

## Будущие дни

```text
FutureRevenuePerDay = actual revenue / elapsed days,
  либо monthly target / total days при отсутствии положительного факта
ProjectedMonthRevenue = actual revenue + FutureRevenuePerDay * remaining days
RequiredDirection = max(ProjectedMonthRevenue * target share / 100 - actual direction, 0)
```

Остаток распределяется по будущим дням, копейки — детерминированно в последний день. Поэтому
future target меняется после синхронизации.

## Смены

Смена содержит дату, сотрудника и часы. Для рейтинга нужна минимум одна смена. В payroll дневной
фонд делится поровну между сотрудниками смены: часы используются для учёта/эффективности, но не как
вес фонда. День фонда без смен снижает readiness.

На главной план остаётся месячным даже при week/custom. Смешение с выбранным периодом описано в
[ADR-0002](../../decisions/ADR-0002-overview-period-scope.md).
