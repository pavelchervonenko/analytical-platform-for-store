---
doc_schema: 1
doc_type: evidence
status: historical
owner: product
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS_WITH_LIMITS
verdict_scope: "Preserved legacy evidence; commands and runtime claims require current verification."
source_of_truth:
  - "docs/current/product/README.md"
original_content_sha256: 9dd1bfd148698bca36c66a3bf8e6a79191de267506b9abfdb5020ad481d35bde
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/product/README.md`.

# Production metrics audit — categories and employee cards

Status: `COMPLETE`

Audit window:

- production API snapshot: `2026-08-01..2026-08-19`;
- stores: `МАГАЗИН`, `МобиСфера`;
- employee cards checked: all `20` for `МАГАЗИН` and all `18` for `МобиСфера`.

This stage extends `METRICS_AUDIT_2026-08-20.md`. The stage files will be consolidated after the
full audit.

## Confirmed controls

- Every employee category list sums exactly to the employee net revenue.
- Every employee satisfies `ADDITIONAL_REVENUE = ACCESSORY + SERVICE` for revenue and quantity.
- Employee category shares reproduce exactly from category revenue divided by employee revenue.
- Employee category totals reconcile to every corresponding store category. The only machine-level
  non-zero difference was a JSON floating-point artifact below `0.0000000001 RUB`; PostgreSQL money
  values remain exact to two decimals.
- All `38` employee cards return one consistent `employee-rating-v1` formula and one consistent
  store plan context per store.
- Card revenue, revenue/hour, share, score and rank dynamics reproduce from current and previous
  backend values.
- Every attach-rate dynamics row matches the current employee rate and current-minus-previous
  percentage-point change.
- No payroll card is returned for the partial `2026-08-01..2026-08-19` period, which matches the
  full-calendar-month payroll contract.
- “Вал / ед. техники” is intentionally displayed only when `countsAsDevice=true`. The backend
  average-per-unit value itself is available for other categories but is not a device KPI.

## Findings

### METRIC-011 — unexpected zero cost is still presented as complete cost data

Severity: `HIGH`, deferred data-quality work

`МАГАЗИН` contains four included accessory items with `ZERO_UNEXPECTED` cost quality:

- three in `CHARGER_CABLE`;
- one in `OTHER_ACCESSORY_PRODUCT`.

The category and store responses set `completeCostData=true` because that flag currently checks only
`cost_amount IS NULL`. The frontend therefore displays “Полные” and does not qualify gross profit,
even though unexpected zero costs can overstate it.

The production totals still reconcile exactly to LiveSklad because LiveSklad itself supplies these
zero costs. The issue is reliability, not arithmetic reconciliation.

Recommended correction when zero-cost work resumes: define completeness as no missing costs and no
unexpected zero costs, retain the separate count, and show “Требует проверки” until each zero is
confirmed as legitimate or corrected.

### METRIC-012 — employee plan percentage and monthly plan percentage use different bases

Severity: `MEDIUM`

The main monthly plan compares actual revenue with the full month target. Employee rating and cards
compare the same revenue with a calendar-prorated target for the selected period.

At `2026-08-19`:

- `МАГАЗИН`: main monthly completion `91.83%`; employee plan context `149.82%`;
- `МобиСфера`: main monthly completion `74.97%`; employee plan context `122.32%`.

Both calculations are correct. The employee UI labels the latter as “Покрытие плана”, “Выполнение
выручки” and “План магазина” without saying that it is the plan-to-date pace. This can look like a
calculation error when moving between screens.

Recommended correction: label the employee value “Выполнение плана на дату” or “Темп к
календарному плану” and optionally show the full-month completion beside it.

### METRIC-013 — `МобиСфера` cards are arithmetically valid but not decision-ready

Severity: `HIGH`, production data issue

All card formulas and dynamics are valid, but current score and rank fields are unavailable for the
three participants because the store has no shifts in the audited period. The UI correctly shows
the absence, but no employee comparison, ranking or AI conclusion based on workload should be used
until shifts are restored.

Recommended correction: repair the schedule data and then repeat this card stage; no rating formula
change is indicated by the current evidence.
