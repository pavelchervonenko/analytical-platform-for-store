---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-09-16
requirement_sources:
  - docs/archive/legacy-contracts/store-plan-progress-api.md
  - docs/archive/discoveries/analytics-business-rules-draft.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/performance/service/StorePlanProgressService.java
  - backend/src/main/java/com/storeanalytics/metrics/service/OverviewMetricsService.java
  - backend/src/main/java/com/storeanalytics/performance/model/StorePlanTargets.java
  - frontend/src/plan-schedule/PlanSchedulePage.tsx
  - frontend/src/plan-schedule/PlanPanel.tsx
  - frontend/src/plan-schedule/PlanSettingsPanel.tsx
  - frontend/src/plan-schedule/DailyPlanTable.tsx
  - frontend/src/plan-schedule/SchedulePanel.tsx
  - frontend/src/plan-schedule/forms.ts
  - frontend/src/api/client.ts
verification_sources:
  - backend/src/test/java/com/storeanalytics/performance/service/WorkScheduleServiceTest.java
  - backend/src/test/java/com/storeanalytics/performance/service/StorePlanProgressServiceTest.java
  - backend/src/test/java/com/storeanalytics/performance/web/StorePlanProgressControllerTest.java
  - frontend/src/plan-schedule/PlanPanel.test.tsx
  - frontend/src/plan-schedule/PlanSettingsPanel.test.tsx
  - frontend/src/plan-schedule/DailyPlanTable.test.tsx
  - frontend/src/plan-schedule/SchedulePanel.test.tsx
  - frontend/src/plan-schedule/forms.test.ts
  - frontend/src/api/etag-client.test.ts
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

Для каждого магазина и каждого календарного месяца создаётся отдельный план. План прошлого месяца
не переносится автоматически. Внутри выбранного месяца набор целей один: факт считается от первого
числа до включительной `asOf`, а cohort задаётся параметром `scope`. `SELLERS` применяет цели
месяца только к продавцам рейтинга, `STORE` — ко всему магазину. Отдельных значений плана для двух
режимов нет.

Отдельный экран управления «План» позволяет переключать progress между `SELLERS` и `STORE`, по
умолчанию открывая `SELLERS`. Это переключение меняет только состав факта, прогноза и дневных
ориентиров; второй план и второй набор целей не создаются.

Экран плана разделяет контроль выполнения и редактирование целей. «Обзор плана» отвечает на три
вопроса менеджера: где магазин находится сейчас, какое действие важнее всего и что требуется в
ближайший рабочий день. «Настройка плана» содержит согласованную форму четырёх месячных целей и не
дублируется в обзоре.

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

Статус `ACHIEVED` для доли означает, что фактическая доля уже достигла цели на текущую `asOf`.
До конца месяца недостигнутая доля имеет `AT_RISK`; в последний день — `MISSED`. В отличие от
выручки, прогноз суммы долевого направления не переводит статус в `ON_TRACK`. Поэтому статус
«Выполнено» у услуг или допов может появиться в начале месяца и не гарантирует, что доля останется
выше цели после следующих продаж.

В открытом месяце интерфейс показывает для долевых направлений backend-прогноз суммы на конец
месяца. Он не называется прогнозом доли и не является основанием статуса. Для выручки рядом с
прогнозом суммы показывается `projectedAmountCompletionPercent`. После закрытия месяца прогнозы
заменяются итоговыми отклонениями. При неполном покрытии данных прогнозы скрываются. Неполная
классификация блокирует прогнозы долевых направлений, но не прогноз выручки при полном покрытии.

При неположительной выручке доля недоступна. Доля считается достигшей цели, как только фактическая
сумма направления не меньше `TargetAmountToDate`; это оценка по данным на текущую `asOf`.
`TargetAmountToDate` — ориентир относительно уже полученной выручки, не отдельный фиксированный
денежный план на месяц. Расчёт факта контролирует
`actual additional = actual accessory + actual service`. Модель плана пока не валидирует
`additionalShareTarget = accessoryShareTarget + serviceShareTarget`.

Цель «Доп. выручка» остаётся самостоятельным вводимым значением. Интерфейс не вычисляет её как
сумму целей аксессуаров и услуг, не блокирует сохранение при несовпадении и не подменяет введённое
заказчиком значение. При этом фактическая сумма дополнительной выручки по действующему контракту
складывается из факта аксессуаров и услуг.

## Будущие дни

```text
FutureRevenuePerDay = actual revenue / elapsed days,
  либо monthly target / total days при отсутствии положительного факта
ProjectedMonthRevenue = actual revenue + FutureRevenuePerDay * remaining days
RequiredDirection = max(ProjectedMonthRevenue * target share / 100 - actual direction, 0)
```

Остаток распределяется по будущим дням, копейки — детерминированно в последний день. Поэтому
future target меняется после синхронизации.

`FutureRevenuePerDay` в интерфейсе называется «Расчётная выручка дня»: это база для пересчёта
целевых сумм аксессуаров и услуг, а не обещание фактической выручки конкретного дня. Ближайший
будущий день является основным операционным ориентиром. Для завершённых дней
`cumulativeGapAmount` показывается как «Отклонение с начала месяца», поскольку значение накоплено
за месяц, а не относится только к одной дате.

Достижение всех долей до закрытия месяца не означает окончательное выполнение месячного плана:
последующие продажи могут изменить структуру. До последнего дня экран использует промежуточный
статус и не сообщает, что весь план окончательно выполнен.

## Смены

Смена содержит дату, сотрудника и часы. Для добавления новой смены интерфейс предлагает только
активных сотрудников с активным назначением и `participatesInRanking=true`. Ранее сохранённая смена
сотрудника, который позже перестал соответствовать roster, остаётся видимой, но недоступна для
повторного выбора; её удаляют явным редактированием дня.

Состав дня общий для магазина, а не принадлежит создавшему его пользователю. Любой пользователь с
доступом к сменам этого магазина может заменить состав, изменить часы и очистить день независимо
от автора предыдущей версии. Strong `ETag` защищает только от настоящего одновременного
перезаписывания. При конфликте приложение само загружает актуальную версию, переносит на неё
изменения выбранных сотрудников и ограниченно повторяет сохранение. Параллельные изменения других
сотрудников сохраняются; явная команда очистки по-прежнему очищает весь актуальный состав дня.
Клиент запрашивает ресурсы с ETag с директивой `Cache-Control: no-transform`: публичный Caddy не
добавляет к opaque concurrency token суффикс выбранного gzip/zstd-представления, поэтому
последующий `If-Match` сравнивается backend с той же версией агрегата.

Для рейтинга нужна минимум одна смена. В payroll дневной фонд делится поровну между сотрудниками
смены: часы используются для учёта/эффективности, но не как вес фонда. День фонда без смен снижает
readiness.

На главной план остаётся месячным даже при week/custom. В week/custom он показывается отдельным
блоком и не подменяет selected-period share; решение закреплено в
[ADR-0002](../../decisions/ADR-0002-overview-period-scope.md).
