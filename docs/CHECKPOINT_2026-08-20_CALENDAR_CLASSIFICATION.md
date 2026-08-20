# Checkpoint: calendar cutoff and Yandex Station classification

Date: 2026-08-20.

Production was not changed.

## Implemented locally

- Replaced the period selector with the new two-month range calendar.
- Added quick periods and mobile/desktop behavior.
- Current-month KPI queries now end at the earlier of:
  - the latest completed calendar day;
  - the store `dataThroughDate`.
- A current month with coverage through 2026-08-19 therefore uses
  `2026-08-01..2026-08-19`, matching the explicit custom range.
- Current partial-month labels show the effective cutoff date.
- Added an auto-classification rule for `Яндекс Станция` and `Yandex Station`:
  analytical category `PODS_WATCH_OTHER_DEVICE`, condition derived from the
  product name. Rule version is `livesklad-product-rules-v5`.

## Completed checks

- Focused frontend period/calendar run: 14 tests passed.
- Focused backend product-classification test passed in Java 21.
- `git diff --check` passed.

## Checks intentionally postponed

- Full backend check.
- Completed full frontend check and production build. The run was interrupted
  at the operator's request after contracts and lint had passed and the visible
  test output contained no failures.
- Required local visual review of the calendar. A previous attempt could not
  run because the local backend was not available.

## Future production data step

The rule fixes future sync and backfill classification. The already normalized
production item must be reclassified through the existing guarded
`ProductClassificationReconciliationService` workflow. Before deployment:

1. obtain the LiveSklad external product ID for the item in sale `B008123`;
2. confirm that exactly one active `UNMAPPED` normalized item is in scope;
3. run reconciliation with that ID and `expectedItemCount=1`;
4. verify that the open `UNMAPPED_PRODUCT` issue is resolved and category totals
   moved by one unit and 27,900 RUB without changing store revenue or gross profit;
5. disable the one-shot reconciliation flag after successful execution.
