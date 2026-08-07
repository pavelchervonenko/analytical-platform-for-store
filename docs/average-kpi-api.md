# Average KPI API

Status: implemented read-only contract, revalidated on 2026-07-23. The response already contains
the comparison period and dynamics; the frontend must not recalculate them.


The average KPI endpoint calculates current values and their dynamics against the immediately
preceding period of equal calendar-day length. It reads normalized PostgreSQL facts and never calls
LiveSklad.

## Endpoint

```text
GET /api/stores/{storeId}/kpi/averages?periodStart=2026-07-10&periodEnd=2026-07-12
```

Both dates are required inclusive `business_date` boundaries. The caller must have a valid session,
must have completed a required password change and must have access to the requested store.

The response uses formula version `average-kpi-v1`:

```json
{
  "storeId": "00000000-0000-0000-0000-000000000000",
  "periodStart": "2026-07-10",
  "periodEnd": "2026-07-12",
  "previousPeriodStart": "2026-07-07",
  "previousPeriodEnd": "2026-07-09",
  "formulaVersion": "average-kpi-v1",
  "averageReceipt": {
    "current": {"numerator": 1750.00, "denominator": 2, "value": 875},
    "previous": {"numerator": 600.00, "denominator": 1, "value": 600},
    "changePercent": 45.8
  },
  "additionalRevenuePerPhone": {
    "current": {"numerator": 450.00, "denominator": 2.500, "value": 180},
    "previous": {"numerator": 100.00, "denominator": 1.000, "value": 100},
    "changePercent": 80.0
  },
  "categoryAveragePrices": [
    {
      "categoryCode": "CHARGER_CABLE",
      "categoryName": "Chargers and cables",
      "categoryActive": true,
      "averageUnitPrice": {
        "current": {"numerator": 450.00, "denominator": 3.500, "value": 129},
        "previous": {"numerator": 100.00, "denominator": 1.000, "value": 100},
        "changePercent": 28.6
      }
    }
  ]
}
```

The endpoint returns every analytics category except `EXCLUDE`, including inactive and zero rows.
`UNMAPPED` remains visible so unclassified revenue is not silently hidden.

## Formulas

```text
Average receipt = signed net revenue / completed sale document count
Additional revenue per phone = signed additional revenue / signed phone quantity
Category average unit price = signed category net revenue / signed category quantity
Change, % = (current raw value - previous raw value) / previous raw value * 100
```

Sales contribute positively and returns negatively. Deleted documents/items and `EXCLUDE` items do
not contribute. A return does not count as a completed receipt; every non-deleted `SALE` document in
the requested store and period counts once.

The previous interval ends one day before `periodStart` and contains exactly the same number of
inclusive calendar days as the requested interval. For example, `2026-07-10..2026-07-12` compares
with `2026-07-07..2026-07-09`.

Money inputs preserve two decimal places and quantity inputs preserve three. Displayed average
values are rounded to a whole ruble with `HALF_UP`; percentages are rounded to one decimal place.
The percentage is calculated from unrounded averages, preventing display rounding from distorting
the comparison.

If a current denominator is zero or negative, its `value` is `null`. `changePercent` is `null` when
either period has no valid value or when the previous raw average equals zero.

## Errors

- `400 INVALID_ARGUMENT`: malformed or reversed period;
- `401 AUTHENTICATION_REQUIRED`: no valid session;
- `403 ACCESS_DENIED`: no store access or password change is still required;
- `404 STORE_NOT_FOUND`: the store does not exist.
