# Production metrics audit — employee and attach-rate stage

Status: `IN_PROGRESS`

Audit window:

- production API snapshot: `2026-08-01..2026-08-19`;
- LiveSklad control exports: `2026-08-01..2026-08-18`;
- stores: `МАГАЗИН`, `МобиСфера`.

This stage extends `METRICS_AUDIT_2026-08-20.md`. The files will be consolidated after all audit
stages are complete.

## Confirmed controls

- Employee KPI rows, including non-participants, reconcile exactly to store revenue, quantity,
  cost and gross profit.
- Employee accessory, service and additional-revenue totals reconcile exactly to store category
  groups before frontend filtering.
- Every employee attach-rate reproduces exactly from its net unit numerator and denominator.
- `МАГАЗИН` rating scores reproduce from the configured `employee-rating-v1` weights and ranks are
  in the correct descending order.

## Findings

### METRIC-005 — seller summary is a filtered subtotal but resembles a store total

Severity: `MEDIUM`

The overview seller section includes only active assignments with
`participatesInRanking=true`. Store KPI includes every included sale, including employees outside
the rating.

At `2026-08-19`:

- `МАГАЗИН`: displayed seller revenue is `47,920,889 RUB` versus store revenue
  `50,505,169 RUB`; displayed gross profit is `8,177,153.63 RUB` versus store gross profit
  `8,324,133.63 RUB`;
- `МобиСфера`: displayed seller revenue is `14,507,938 RUB` versus store revenue
  `14,994,025 RUB`; displayed gross profit is `2,204,088.40 RUB` versus store gross profit
  `2,243,165.40 RUB`.

All employee KPI rows, including non-participants, reconcile exactly to the store totals. No facts
are lost; the discrepancy is created by frontend presentation filtering.

Recommended correction: rename the summary to “Участники рейтинга”, show an explicit excluded
amount/employee count, or provide an “all employees” total alongside the filtered subtotal.

### METRIC-006 — return employee attribution conflicts with the LiveSklad report

Severity: `HIGH`, business decision required

Production assigns a return reversal to the employee of the original sale. The LiveSklad
goods-and-services report displays the employee recorded on the return row. Store totals therefore
remain exact, while individual employee totals differ.

Observed transfers for `2026-08-01..2026-08-18`:

- `МАГАЗИН`: `2,100 RUB`, one unit and `910 RUB` gross profit move between two employees;
- `МобиСфера`: net `44,930 RUB`, two units and `8,071 RUB` gross profit move between two employees.

The repository contains conflicting requirements: the canonical employee KPI documentation and
current code use the original seller, while the later customer formula audit states that the
employee from the return report row is required.

Impact: employee revenue, gross profit, category shares, attach-rate, ranking and potentially
payroll attribution can differ from LiveSklad employee reports even when store totals match.

Recommended correction: first confirm whether a return must affect the original seller or the
return-processing employee. Persist both identities. Use the confirmed KPI identity explicitly and
add a regression fixture where the two employees differ.

### METRIC-007 — store attach-rate benchmark includes employees hidden from the map

Severity: `MEDIUM`, methodology clarification required

The store attach-rate column uses all store documents. Employee columns include only active
ranking participants. The visible employee numerators and denominators therefore do not always sum
to the store column.

Example for `МАГАЗИН` at `2026-08-19`:

- charger/cable store rate: `239 / 559 = 42.75%`;
- displayed ranking participants: `239 / 535 = 44.67%`;
- new-device warranty store rate: `148 / 398 = 37.19%`;
- displayed ranking participants: `148 / 375 = 39.47%`.

The current rating formula deliberately compares employees with the all-store rate. This is
internally consistent, but hidden non-participants change the benchmark and therefore employee
attach scores.

Recommended action: confirm that “store benchmark” must include non-participants. If retained, show
an “outside rating” contribution or an explanatory tooltip in the map.

### METRIC-008 — attach-map heat colors do not mean what the legend suggests

Severity: `MEDIUM`

The map labels colors as “Ниже / Среднее / Выше”, but color levels are fixed absolute bands
(`<25%`, `<50%`, `<75%`, `>=75%`). They are not calculated relative to the store value, plan or
team distribution.

Impact: an employee can outperform the store and still receive the “Ниже” color, or underperform
the store and receive a visually stronger band.

Recommended correction: because the panel is described as a comparison with the store, color by
employee/store ratio or percentage-point delta. Alternatively keep absolute bands and label their
numeric ranges explicitly.

### METRIC-009 — `МобиСфера` employee ranking is blocked by absent shifts

Severity: `HIGH`, production data issue

All three ranking participants with sales have zero shifts and zero worked hours for
`2026-08-01..2026-08-19`. Revenue and category metrics remain available, but contribution,
efficiency, structure, attach score, overall score and rank are intentionally not assigned.

Recommended correction: load and verify the work schedule for all 19 completed days before using
`МобиСфера` employee rankings or AI employee comparisons.

### METRIC-010 — “leader by additional share” has no minimum sample threshold

Severity: `LOW`, future robustness risk

The overview selects the highest/lowest additional-revenue share among visible employees with any
positive revenue. Shift count, revenue size and unit base are not considered.

Recommended correction: reuse a documented sufficiency threshold or display the revenue/base next
to the label and suppress leader/focus claims for very small samples.
