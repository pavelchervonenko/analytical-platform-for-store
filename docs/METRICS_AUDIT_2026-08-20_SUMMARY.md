# Production metrics audit — consolidated summary

Status: `COMPLETE`

Audited scope:

1. add-ons, accessories, services, plans, remaining targets and percentages;
2. sales structure;
3. monthly plan;
4. seller metrics;
5. attach-rate map;
6. sales categories;
7. all metrics in every production employee card.

Evidence:

- production API data through `2026-08-19`;
- LiveSklad exports for `2026-08-01..2026-08-18`;
- all `20` `МАГАЗИН` employee cards;
- all `18` `МобиСфера` employee cards;
- backend formula and repository implementations;
- frontend selection and presentation paths;
- existing backend and frontend regression tests.

Detailed findings are recorded in:

- `METRICS_AUDIT_2026-08-20.md`;
- `METRICS_AUDIT_2026-08-20_EMPLOYEES.md`;
- `METRICS_AUDIT_2026-08-20_CATEGORIES_AND_CARDS.md`.

## Overall conclusion

No store-level monetary arithmetic defect was found for the control period. Revenue, cost and gross
profit reconcile to LiveSklad exports for both stores. Category, employee and attach-rate formulas
are internally consistent and repeatable.

The application is not yet fully decision-ready for every displayed metric because of one business
rule conflict, two production data problems and several presentation mismatches.

## Priority 0 — resolve before trusting employee results

### Return employee attribution

Current production charges a return to the original seller. The current LiveSklad report attributes
the return row to another employee in observed cases. The repository contains both rules in
different documents.

Required decision:

- `ORIGINAL_SELLER`: preserve current behavior and explain why per-employee values intentionally do
  not match the raw LiveSklad employee column; or
- `RETURN_ROW_EMPLOYEE`: change normalization/KPI/payroll attribution to the verified LiveSklad
  identity used in the report.

Do not change this silently. Persist original seller, return-document employee and cash-operation
employee separately so the selected rule remains auditable.

### `МобиСфера` shifts

No shifts exist for the completed August period. Store and employee sales remain correct, but all
ranking and workload-dependent AI conclusions are unavailable. Restore and verify the schedule
before recalculating or publishing employee rankings.

## Priority 1 — safe corrections with confirmed intent

1. Classify `Яндекс Станция Макс бежевый` as the intended other-device analytical category and
   refresh affected facts through the supported workflow.
2. Present sales structure hierarchically: phones under devices; additional revenue as the subtotal
   of accessories and services.
3. Rename seller summary totals to make the rating-participant filter explicit and show the amount
   outside that filter.
4. Distinguish accumulated share-gap recovery from the full future daily target in plan labels.
5. Label the employee plan value as plan-to-date pace and distinguish it from full-month
   completion.
6. Make attach-map colors relative to the store benchmark, or label fixed absolute bands with their
   numeric ranges.
7. Explain that the store attach benchmark includes non-ranking sales that are not rendered as
   employee columns.

## Priority 2 — data-quality and robustness work

1. Treat unexpected zero cost as incomplete/uncertain gross-profit data until confirmed.
2. Add a minimum sample policy for “leader by additional share” and “focus” claims.
3. Explain raw LiveSklad unit-count differences caused by analytically excluded zero-value work.

## Required regression coverage

Backend:

- return fixture with distinct original seller, return-document employee and cash-operation
  employee;
- explicit assertion for the selected employee-attribution rule across KPI, categories, attach-rate,
  rating and payroll;
- store-versus-ranking-participant attach benchmark test;
- unexpected-zero-cost completeness test;
- employee-category-to-store-category reconciliation test;
- no-shift rating and card quality-gate test.

Frontend:

- seller summary must identify its filtered scope and excluded amount;
- sales structure must not render overlapping groups as additive peers;
- monthly completion and plan-to-date pace must carry distinct labels;
- accumulated share gap and full future daily target must carry distinct labels;
- attach-map heat level must match the legend semantics;
- zero-cost uncertainty must not render as “Полные”.

Production acceptance after corrections:

1. repeat the LiveSklad reconciliation for a closed date range;
2. reconcile both store and employee totals under the selected return rule;
3. verify attach numerators and denominators, including excluded employees;
4. verify restored `МобиСфера` shifts, coverage, scores and ranks;
5. run the complete backend suite, frontend unit suite and local visual verification before release.
