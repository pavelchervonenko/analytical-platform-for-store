# Category KPI API

Status: implemented read-only contract, revalidated on 2026-07-23. The frontend renders this data
without category regrouping or financial recalculation.

Category KPI reads normalized sale and return item snapshots from PostgreSQL. It never calls
LiveSklad and never recalculates historical product assignments.

## Endpoint

```text
GET /api/stores/{storeId}/kpi/categories?periodStart=2026-07-01&periodEnd=2026-07-31
```

Both dates are required and inclusive `business_date` boundaries. The caller must be authenticated,
must have completed a required password change and must have access to the requested store.

The response uses formula version `category-kpi-v2`:

```json
{
  "storeId": "00000000-0000-0000-0000-000000000000",
  "periodStart": "2026-07-01",
  "periodEnd": "2026-07-31",
  "formulaVersion": "category-kpi-v2",
  "groups": [
    {
      "groupCode": "PHONES",
      "groupName": "Телефоны",
      "metrics": {
        "netRevenue": 250000.00,
        "netQuantity": 25.000,
        "costAmount": 190000.00,
        "grossProfit": 60000.00,
        "averageGrossProfitPerUnit": 2400.00,
        "marginPercent": 24.00,
        "dataQuality": {
          "completeCostData": true,
          "includedItemCount": 27,
          "missingCostItemCount": 0,
          "unexpectedZeroCostItemCount": 0
        }
      }
    }
  ],
  "categories": [
    {
      "categoryCode": "IPHONE_NEW_ASIS",
      "categoryName": "iPhone New/ASIS+",
      "categoryKind": "DEVICE",
      "deviceFamily": "IPHONE",
      "categoryActive": true,
      "countsAsPhone": true,
      "countsAsDevice": true,
      "countsAsAdditionalRevenue": false,
      "metrics": {
        "netRevenue": 150000.00,
        "netQuantity": 15.000,
        "costAmount": 110000.00,
        "grossProfit": 40000.00,
        "averageGrossProfitPerUnit": 2666.67,
        "marginPercent": 26.67,
        "dataQuality": {
          "completeCostData": true,
          "includedItemCount": 16,
          "missingCostItemCount": 0,
          "unexpectedZeroCostItemCount": 0
        }
      }
    }
  ]
}
```

## Category semantics

- All analytics categories except `EXCLUDE` are returned, including categories with zero facts.
- Inactive categories remain visible so historical reports do not lose their dimension row.
- `UNMAPPED` is returned as a separate category and is not included in a business group.
- `EXCLUDE`, deleted documents and deleted items do not contribute to any category or group.
- Category membership comes from `sales_document_items.analytics_category_id`, which is captured
  when the item is normalized. A later product assignment does not rewrite that snapshot.
- Category definitions and their business flags are stable reference data. A semantic change must
  use a new category code instead of repurposing an existing code.

## Business groups

The response always contains three groups in this order:

1. `PHONES`: categories with `counts_as_phone = true`.
2. `DEVICES`: categories with `counts_as_device = true`; phones are intentionally included.
3. `ADDITIONAL_REVENUE`: categories with `counts_as_additional_revenue = true`.

Groups overlap by design: `PHONES` is a subset of `DEVICES`. Group values must not be added together
to obtain a store total. The category list itself is non-overlapping and reconciles with store KPI
because each normalized item has exactly one category snapshot.

## Formulas and data quality

For both categories and groups:

```text
Net revenue = sales net amount - return net amount
Net quantity = sold quantity - returned quantity
Cost amount = sales cost - return cost
Gross profit = net revenue - cost amount
Average gross profit per unit = gross profit / net quantity
Margin percent = gross profit / net revenue * 100
```

Average gross profit per unit is `null` when net quantity is zero or negative. The frontend displays
it only for categories where `countsAsDevice = true`. Margin is `null` when net revenue is zero.
If any included item in one category or group has missing cost, only that category or group receives
`completeCostData = false`; its `costAmount`, `grossProfit`, `averageGrossProfitPerUnit` and
`marginPercent` are `null`. Revenue and quantity remain available.

`ZERO_UNEXPECTED` does not make cost incomplete, but its count is exposed as a warning. Returns are
signed financial facts while `includedItemCount` and quality counters count physical item rows.

## Errors

- `400 INVALID_ARGUMENT`: malformed or reversed period.
- `401 AUTHENTICATION_REQUIRED`: no valid session.
- `403 ACCESS_DENIED`: no access to the store or password change is still required.
- `404 STORE_NOT_FOUND`: the store does not exist.
