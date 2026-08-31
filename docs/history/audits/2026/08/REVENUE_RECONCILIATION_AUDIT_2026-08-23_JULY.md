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
original_content_sha256: 2c13bf18ff03594efadd06a275d8f54664a51f809c5b8cf5b75b415818b931e1
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/product/README.md`.

# July revenue reconciliation audit — 2026-08-23

Status: `ACTION_REQUIRED`

The production backfill for `2026-07-01..2026-07-31` completed successfully,
but `МАГАЗИН` is not reconciled to the LiveSklad reports. The discrepancy is
fully explained by eight sale-return documents that are present in the reports
and absent from the synchronized API facts. `МобиСфера` reconciles exactly.

The audit was read-only. It did not start synchronization, process receipts,
or modify production data.

Follow-up on 2026-08-24: the eight-record recovery is still a separate pending production
operation. The temporary local script is intentionally excluded from Git. Use
[validated-return-recovery-runbook.md](../../../../archive/legacy-contracts/validated-return-recovery-runbook.md) and do not mark this
audit reconciled until the post-recovery differences are verified.

## Production state

- release: `v0.1.0-pilot.22`;
- schema: `V44`;
- backend API, backend worker and web containers: healthy;
- `LIVESKLAD_WEBHOOK_ENABLED=true`;
- `LIVESKLAD_WEBHOOK_WORKER_ENABLED=true`;
- `LIVESKLAD_ORDER_RETURN_WEBHOOK_WORKER_ENABLED=false`;
- scheduled synchronization: enabled;
- incremental overlap: three days.

The July backfill job finished in `SUCCESS` after 35 upstream retries. Child
failures were rate-limit failures and were subsequently recovered. A successful
job therefore proves orchestration completion, but it cannot prove equality
with a CRM report when the upstream period feed omits documents.

## Store reconciliation

| Store | Metric | CRM report | Production | Difference |
|---|---|---:|---:|---:|
| МАГАЗИН | Revenue | 64,840,862.00 | 65,557,612.00 | +716,750.00 |
| МАГАЗИН | Quantity | 2,692 | 2,734 | +42 |
| МАГАЗИН | Cost | 54,065,035.05 | 54,583,242.05 | +518,207.00 |
| МАГАЗИН | Gross profit | 10,775,826.95 | 10,974,369.95 | +198,543.00 |
| МобиСфера | Revenue | 15,680,200.00 | 15,680,200.00 | 0.00 |
| МобиСфера | Quantity | 523 | 523 | 0 |
| МобиСфера | Cost | 13,956,145.40 | 13,956,145.40 | 0.00 |
| МобиСфера | Gross profit | 1,724,054.60 | 1,724,054.60 | 0.00 |

For `МАГАЗИН`, sales and issued-order positions reconcile. Production contains
62 July sale-return documents for 3,089,289.00, while the CRM report contains
70 documents for 3,806,039.00. The eight-document difference is exactly the
store metric difference.

## Missing historical returns

Every missing return has zero paid amount in the sales report. This is the same
source-discovery pattern observed during the August reconciliation: the return
affects revenue and gross profit even though it has no refund cash movement.

| Document | LiveSklad ID | Amount | Cost | Gross-profit effect | Positions | Quantity |
|---|---|---:|---:|---:|---:|---:|
| F000321 | `6a4eac7e1859763ea8325d9f` | 79,880.00 | 68,700.00 | 11,180.00 | 3 | 3 |
| F000340 | `6a57ead8e861c2d49501db68` | 141,000.00 | 85,789.00 | 55,211.00 | 10 | 10 |
| F000342 | `6a593669c3093767e3e84554` | 102,890.00 | 90,999.00 | 11,891.00 | 3 | 3 |
| F000344 | `6a5938afc309371e11e84925` | 46,000.00 | 36,700.00 | 9,300.00 | 2 | 2 |
| F000352 | `6a5d2b39e861c25fc24d240d` | 4,990.00 | 1,590.00 | 3,400.00 | 1 | 1 |
| F000371 | `6a660193e861c22b80db7be7` | 103,000.00 | 68,025.00 | 34,975.00 | 9 | 9 |
| F000378 | `6a6c795fb75c90fffe3dea54` | 4,990.00 | 0.00 | 4,990.00 | 1 | 1 |
| F000380 | `6a6cf266aa17fa34a10d64fd` | 234,000.00 | 166,404.00 | 67,596.00 | 10 | 13 |
| **Total** | — | **716,750.00** | **518,207.00** | **198,543.00** | **39** | **42** |

Do not add monetary adjustment rows. The 24-character LiveSklad IDs were
obtained and recorded above. Submit the existing validated manual-recovery API
with the document number, amount and position count shown above. Each request
needs a distinct idempotency key. Completion requires all eight receipts to be
`PROCESSED` and all four production differences to become zero.

## Webhook verification

The `SALE_RETURN` path is working in production:

- 13 real `newSaleReturn` events and four previously validated manual
  recoveries are `PROCESSED`;
- every receipt has `processing_attempt_count=1` and `delivery_count=1`;
- every receipt has a scalar `source_document_id`;
- `payload_mismatch=false`, `terminal_failure=false`, and no error code;
- no sale-return receipt is waiting for processing.

The `ORDER_RETURN` canary is not complete. No order-return receipt has arrived,
and its dedicated worker remains disabled as designed. Do not enable it until
the first real receipt proves that `data.id` identifies the changed order and
is accepted by the order-detail API.

## Remaining data-quality limitations

- One synchronized July return in `МАГАЗИН` lacks a valid original-sale link.
  It does not explain the 716,750.00 store-level difference, but the link must
  be reviewed before treating return attribution as fully audited.
- Nine July item rows across five physical products have
  `ZERO_UNEXPECTED` cost. Revenue is unaffected, but gross-profit confidence
  remains limited until the source cost is confirmed.
- No July item is `UNMAPPED` and no July revenue is unassigned to an employee.

## Acceptance sequence

1. Queue eight validated manual recoveries using the recorded LiveSklad IDs.
2. Verify each recovery is `PROCESSED` exactly once.
3. Re-run the July reconciliation; expected differences are zero.
4. Review the single return with a missing original-sale link.
5. Review the five unexpected-zero-cost products.
6. Complete the first real `ORDER_RETURN` canary before enabling its worker.
