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
original_content_sha256: ac8b722272e29352499902988fa373012d5f1ca6e33c218de9c8885ec4005f63
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/product/README.md`.

# Revenue reconciliation audit — 2026-08-18

Status: issued-order-position synchronization is implemented and verified locally. Production is
unchanged until the release is deployed and a historical backfill completes. One upstream
sale-return discovery gap remains explicit and must not be hidden by a manual adjustment.

## Row-level export resolution

The customer export `Отчет по товарам и работам-38.xlsx` for 2026-08-01 through 2026-08-09 made the
remaining source difference reproducible:

```text
22,250,512.50 application/API sales minus API-discoverable returns
+   24,500.00 issued order works A000605 and A000642
-   15,030.00 report return F000381 absent from the cash-return API feed
=22,259,982.50 exported detailed report
```

The application already matched the public sales and cash-return REST endpoints exactly. The
24,500 RUB was not a formula defect: the application did not ingest the issued-order-position
source that LiveSklad's detailed goods-and-works report combines with store sales.

## Implemented correction

Flyway V40 and synchronization phase `ORDERS` now normalize issued order positions into the shared
sales fact projection. This automatically feeds store and employee KPI, categories, plans, payroll,
reports, and AI snapshots without a second calculation path. The implementation is idempotent,
captures later edits and cancellation, respects the position business date and responsible employee,
and prevents the SALES/RETURNS phases from deleting order facts.

The reconciliation fixtures cover the two confirmed rows:

- A000605: 18,500 RUB, 2026-08-05, responsible employee from the position;
- A000642: 6,000 RUB, 2026-08-06, responsible employee from the position.

## Remaining upstream return limitation

F000381 for 15,030 RUB is present in the XLSX detailed report but is not returned by the configured
cash-register transaction endpoints, including the observed deleted transaction shapes. LiveSklad's
documented REST API exposes `/documents/{id}`, but no collection endpoint that can enumerate all
sale-return documents. Without an ID, polling cannot discover this row safely.

Do not add a hard-coded compensating fact. The production-safe resolution is to obtain from
LiveSklad either a supported return-list endpoint or a return webhook that includes the document ID,
then feed that ID through the existing return-detail normalization. Until then, this upstream
coverage limitation remains explicit and detailed-report equality cannot be guaranteed for rows the
API does not publish.

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

## Pre-export `МАГАЗИН` checkpoint

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

## Evidence used for row-level reconciliation

The supplied operation-level export for 2026-08-01 through 2026-08-09 retained document/date,
employee, item/work name, quantity, actual amount, and return rows. It was sufficient to identify
A000605, A000642, and F000381 without using any customer contact data.

## Production completion sequence

1. Deploy backend and frontend together because the public contract advances from v9 to v10.
2. Run one historical backfill from 2026-01-01 through the latest completed business day; no manual
   recurring imports are required after that.
3. Confirm every child window completes SALES, RETURNS, and ORDERS and that store freshness is
   `CURRENT` only after all three sources cover the day.
4. Re-run row-level reconciliation for June, July, and August, including separate totals for `sale`,
   `saleReturn`, and `orderPosition`.
5. Verify that A000605 and A000642 add exactly 24,500 RUB for 2026-08-01 through 2026-08-09.
6. Keep F000381 as an explicit upstream API gap until LiveSklad supplies a supported discovery path;
   do not force equality with a manual monetary correction.
