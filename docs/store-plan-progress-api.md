# Выполнение плана магазина

Статус: read-only progress реализован и повторно сверен 2026-08-24. Создание/изменение самого плана и
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

Для долевого направления денежный ориентир считается от полной фактической чистой выручки
на дату среза, как требует согласованная методика заказчика:

```text
targetAmount = actualNetRevenue × targetSharePercent / 100
```

Поэтому `expectedAmountToDate` для долевого направления равен `targetAmount`, а
`paceGapAmount = actualAmount - targetAmount`. `remainingAmount` и
`requiredPerRemainingDay` показывают только накопленное отставание на дату среза. Для выручки
сохраняется календарный месячный темп.

## Дневной план аксессуаров и услуг

`dailyTargets` содержит все календарные дни месяца. Для завершённых дней возвращаются фактическая
дневная выручка, сумма и процент направления. Дневной ориентир завершённого дня равен фактической
выручке дня, умноженной на месячный целевой процент.

Для будущих дней backend сначала определяет прогнозную дневную выручку:

```text
futureRevenuePerDay =
  actualNetRevenue / elapsedDays, если actualNetRevenue > 0
  revenueTarget / totalDays, иначе

projectedMonthRevenue =
  actualNetRevenue + futureRevenuePerDay × remainingDays

futureDirectionRequired =
  max(projectedMonthRevenue × targetSharePercent / 100 - actualDirectionAmount, 0)
```

`futureDirectionRequired` равномерно распределяется на оставшиеся дни. Последний день получает
остаток копеек, поэтому сумма строк точно совпадает с требуемой суммой. Будущий дневной процент равен
дневной целевой сумме, делённой на прогнозную выручку дня. При отставании он растёт, при опережении
снижается, а если накопленный результат уже покрывает прогнозный месячный ориентир — равен 0%.

Возвраты уменьшают выручку и соответствующее направление в дату возврата. Исходные вычисления идут
без промежуточного округления; денежные поля ответа округляются до копеек, проценты — до 0,01 п.п.

## Подписи в интерфейсе

| Поле/смысл | Рекомендуемая подпись |
| --- | --- |
| полный target месяца | «План месяца» |
| факт относительно полного target | «Выполнено» |
| `expectedAmountToDate` | «Темп на дату» |
| `remainingAmount` | «Осталось по плану» |
| `requiredPerRemainingDay` | «Нужно в день» |
| будущий `dailyTargets.*.targetAmount` | «Цель дня» |

«Нужно в день» не фиксированный план: оно пересчитывается после каждого нового факта, возврата,
изменения плана, даты среза или количества оставшихся дней. Future-day target нельзя подписывать
как факт или как неизменную первоначальную норму.

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

interface StorePlanDailyDirectionView {
  actualAmount: number | null;
  actualSharePercent: number | null;
  targetAmount: number;
  targetSharePercent: number | null;
  cumulativeGapAmount: number | null;
}

interface StorePlanDailyTargetView {
  date: string;
  completed: boolean;
  revenueBasisAmount: number;
  revenueBasisProjected: boolean;
  accessory: StorePlanDailyDirectionView;
  service: StorePlanDailyDirectionView;
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
  formulaVersion: "store-plan-progress-v2";
  plan: StorePerformancePlanView;
  dataQuality: StorePlanProgressDataQuality;
  achievedDirectionCount: number;
  allDirectionsAchieved: boolean;
  focusDirections: StorePlanDirectionCode[];
  directions: StorePlanDirectionView[];
  calculatedAt: string;
  dailyTargets: StorePlanDailyTargetView[];
}
```

`classificationComplete=false` означает, что доли могут быть занижены из-за `UNMAPPED`.
`completeThroughAsOf=false` означает, что синхронизация еще не подтвердила полное покрытие продаж и
возвратов до выбранной даты. Значения при этом возвращаются, но интерфейс обязан показать
предупреждение.
