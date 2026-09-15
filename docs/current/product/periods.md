---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-09-14
requirement_sources:
  - docs/archive/discoveries/analytics-business-rules-draft.md
  - docs/decisions/ADR-0002-overview-period-scope.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/service/StoreKpiPeriod.java
  - backend/src/main/java/com/storeanalytics/metrics/service/AverageKpiService.java
  - backend/src/main/java/com/storeanalytics/metrics/service/OverviewMetricsService.java
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeCardService.java
  - backend/src/main/java/com/storeanalytics/performance/service/StorePlanProgressService.java
  - backend/src/main/java/com/storeanalytics/common/config/TimeConfig.java
  - frontend/src/stores/WorkspaceProvider.tsx
verification_sources:
  - frontend/src/stores/RangePeriodSelector.test.tsx
  - frontend/src/stores/WorkspaceProvider.test.tsx
  - frontend/src/shared/date.test.ts
  - backend/src/test/java/com/storeanalytics/metrics/service/AverageKpiServiceTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/OverviewMetricsServiceTest.java
  - backend/src/test/java/com/storeanalytics/performance/service/EmployeeCardServiceTest.java
  - backend/src/test/java/com/storeanalytics/performance/service/StorePlanProgressServiceTest.java
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

На странице «Сотрудники» список всегда сравнивает выбранный диапазон с таким предыдущим равным
периодом. Карточка сотрудника использует явный режим сравнения:

- для `WEEK` обе границы сдвигаются ровно на семь дней (`PREVIOUS_WEEK`);
- для `MONTH` и `CUSTOM` берётся непосредственно предшествующий равный по длительности диапазон
  (`PREVIOUS_PERIOD`).

Поэтому неполный месяц `2026-08-01..2026-08-28` сравнивается с
`2026-07-04..2026-07-31`, а не с полным июлем. Для семидневного диапазона оба правила дают одну и
ту же предыдущую неделю.

План всегда считается от первого числа месяца до `asOf`; месячный отчёт — за полный календарный
месяц; годовой — по финализированным месяцам года. Выбор сегодняшней даты не доказывает
завершённость дня: дополнительно нужны sync coverage и quality.

Если аналитический период не является целым месяцем, месячный план использует календарный месяц
даты окончания периода. Это правило применяется к дню, неделе, произвольному диапазону, стыку
месяцев и стыку годов. Для текущего месяца `asOf` ограничивается последним завершённым и
загруженным днём; исторический месяц считается до своего последнего дня.

## Timezone gap

Sales/returns normalization использует `businessZone` из `TimeConfig`, сейчас
`Europe/Kaliningrad`; часть frontend/planning/reporting читает timezone магазина. Пока зоны
совпадают, gap не виден. Другой timezone без end-to-end унификации способен сдвинуть
`business_date`, «сегодня» и границы отчёта.

## Главная страница и месячный план

Верхние показатели и структура продаж на главной используют один выбранный диапазон и один scope:
`SELLERS` по умолчанию либо `STORE`. Месячный план остаётся отдельным контрактом от первого числа
месяца до `asOf`; недельный или пропорциональный план не создаётся.

В режиме месяца target/gap можно показывать рядом с фактом того же месяца. В `WEEK`/`CUSTOM`
верхние карточки содержат только факт выбранного периода, а прогресс месячного плана показывается
отдельно для месяца даты окончания периода. Его scope совпадает с переключателем «Обзора».
Отдельный раздел «План» имеет независимый scope с `SELLERS` по умолчанию. Правило разделения
selected-period facts и month plan принято и реализовано в
[ADR-0002](../../decisions/ADR-0002-overview-period-scope.md).
