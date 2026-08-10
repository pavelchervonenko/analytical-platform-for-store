# Product category import API

Status: implemented ADMIN-only integration endpoint, revalidated on 2026-08-04. It is an
administrative bootstrap action, not a manager-facing upload screen; see `docs/frontend-actions.md`.

The initial customer-approved product classification is imported through:

```text
POST /api/integration-connections/{connectionKey}/product-category-imports
Content-Type: application/json
```

The prepared payload is `outputs/category-review-approved/product-category-assignments-v2.json`.
Its active rule version is `customer-approved-2026-08-07-v2`; `validFrom` is
`2025-12-31T22:00:00Z`, which is `2026-01-01 00:00` in the reporting time zone
`Europe/Kaliningrad`. Import the classification before the historical sales backfill so each sale
item captures the approved category snapshot during normalization.

Example request body:

```json
{
  "validFrom": "2025-12-31T22:00:00Z",
  "ruleVersion": "customer-approved-2026-07-20-v1",
  "changeReason": "Initial customer-approved classification",
  "assignments": [
    {
      "externalProductId": "4310",
      "productName": "Cable",
      "categoryCode": "CHARGER_CABLE",
      "conditionType": "NOT_APPLICABLE"
    }
  ]
}
```

Successful response:

```json
{
  "requested": 1,
  "productsCreated": 1,
  "assignmentsCreated": 1,
  "assignmentsUnchanged": 0
}
```

Behavior and invariants:

- the connection must exist, be active, and use `LIVESKLAD`;
- category codes must exist and be active;
- `UNMAPPED` is represented by no assignment and cannot be imported explicitly;
- duplicate `externalProductId` values in one request are rejected;
- missing products are created as minimal LiveSklad identities and enriched by later sales sync;
- the entire batch runs in one transaction;
- repeating the same import is idempotent;
- an existing different category history causes a conflict and rolls back the whole batch;
- category snapshots already stored on sale items are never rewritten by this import.


## Bootstrap safety and recovery

The admin screen accepts the complete approved artifact as a JSON file, validates it locally and
fills the effective date, rule version and change reason from its metadata.

Before a backfill, frontend reads GET /api/sync/jobs/backfill-readiness with periodStart. A ready
response requires at least one approved assignment effective at the reporting-zone start of that
date. The response also contains effective and total assignment counts, product count and the
number of active sale items already normalized as UNMAPPED.

Manual backfill returns 409 SYNC_CLASSIFICATION_REQUIRED when readiness is false. The scheduled
incremental enqueuer skips creation and writes a warning. This is a bootstrap safety barrier. After bootstrap, classification uses two ordered layers:
an effective customer-approved assignment by exact LiveSklad product identity first, then the
versioned high-confidence rule set `livesklad-product-rules-v1`. A rule result is stored directly in
the sale or return snapshot with its rule version. Ambiguous names are never forced into a fallback
category and remain visible as `UNMAPPED` for review.

If facts were synchronized before an approved rule release, ordinary assignment import still does
not silently rewrite historical snapshots. A reviewed one-time reconciliation may update only an
explicit allowlist of product external IDs and only when the observed active `UNMAPPED` item count
and the complete distinct-ID set exactly match the approved dry-run. Any mismatch or unresolved
product rolls back the transaction. The feature is disabled by default and must be disabled again
immediately after the accepted run. This avoids a provider reload and does not change sales, return,
quantity or monetary facts; only category/condition snapshots and their matching open data-quality
issues change. Direct SQL reclassification remains an unapproved operational path. The one-time V32
migration remains a reviewed repair of the erroneous initial CARE mapping, not a general interface.

The endpoint is authenticated and CSRF-protected by the common Spring Security configuration.
