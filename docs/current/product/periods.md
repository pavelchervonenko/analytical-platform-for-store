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
  - docs/analytics-business-rules-draft.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/service/StoreKpiPeriod.java
  - backend/src/main/java/com/storeanalytics/metrics/service/AverageKpiService.java
  - backend/src/main/java/com/storeanalytics/common/config/TimeConfig.java
  - frontend/src/stores/WorkspaceProvider.tsx
verification_sources:
  - frontend/src/stores/RangePeriodSelector.test.tsx
  - backend/src/test/java/com/storeanalytics/metrics/service/AverageKpiServiceTest.java
runtime_evidence: []
required_reviewers:
  - product
  - frontend
  - backend
review_triggers:
  - period-semantics-change
  - timezone-change
  - ui-change
supersedes: []
superseded_by: null
---

# Периоды расчёта

`start` и `end` — включительные календарные даты. Факт относится к периоду по `business_date`, а
не по моменту загрузки. KPI, категории, attach-rate и employee endpoints получают одну пару дат.

Предыдущий период непосредственно предшествует текущему и содержит столько же календарных дней:

```text
current:  2026-08-17..2026-08-23
previous: 2026-08-10..2026-08-16
```

План всегда считается от первого числа месяца до `asOf`; месячный отчёт — за полный календарный
месяц; годовой — по финализированным месяцам года. Выбор сегодняшней даты не доказывает
завершённость дня: дополнительно нужны sync coverage и quality.

## Timezone gap

Sales/returns normalization использует `businessZone` из `TimeConfig`, сейчас
`Europe/Kaliningrad`; часть frontend/planning/reporting читает timezone магазина. Пока зоны
совпадают, gap не виден. Другой timezone без end-to-end унификации способен сдвинуть
`business_date`, «сегодня» и границы отчёта.

## Overview gap

В week/custom суммы относятся к выбранному диапазону, а доли/отклонения плана — к месяцу до
`asOf`. До [ADR-0002](../../decisions/ADR-0002-overview-period-scope.md) такие commercial cards
нельзя трактовать как единый периодический показатель. Сквозного теста одинакового scope всех
Overview-запросов сейчас нет.
