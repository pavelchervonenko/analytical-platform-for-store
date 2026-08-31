---
doc_schema: 1
doc_type: archive
status: archived
owner: product
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "docs/current/api/store-kpi.md"
original_content_sha256: 667da7c9de571fd8238bf19f9b0d9493fa31a87945e8c1172ab72487b8fa281e
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/api/store-kpi.md`.

# API KPI магазина

Статус: read-only endpoint реализован и сверен 2026-07-23. Frontend использует его на экране
«Обзор»; мутаций для этого среза нет.


## Endpoint

```http
GET /api/stores/{storeId}/kpi?periodStart=2026-07-01&periodEnd=2026-07-31
```

Обе границы обязательны, передаются как ISO-даты и включаются в расчет. Endpoint защищен общей
конфигурацией Spring Security. Неизвестный магазин возвращает `404` с кодом `STORE_NOT_FOUND`,
некорректный, обратный или превышающий 366 календарных дней период — `400 INVALID_ARGUMENT`.

## Контракт расчета

Endpoint читает только нормализованные `sales_documents`, `sales_document_items` и сохраненные
снимки категорий. Он не обращается к LiveSklad и не разбирает raw payload.

- активная позиция продажи учитывается с положительным знаком;
- активная позиция возврата учитывается с отрицательным знаком;
- удаленные документы и позиции игнорируются;
- категория `EXCLUDE` игнорируется;
- категория `UNMAPPED` остается в финансовых итогах и отражается в блоке качества;
- фильтрация использует включительный интервал `business_date` и выбранный магазин;
- при нулевой чистой выручке маржа отсутствует;
- если хотя бы у одной включенной позиции нет себестоимости, себестоимость, валовая прибыль и
  маржа отсутствуют, а не рассчитываются из неполной суммы;
- `storeOpenQualityIssueCount` — текущий счетчик открытых проблем всего магазина, а не
  исторический счетчик выбранного периода.

Версия формул: `store-kpi-v1`.

```text
netRevenue = выручка продаж - выручка возвратов
netQuantity = количество продаж - количество возвратов
costAmount = себестоимость продаж - себестоимость возвратов
grossProfit = netRevenue - costAmount
marginPercent = grossProfit / netRevenue * 100
```

Деньги возвращаются с точностью 2 знака, количество — 3 знака, процент маржи — 2 знака.

## Пример ответа

```json
{
  "storeId": "00000000-0000-0000-0000-000000000001",
  "periodStart": "2026-07-01",
  "periodEnd": "2026-07-31",
  "formulaVersion": "store-kpi-v1",
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
    "unexpectedZeroCostItemCount": 0,
    "storeOpenQualityIssueCount": 0
  }
}
```
