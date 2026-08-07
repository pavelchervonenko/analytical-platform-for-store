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
incremental enqueuer skips creation and writes a warning. This is a bootstrap safety barrier, not a
promise that every future LiveSklad product is classified: genuinely new products remain visible
as UNMAPPED.

If facts were synchronized before the initial import, importing assignments does not silently
rewrite historical snapshots. Run the same durable backfill again after import; normal source
reconciliation updates those items using the now-effective assignments. Direct SQL
reclassification is not an approved operational path. The one-time V32 migration is a reviewed
repair of the erroneous initial CARE mapping, not a general reclassification interface.

The endpoint is authenticated and CSRF-protected by the common Spring Security configuration.
