# Выполнение плана магазина

Статус: read-only progress реализован и сверен 2026-07-23. Создание/изменение самого плана и
условия кнопок описаны в `employee-rating-api.md` и `docs/frontend-actions.md`.


## Назначение

`GET /api/stores/{storeId}/performance-plans/{yyyy-MM}/progress?asOf=YYYY-MM-DD`
возвращает единый срез плана магазина, факта, долей, темпа и прогноза. Endpoint read-only и не
изменяет план или зарплатный расчет.

`asOf` обязателен и должен находиться внутри запрошенного месяца. Явная дата не позволяет незаметно
смешать закрытый день с текущим незавершенным днем. Период факта всегда начинается с первого числа
месяца и включает `asOf`.

Endpoint требует доступ пользователя к магазину. Отсутствующий план возвращает
`404 PERFORMANCE_PLAN_NOT_FOUND`, чужой магазин — `403`.

## Направления

Ответ всегда содержит четыре направления в стабильном порядке:

1. `REVENUE` — общая чистая выручка;
2. `ACCESSORY` — категории с `categoryKind=ACCESSORY`;
3. `SERVICE` — категории `SERVICE`, `WARRANTY`, `PROTECTION`;
4. `ADDITIONAL` — все категории с `countsAsAdditionalRevenue=true`.

Продажи и возвраты используют общие правила KPI. Возврат относится к дате возврата и уменьшает
факт выбранного периода.

Для `REVENUE` критерием является сумма: `actualAmount >= targetAmount`. Для остальных направлений
критерием является доля в фактической чистой выручке:

```text
actualSharePercent = actualAmount / netRevenue × 100
achieved = actualAmount × 100 >= targetSharePercent × netRevenue
criterionCompletionPercent = actualSharePercent / targetSharePercent × 100
```

Сравнение выполняется по исходным значениям без промежуточного округления. При нулевой или
отрицательной чистой выручке доля равна `null`, а долевой критерий не выполнен.

## Денежный ориентир и темп

Для долевого направления денежный ориентир рассчитывается так:

```text
targetAmount = revenueTarget × targetSharePercent / 100
```

Он нужен для полосы выполнения, остатка и дневного темпа, но не заменяет долевой критерий. Поэтому
`amountCompletionPercent` и `criterionCompletionPercent` могут различаться.

Все темпы первой версии используют календарные дни:

```text
currentDailyPace = actualAmount / elapsedDays
expectedAmountToDate = targetAmount × elapsedDays / totalDays
paceGapAmount = actualAmount - expectedAmountToDate
projectedAmount = actualAmount × totalDays / elapsedDays
remainingAmount = max(targetAmount - actualAmount, 0)
requiredPerRemainingDay = remainingAmount / remainingDays
```

Если план уже набран, остаток и требуемый темп равны нулю. В последний день при ненабранном плане
`requiredPerRemainingDay=null`, потому что оставшихся дней нет.

## Статусы

`StorePlanProgressStatus`:

- `ACHIEVED` — фактический критерий направления уже выполнен;
- `ON_TRACK` — денежный план выручки еще не выполнен, но прогноз достигает цели;
- `AT_RISK` — критерий доступен, но текущий результат/прогноз ниже цели;
- `MISSED` — последний день месяца завершен без выполнения критерия;
- `NOT_AVAILABLE` — до конца месяца еще есть время, но долю нельзя рассчитать из-за неположительной
  выручки.

`focusDirections` содержит направления со статусами `AT_RISK`, `MISSED` и `NOT_AVAILABLE` в
порядке отображения. Backend не генерирует текстовый управленческий вывод: frontend и будущая LLM
используют коды, исходные значения и статусы.

## Контракт

```ts
type StorePlanDirectionCode = "REVENUE" | "ACCESSORY" | "SERVICE" | "ADDITIONAL";
type StorePlanCriterionType = "AMOUNT" | "SHARE";
type StorePlanProgressStatus =
  | "ACHIEVED"
  | "ON_TRACK"
  | "AT_RISK"
  | "MISSED"
  | "NOT_AVAILABLE";

interface StorePlanDirectionView {
  code: StorePlanDirectionCode;
  criterionType: StorePlanCriterionType;
  actualAmount: number;
  targetAmount: number;
  amountCompletionPercent: number | null;
  currentDailyPace: number;
  expectedAmountToDate: number;
  paceGapAmount: number;
  projectedAmount: number;
  projectedAmountCompletionPercent: number | null;
  remainingAmount: number;
  requiredPerRemainingDay: number | null;
  actualSharePercent: number | null;
  targetSharePercent: number | null;
  shareGapPercentagePoints: number | null;
  criterionCompletionPercent: number | null;
  achieved: boolean;
  status: StorePlanProgressStatus;
}

interface StorePlanProgressDataQuality {
  freshnessStatus: StoreDataFreshnessStatus;
  dataThroughDate: string | null;
  completeThroughAsOf: boolean;
  classificationComplete: boolean;
  unmappedItemCount: number;
  openQualityIssueCount: number;
}

interface StorePlanProgressView {
  storeId: string;
  periodStart: string;
  periodEnd: string;
  asOfDate: string;
  totalDays: number;
  elapsedDays: number;
  remainingDays: number;
  formulaVersion: "store-plan-progress-v1";
  plan: StorePerformancePlanView;
  dataQuality: StorePlanProgressDataQuality;
  achievedDirectionCount: number;
  allDirectionsAchieved: boolean;
  focusDirections: StorePlanDirectionCode[];
  directions: StorePlanDirectionView[];
  calculatedAt: string;
}
```

`classificationComplete=false` означает, что доли могут быть занижены из-за `UNMAPPED`.
`completeThroughAsOf=false` означает, что синхронизация еще не подтвердила полное покрытие продаж и
возвратов до выбранной даты. Значения при этом возвращаются, но интерфейс обязан показать
предупреждение.
