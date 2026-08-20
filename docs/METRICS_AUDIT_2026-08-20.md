# Production metrics audit — August 2026

Status: `IN_PROGRESS`

Audit window:

- production API snapshot: `2026-08-01..2026-08-19`;
- LiveSklad control exports: `2026-08-01..2026-08-18`;
- stores: `МАГАЗИН`, `МобиСфера`.

The audit is read-only. A finding is recorded only after its calculation or presentation path is
reproduced from production data and the applicable backend/frontend rule.

## Confirmed controls

- Store revenue and gross profit for `2026-08-01..2026-08-18` reconcile exactly to the LiveSklad
  sales plus issued-order exports for both stores.
- Category totals reconcile to store KPI totals for revenue, quantity, cost and gross profit.
- `ADDITIONAL_REVENUE = ACCESSORY + SERVICE` for revenue, quantity, cost and gross profit.
- Employee accessory, service and additional-revenue totals reconcile to the corresponding store
  groups before presentation filtering.
- All production attach-rate values reproduce exactly from their net unit numerators and
  denominators under `attach-rate-v3`.
- Both configured plans satisfy
  `additionalSharePercent = accessorySharePercent + serviceSharePercent`.

## Findings

### METRIC-001 — one analytical product is unmapped

Severity: `MEDIUM`

Store `МАГАЗИН` contains one `UNMAPPED` sales item in the audit period: `Яндекс Станция Макс
бежевый`, one unit, revenue `27,900 RUB`, gross profit `1,400 RUB`.

Impact:

- store revenue, gross profit and monthly share plans remain correct;
- `DEVICES` revenue and quantity are understated by `27,900 RUB` and one unit;
- source classification quality remains incomplete;
- the current item does not affect phone or attach-rate denominators.

Recommended correction: assign the effective analytical category intended for non-phone devices
(`PODS_WATCH_OTHER_DEVICE`) and refresh affected analytical facts through the supported
classification workflow.

### METRIC-002 — “Sales structure” renders overlapping groups as peers

Severity: `MEDIUM`

The dashboard renders five flat rows although they are not a partition:

- `PHONES` is a subset of `DEVICES`;
- `ADDITIONAL_REVENUE` is the sum of `ACCESSORY` and `SERVICE`.

Impact: a user can reasonably interpret the panel as additive sales structure and double-count
revenue and units. The underlying group calculations are correct.

Recommended correction: render the primary partition as devices, accessories, services and
unmapped/other. Show phones as an “of which” child of devices and additional revenue as a subtotal,
not as peer rows.

### METRIC-003 — two different daily plan meanings use similar labels

Severity: `MEDIUM`

`StorePlanDirectionView.requiredPerRemainingDay` distributes only the accumulated share gap at the
cutoff date. `dailyTargets` distributes the full future amount needed to preserve/reach the target
share against projected future revenue.

For `МАГАЗИН` accessories at `2026-08-19`:

- accumulated-gap recovery: `46,987.30 RUB/day`;
- full future daily target: approximately `214,451.81 RUB/day`.

Both calculations follow the documented formulas, but the UI can present both as “needed per day”.

Recommended correction: label the first value “Закрыть текущее отставание в день” and the daily
schedule value “Цель на будущий день с учётом прогноза выручки”.

### METRIC-004 — LiveSklad and application unit totals differ by one explained excluded item

Severity: `INFO`

For `МобиСфера` on `2026-08-01..2026-08-18`, LiveSklad reports `537` net units while store KPI
reports `536`. Revenue and gross profit reconcile exactly. The difference is one zero-value
`ДИАГНОСТИКА` position installed through an order and excluded from analytical KPI.

Recommended action: no formula change. Document in UI/help that excluded zero-value work can make
the application unit count differ from the raw LiveSklad goods-and-services row count.

## Pending stages

- employee metrics and presentation visibility;
- attach-rate employee map and exclusions;
- category details;
- every employee card metric;
- consolidated correction and regression-test plan.
