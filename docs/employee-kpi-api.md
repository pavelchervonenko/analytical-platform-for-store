# API KPI сотрудников

Статус: read-only endpoint реализован и сверен 2026-07-23. Он возвращает финансовую детализацию,
но не заменяет отдельные API рейтинга, справочника и карточки из `employee-rating-api.md`.


## Endpoint

```http
GET /api/stores/{storeId}/kpi/employees?periodStart=2026-07-01&periodEnd=2026-07-31
```

Период включительный и использует те же правила нормализованных фактов и версию формул
`store-kpi-v1`, что и общий KPI магазина.

## Состав сотрудников

В ответ входят:

- все сотрудники, имеющие назначение в магазин, в том числе с нулевыми показателями;
- сотрудники с фактами выбранного периода, даже если текущего назначения уже нет;
- отдельная строка `Не назначен`, если существуют документы без `employee_id`.

Для каждого сотрудника возвращаются текущие признаки активности сотрудника и назначения.
Финансовые факты при их изменении не переписываются.

```text
rankingEligible = employeeActive
                  AND assignmentActive
                  AND participatesInRanking
                  AND NOT unassigned
```

Если `participatesInRanking` переключается в `false`, сотрудник остается в детализации, а его
продажи и возвраты продолжают входить в KPI сотрудника и магазина. Меняется только
`rankingEligible`.

Числовое место в рейтинге пока не возвращается: подтвержденной формулы ранжирования по набору KPI
нет, поэтому backend не подменяет её сортировкой только по выручке.

## Финансовые правила

- возврат уменьшает показатели сотрудника исходной продажи;
- удаленные документы и позиции игнорируются;
- `EXCLUDE` не участвует в расчете;
- `UNMAPPED` участвует в суммах и отражается в data quality;
- неполная себестоимость делает `costAmount`, `grossProfit` и `marginPercent` конкретного
  сотрудника неопределенными;
- при нулевой чистой выручке маржа отсутствует.

## Пример элемента

```json
{
  "employeeId": "00000000-0000-0000-0000-000000000010",
  "displayName": "Сотрудник",
  "employeeActive": true,
  "assignedToStore": true,
  "assignmentActive": true,
  "participatesInRanking": true,
  "rankingEligible": true,
  "unassigned": false,
  "netRevenue": 300.00,
  "netQuantity": 3.000,
  "costAmount": 200.00,
  "grossProfit": 100.00,
  "marginPercent": 33.33,
  "dataQuality": {
    "completeCostData": true,
    "includedItemCount": 2,
    "unmappedItemCount": 0,
    "missingCostItemCount": 0,
    "unexpectedZeroCostItemCount": 0
  }
}
```
