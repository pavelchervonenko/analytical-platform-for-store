# Revenue reconciliation audit — 2026-08-18

Status: source/API arithmetic confirmed; one configurable CRM-report difference remains to be
reconciled from the exported report rows before production calculations are changed.

## Scope

- Period: 2026-08-01 through 2026-08-16 inclusive.
- Reporting timezone: `Europe/Kaliningrad`.
- Stores: `МАГАЗИН` and `МобиСфера`.
- Customer comparison values:
  - `МАГАЗИН`: 43,302,474 RUB in CRM; 43,514,394 RUB in the application.
  - `МобиСфера`: 12,502,346 RUB in CRM; 12,453,346 RUB in the application.

The audit used read-only LiveSklad API requests and read-only production database transactions.
Temporary production sudo rules and runners were removed after the audit.

## Confirmed sales and return arithmetic

| Store | Sale documents | Sale amount | Return documents | Return amount | Current application net |
|---|---:|---:|---:|---:|---:|
| МАГАЗИН | 711 | 44,481,114 | 26 | 966,720 | 43,514,394 |
| МобиСфера | 252 | 12,731,496 | 9 | 278,150 | 12,453,346 |

For both stores:

- the LiveSklad API net amount exactly equals the application amount;
- sale header amount, active item total, and cash total reconcile;
- return cash transaction, return detail positions, and return detail payments reconcile;
- no target-period document or item is deleted in the application;
- no target-period item is excluded through the `EXCLUDE` analytics category;
- there are no sale/item amount mismatches;
- there are no duplicate source IDs;
- the returned list-price discount difference is not the discrepancy source.

## Hypotheses ruled out

### Reporting timezone

UTC, `Europe/Moscow`, and `Europe/Kaliningrad` produce identical target-period totals. No document
crosses the relevant period boundary between Moscow and Kaliningrad.

### Unlinked returns

The 14 open `RETURN_ORIGINAL_DOCUMENT_MISSING` issues all belong to May, June, or July. Their target
period count and amount are both zero.

### Retroactive amount changes

Eight target-period sale entities have multiple retained raw versions, but none changed its source
amount. Target-period returns have no multiple raw versions.

### Employee ranking participation

Ranking participation is not the CRM revenue filter. In particular, `МобиСфера` CRM revenue includes
the 262,698 RUB of sales attributed to employees outside the rating group. Store revenue must remain
independent of the `participates_in_ranking` flag.

### Return list price versus actual sold price

The total difference between list price and actual sold price on returns is 17,350 RUB for
`МАГАЗИН` and 2,490 RUB for `МобиСфера`; neither explains the reported differences.

## Confirmed missing source: orders

The current application does not synchronize LiveSklad orders or order positions. It synchronizes
only `/sales` plus sale-return documents.

LiveSklad returned the following order-position amounts for the target period, using each position's
own `date` field:

| Store | Issued orders | Work positions | Position amount | Order cash transactions |
|---|---:|---:|---:|---:|
| МАГАЗИН | 5 | 5 | 40,500 | 40,500 |
| МобиСфера | 2 | 2 | 49,000 | 49,000 |

For `МобиСфера`, this explains the discrepancy exactly:

```text
12,453,346 application sales net
+   49,000 issued order work positions
=12,502,346 CRM revenue
```

The official LiveSklad report documentation confirms that the configurable detailed goods-and-works
report combines store-sale positions and order positions. Store-sale positions use the sale date;
order positions use the date the position was actually added to the order:

https://help.livesklad.com/ru/articles/292-%D0%BF%D0%BE%D0%B4%D1%80%D0%BE%D0%B1%D0%BD%D1%8B%D0%B9-%D0%BE%D1%82%D1%87%D1%91%D1%82-%D0%BF%D0%BE-%D1%80%D0%B0%D0%B1%D0%BE%D1%82%D0%B0%D0%BC-%D0%B8-%D1%82%D0%BE%D0%B2%D0%B0%D1%80%D0%B0%D0%BC

## Remaining `МАГАЗИН` difference

Adding all target-period order positions to the application's current value gives:

```text
43,514,394 current application sales net
+   40,500 target-period order positions
=43,554,894 complete public-API position candidate
-43,302,474 CRM report
=  252,420 remaining difference
```

The public API exposes the underlying sales, returns, orders, positions, and cash transactions, but
does not expose the selected configuration of the fully customizable CRM report. The remaining
252,420 RUB therefore cannot be assigned safely to a business rule from the headline total alone.
Changing application arithmetic to force that number would be speculative and could break other
periods.

## Required evidence for row-level reconciliation

Export the exact `Подробный отчёт по работам и товарам` used to obtain 43,302,474 RUB:

1. Period 2026-08-01 through 2026-08-16 inclusive.
2. Store `МАГАЗИН`.
3. Operation-list format, without grouping rows together if the UI permits it.
4. The same employee, document, order-status, directory, and other filters used for the quoted CRM
   number.
5. A screenshot of the complete report filter panel or the saved report configuration.

The export should retain document/date, employee, item/work name, quantity, actual amount, and
return columns. Customer contact data is not needed.

## Implementation sequence after receiving the export

1. Reconcile every exported row to a sale, sale return, order position, or documented exclusion.
2. Record the exact canonical inclusion, date, employee-attribution, and return rules.
3. Add order synchronization with retained raw versions and correction overlap.
4. Normalize order positions by their own business date and responsible employee.
5. Extend the shared KPI fact projection so store KPI, employee KPI, categories, plans, payroll,
   reports, and AI snapshots all use the same facts.
6. Add invariant tests: source totals, store/employee roll-up equality, return subtraction, order
   position dates, idempotency, retroactive correction, and historical backfill.
7. Reconcile June, July, and August before preparing a production release.
