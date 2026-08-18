# LiveSklad API

Status on 2026-08-18: sanitized upstream discovery and implemented synchronization reference.
It is not a frontend contract and must not be exposed in the browser. Discovery is complete for the implemented store/employee/
sales/return/issued-order-position sync; remaining gaps below are explicit upstream limitations.

This is the source of truth for the LiveSklad contract discovered for this project. It contains only
read-only API behavior and sanitized structures. Never add credentials, tokens, or customer data.

## Discovery rules

- Run scripts manually from a trusted Bash terminal.
- Agents and documentation reviews must never inspect or print `.env`; use only already configured
  secret injection when an explicitly authorized operator runs discovery.
- `.env` is parsed as UTF-8 dotenv data and is never sourced as shell code. Only
  `LIVESKLAD_BASE_URL`, `LIVESKLAD_LOGIN` and `LIVESKLAD_PASSWORD` are imported; invalid syntax
  and duplicate keys fail closed. Shell expansion and command substitution remain literal data.
- Never use `set -x` while credentials or tokens are loaded.
- Never commit raw production responses.
- Discovery requires an origin-only HTTPS base URL with no userinfo, path, query or fragment.
- Each upstream response is capped at 1 MiB by default and may be configured only up to 8 MiB with
  `DISCOVERY_MAX_RESPONSE_BYTES`.
- Record field names, JSON types, nullability, IDs, relations, limits, and business meaning.
- Use only `GET` requests after authentication.
- Watch `remainRequest`: the current limit is 100 requests per 15-minute window.
- Add sanitized fixtures later under `backend/src/test/resources/fixtures/livesklad`.

## Prerequisites

- Bash (WSL/Linux/macOS, not PowerShell)
- `curl`
- `jq`
- Python 3 (standard library only)
- Explicitly configured local secret injection; credentials are intentionally omitted here.

## Scripts

| Script | Purpose | Safe output |
|---|---|---|
| `scripts/livesklad-discovery/01-auth.sh` | Validate auth and profile metadata | Status, TTL, limits, field names |
| `scripts/livesklad-discovery/02-shops.sh` | Profile the stores collection | Counts, field types, nullability, ID integrity |
| `scripts/livesklad-discovery/03-employees.sh` | Profile employees and roles for every store | Counts, schemas, role subsets, cross-store ID overlap |
| `scripts/livesklad-discovery/04-sales-list.sh` | Probe recent sales lists for every store | Document types and sanitized field schemas |
| `scripts/livesklad-discovery/05-sales-period.sh` | Profile all sales in a short period | Completeness, optional fields, financial invariants, payment flags |
| `scripts/livesklad-discovery/06-sale-details.sh` | Profile representative sale details | Relations, positions, batches, returns, services, serial items |
| `scripts/livesklad-discovery/07-sale-edge-search.sh` | Search recent details for rare position cases | Services, returns, quantity, cash elements, batch checks |

```bash
bash scripts/livesklad-discovery/01-auth.sh
bash scripts/livesklad-discovery/02-shops.sh
bash scripts/livesklad-discovery/03-employees.sh
bash scripts/livesklad-discovery/04-sales-list.sh
bash scripts/livesklad-discovery/05-sales-period.sh
bash scripts/livesklad-discovery/06-sale-details.sh
bash scripts/livesklad-discovery/07-sale-edge-search.sh
```

## Common response envelope

| Field | Meaning | Persistence |
|---|---|---|
| `data` | Resource payload or collection | Raw payload plus selected normalized fields |
| `total` | Records matching filters | Sync telemetry only |
| `page` | Current page | Sync telemetry only |
| `pageSize` | Applied page size | Sync telemetry only |
| `remainRequest` | Requests left in the limit window | Logs and metrics |
| `expireDate` | Limit-window reset timestamp | Logs and metrics |

## 01. Authorization

| Property | Value |
|---|---|
| Method | `POST` |
| Path | `/auth` |
| Content type | `application/x-www-form-urlencoded` |
| Request fields | `login`, `password` |
| Token header | `Authorization: <token>`, without `Bearer` |
| Observed TTL | 900 seconds |

| Response field | Type | Meaning | Sensitive |
|---|---|---|---|
| `token` | string | Short-lived access token | Yes |
| `ttl` | number | Token lifetime in seconds | No |
| `remainRequest` | number | Requests remaining | No |
| `expireDate` | string | Limit reset in ISO-8601 | No |

Confirmed on 2026-07-10 and revalidated on 2026-07-12: authentication succeeded, TTL was 900, and auth returned
`remainRequest: 99`. A production client must cache the token in memory, refresh it before expiry,
and explicitly handle HTTP 401, 403, and 429 without logging secrets.

## 02. Stores

| Property | Value |
|---|---|
| Method | `GET` |
| Path | `/shops` |
| Authentication | `Authorization: <token>` |
| Sensitive fields | `name`, `address` (not printed by the script) |

Confirmed on 2026-07-12:

- The account exposes 2 stores.
- Response fields are `data`, `remainRequest`, and `expireDate`.
- `version` is absent in the real response even though some public examples contain it.
- Every observed store has non-null string fields `id`, `name`, `address`, and `color`.
- Both IDs are present and unique.
- The rate-limit counter is shared with other calls in the same window; a script does not start a new limit window.

Database implications:

- `id` maps to `stores.external_id`.
- `name` and `address` are normalized store attributes.
- `color` is optional UI/source metadata.
- LiveSklad does not provide the store timezone here; our configured timezone remains authoritative.

## 03. Employees and roles

Confirmed on 2026-07-12:

- Store 1 exposes 12 employees and store 2 exposes 10.
- The company has 12 unique employee IDs; 10 IDs occur in both stores.
- All 10 employees returned for store 2 also occur in store 1; 2 employees occur only in store 1.
- Every observed employee has non-null string fields `id` and `name`.
- IDs are unique within each store and stable across stores.
- The endpoints do not report `total`, `page`, or `pageSize`; pagination must continue until a short page.
- Managers and masters returned exactly the same ID sets as all employees in this account.
- The rate-limit window reset during the script, so `remainRequest` increased and `expireDate` changed.

Database implications:

- Employee identity is company-wide: use one employee row per LiveSklad employee ID.
- Store membership is many-to-many: use an `employee_store_assignments` relation.
- Do not duplicate an employee row for every store.
- Do not infer a reliable role from the managers/masters endpoints for this account.
- Ranking eligibility and business roles should remain configurable in our application.

## 04. Recent sales list

Confirmed on 2026-07-12:

- The documented `date=[fromUnixMs,toUnixMs]` filter works.
- The 7-day window contained 252 sales for store 1 and 42 for store 2.
- Store 1 requires pagination; store 2 fits on one page of 50.
- Response fields are `data`, `total`, `page`, `pageSize`, `sort`, `summ`, `remainRequest`, and `expireDate`.
- Every first-page document has string `id`, `number`, `date`, and `type`, plus object `summ` and `cash`.
- Only `type=sale` was observed on the first page of each store.
- `counteragent` is omitted on some documents and cannot be required.
- `node` is sparse: observed on 4 of 50 documents in store 1 and absent from store 2's first page.
- `summ` consistently contains numeric `price`, `soldPrice`, and `purchasePrice`.
- `cash` consistently contains numeric `summ` and boolean `isBank`, `isMoney`.
- Response-level `summ` contains numeric aggregate `cash`, `soldPrice`, and `purchasePrice`.
- Counteragent objects contain `id`, `name`, and numeric `rating`; this is personal data and is not needed for MVP KPIs.

Database implications:

- Sale list fields map naturally to the document header, but employee attribution still requires document detail.
- Counterparty data must remain optional and should not be normalized unless a use case requires it.
- Response-level `summ` is query metadata, not a sale entity.
- `node` should remain optional metadata unless the customer identifies a business use.
- Monetary semantics still require checks for discounts, zero costs, and payment mismatches.

## 05. Complete short-period sales profile

Confirmed on 2026-07-12:

- All reported records were fetched: 251 from store 1 and 42 from store 2.
- The one-document difference from the earlier 252 count is caused by moving 7-day boundaries between runs.
- Store 1 required 6 pages and store 2 required 1 page.
- All 293 IDs were present and unique, with no cross-store overlap.
- Every document in the period had `type=sale`; returns were not exposed as negative or alternate list types.
- `counteragent` was omitted on 14 documents; `node` appeared on 24.
- 74 documents had `price != soldPrice`, confirming real discounts.
- 13 documents had zero `purchasePrice`; zero cost cannot automatically be treated as reliable cost data.
- No negative amounts were observed.
- `cash.summ` matched `summ.soldPrice` for all 293 documents.
- Payment flags are always present booleans: 197 cash-only, 40 bank-only, and 56 mixed-payment documents.
- The original profiler displayed `false` as `null` because of jq's `//` semantics; the script has been corrected.

Database and sync implications:

- Use fixed period boundaries for reproducible synchronization and reporting.
- Keep a rolling overlap when re-syncing recent sales so late corrections are captured.
- Observed sale IDs are globally unique, but a defensive unique key may still include source and store.
- Store list price, sold price, and cost separately; calculate discount explicitly.
- Treat zero purchase price as a data-quality state until its business meaning is confirmed.
- Payment method can be cash, bank, or mixed; a single binary payment type is insufficient.

## 06. Representative sale details

Confirmed on 2026-07-12 from 12 representative documents and 21 positions:

- `customer.id` matched a visible employee in all 12 documents.
- `shop.id` matched the requested store in all 12 documents.
- Document `customer` is the responsible employee and contains `id`, `name`, and `shortName`.
- Document `counteragent` is optional; `howKnow` and `node` are also optional.
- Detail responses do not contain list-level `summ`; both list and detail payloads are required.
- Detail `cash` contains numeric `money`, `bank`, `invoice`, plus an `elements` array.
- Every document has a `positions` array; sampled counts ranged from 1 to 5 positions.
- Every sampled position has unique `positionId` and required `nomenclatureId`.
- 21 positions referenced 18 unique nomenclature IDs, confirming reuse across documents.
- Position price fields are `price`, `soldPrice`, and `purchasePriceSumm`.
- `isWork` is required but all sampled values were false; service semantics remain unconfirmed.
- `isSerial` and `guaranteeInDay` are omitted when not applicable.
- `returnCount` is required but was zero in every sampled position.
- Each sampled position had a `batches` array and a structured `measure` object.
- Batch fields are `batchId`, `storeId`, `count`, `purchasePrice`, `returnCount`; `sn` is optional.
- Serial numbers occurred only on serial positions.
- No sampled position had quantity greater than one.
- 3 positions were discounted and 8 had zero purchase cost.

Database implications:

- `sales_documents.employee_id` maps from detail `customer.id`.
- `sales_documents.store_id` maps from detail `shop.id`.
- `sales_document_items.external_id` maps from `positionId`.
- Product identity starts with `nomenclatureId`; a full catalog lookup is still missing.
- Add normalized return count and work/serial markers to sale items.
- Keep batch and serial data in raw JSON initially unless a concrete report requires normalization.
- Preserve both list and detail raw payloads because neither response is a complete projection alone.

## 07. Rare sale-detail edge search

Confirmed on 2026-07-12 from 30 documents and 56 positions:

- The scan reached its configured limit; the rate-limit guard did not activate.
- No `isWork=true`, positive `returnCount`, or position `count>1` was observed.
- Guarantees occurred in 26 of 30 documents and serial items in 22 of 30.
- No position contained more than one batch.
- Every one of the 56 positions had exactly one batch.
- `purchasePriceSumm` matched the batch extended cost for all 56 positions.
- `cash.elements` contained 33 entries with required `id`, `date`, `type`, `money`, and
  `isBankTransfer` fields.
- Every observed cash element had `type=sale`.
- The last response reported 62 requests remaining, so the scan stayed within the request budget.

Database implications:

- Keep position quantity even though only single-unit positions have been observed.
- Keep `returnCount` and `isWork`; absence in a small recent sample does not prove they are unused.
- Store batches and serial numbers in raw detail JSON for MVP; normalize them only for a concrete report.
- Do not model `cash.elements` as one payment per document: a document can contain multiple elements.
- A normalized payment-component table can be added when cash-flow or payment-method reporting is required.

The discovery script can be rerun with a different period or limit when targeted historical examples
are known:

```bash
bash scripts/livesklad-discovery/07-sale-edge-search.sh
```

The script uses a 30-day candidate window, reserves five requests as a rate-limit guard, and never
prints IDs, names, numbers, dates, serial numbers, or monetary values.

## 08. Orders overview

Confirmed on 2026-07-12:

- The 30-day period contains 92 orders; the first page contains 50.
- The account exposes 4 order types with unique string IDs and names.
- Statuses are grouped under `data.new`, `data.inWork`, `data.wait`, `data.finish`, and
  `data.closed`; each group contains an `elements` array.
- The account currently exposes one status in each group, for 5 unique statuses total.
- Status groups contain `color`, `description`, and `elements`.
- Statuses contain `id`, `name`, `color`, `comment`, `sort`, `isPayRequired`, and `roles`.
- Each status has role-permission objects with `id`, `name`, `isSet`, `isChange`, and
  `isChecked`.
- Every sampled order has `id`, `number`, `dateCreate`, `device`, `cash`, `summ`,
  `counteragent`, `shop`, `status`, `typeOrder`, `isVisible`, and `isUrgent`.
- `manager` occurs on only 7 of 50 orders; `master` and `closeManager` were absent.
- `problem`, `sn`, `typeDevice`, and `approximatePrice` are optional.
- All 50 sampled orders are visible and non-urgent.
- Order `summ` contains `price` and `soldPrice`; order `cash` contains `summ`.
- Counteragent phone arrays occur on 7 of 50 orders and are personal data that is not needed for MVP
  analytics.

Database implications:

- Model order type and order status as source-backed dictionaries with stable external IDs.
- Preserve the LiveSklad status group key separately from the status ID.
- Do not hard-code the five observed group keys as a Java enum; source configuration can change.
- Keep status role permissions in raw JSON unless application authorization explicitly needs them.
- The customer-confirmed reporting rule attributes an order position to its own `customer` relation;
  creator, manager, close manager, and master remain audit-only workflow roles.
- Counteragent names, phones, device serial numbers, and problem descriptions should remain outside
  normalized MVP analytics unless a defined use case requires them.
- Full synchronization must paginate and use bounded date windows because one page covers only 50 of
  92 recent orders.

```bash
bash scripts/livesklad-discovery/08-orders-overview.sh
```

References:

- https://developer.livesklad.com/api/order
- https://developer.livesklad.com/api/status

## 09. Representative order details and custom fields

Confirmed on 2026-07-12 from 7 representative details:

- Detail IDs and status, order-type, and shop relations matched the list response in all 7 cases.
- Required detail fields include `id`, `num`, `number`, `dateCreate`, `lastAction`,
  `positions`, `cash`, `counteragent`, `createManager`, `shop`, `status`, `typeOrder`,
  `isVisible`, and `isUrgent`.
- `manager` occurred in 4 details and `closeManager` in 1; `master` was absent.
- `createManager` occurred in every detail and is distinct from the optional workflow employee roles.
- Detail-level `summ` was absent even though list-level `summ` is always present.
- `lastAction` is always present and is the best observed candidate for incremental order sync.
- Optional device and repair fields include brand, model, type, serial number, appearance, problem,
  complete set, notes, approximate price, finish/close dates, source, and files.
- All details contain a `positions` array, but only 1 position occurred across the 7 sampled orders.
- The observed order position uses the same product identity and price concepts as sale positions:
  `positionId`, `nomenclatureId`, quantity, list/sold/cost prices, measure, guarantee, and `isWork`.
- Order cash contains `order`, `invoice`, `orderReturn`, and an `elements` array.
- One of four order types defines 2 custom fields; the other three define none.
- Custom-field definitions include ID, data type, required flag, mark, description, default value,
  and optional items. Detail values are represented as `id` plus `value`.
- Counteragent detail contains channel flags for email, SMS, Telegram, and MAX, but these are
  communication/PII concerns outside MVP store analytics.
- API request budget remained healthy after the probe.

Database and sync implications:

- Preserve both list and detail raw payloads; neither projection is complete by itself.
- Use `lastAction` with an overlap window for incremental order synchronization, subject to a later
  retroactive-update test.
- Model order employee roles separately; do not collapse creator, manager, close manager, master, and
  position employee into one column.
- Keep repair descriptions, device serial numbers, files, and counteragent contact data out of
  normalized MVP tables.
- Store custom-field definitions as source metadata and values as raw JSON initially; normalize only
  fields selected for reporting.
- Order positions may reuse the product dimension built from sale-position `nomenclatureId` values.

Implementation update on 2026-08-18:

- `GET /company/orders?lastAction=[fromUnixMs,toUnixMs]&page=N&pageSize=50` was revalidated against
  the production account. Adding the apparently plausible `sort=dateCreate ASC` parameter returns
  HTTP 400, so production synchronization deliberately does not send it.
- `GET /orders/{id}` supplies the authoritative latest status, visibility, close date, shop,
  position date, position employee, quantity, sold/list price, and cost.
- Only visible orders whose list and detail status is `Выдан` and whose detail has `dateClose` are
  normalized. A later status, visibility, or position change updates or soft-deletes the facts
  idempotently.
- Every issued position is stored as one synthetic sale fact with source type `orderPosition`,
  business date from position `date`, employee from position `customer`, and external ID
  `order:{orderId}:position:{positionId}`.
- Positions use the same effective product classification as ordinary sale items. A previously unseen
  `isWork=true` product receives the existing automatic service fallback.
- Incremental and backfill jobs run `ORDERS` after `RETURNS`, limit a window to 70 order details,
  halve oversized or rate-limited windows, retain sanitized raw evidence, and include orders in
  freshness, retention protection, and daily-pulse readiness.
- SALES and RETURNS reconciliation is source-type scoped, so those phases cannot soft-delete
  synthetic order-position facts.

```bash
bash scripts/livesklad-discovery/09-order-details.sh
```

## 10. Cash registers and transactions

Confirmed on 2026-07-12:

- The account exposes 20 unique cash-item definitions with `id`, `name`, `isIncome`,
  `isBalance`, and optional `type`.
- Two stores expose 4 unique cash registers; every register's `shopId` matched the requested store.
- Register responses expose cash and bank balances. These values are sensitive operational data and
  are not needed as normalized historical facts for MVP.
- The 30-day totals by register are 1497, 0, 124, and 65 transactions, for 1686 total.
- Only the first 50 records per register were sampled, producing 150 unique transactions.
- Every sampled transaction has employee-like `customer`, register, cash item, date, ID, amount,
  balance marker, bank-transfer marker, running remainder, shop ID, and type.
- Counteragent is optional (83 of 150); `document` occurs on 94, internal movement register on 16,
  note on 25, and `worker` on 5.
- All 150 sampled `money` values are positive. A negative amount is therefore not the source
  convention for identifying outflow or returns.
- The sample contains 106 cash and 44 bank-transfer operations.
- `dateChange` was absent from every sampled transaction.
- `customer.id` is always present, but its relationship to known employees still needs a direct
  identity check.

Database and sync implications:

- Determine transaction direction from the cash-item dictionary and transaction semantics, especially
  `cashItem.isIncome`, `isBalance`, `type`, and `document`; do not infer it from amount sign.
- A full 30-day profile requires pagination because the first pages cover only 150 of 1686 records.
- Use source transaction ID for idempotent upsert and re-read a rolling date overlap because no
  update timestamp was observed.
- Keep register balances out of the transaction fact table and avoid logging their values.
- Cash transactions are candidates for reconciliation and return detection, not the primary source
  of sales revenue.
- Counteragent data remains optional and unnecessary for MVP metrics.

```bash
bash scripts/livesklad-discovery/10-cash-overview.sh
```

## 11. Complete cash-period semantics

Confirmed on 2026-07-12:

- All reported records were fetched from all four registers: 1499, 0, 124, and 65, for 1688 total.
- The two-record difference from the earlier 1686 total is consistent with a moving 30-day boundary
  and/or live writes between runs.
- All 1688 transaction IDs are present and unique.
- Every transaction references a known cash item and store.
- Every `customer.id` matched a known employee ID.
- `worker` occurred on 80 transactions and also matched a known employee every time.
- Direction is complete: 1319 income and 369 outflow transactions, with no unknown classification.
- All amounts remain positive, including every outflow and return operation.
- Transaction categories observed: 1225 sales, 46 sale-return records, 90 purchases, 49 generic
  income operations, 182 consumption operations, 9 collections, 42 moves, 42 matching move-from
  operations, and 3 order payments.
- Of the 46 sale-return records, 45 have `transactionType=saleReturn` and one has
  `transactionType=delete`; the deleted record must not affect active totals.
- Internal moves occur as paired `move` and `moveFrom` records and must be excluded from revenue.
- Cash transaction `document` occurs on 1361 records and contains only ID and number in this
  projection.
- `dateChange` occurs on only 2 records and is not a dependable universal update cursor.
- The profiler originally rendered boolean false as null inside semantic combinations due to jq
  `//` semantics. Direction counts were unaffected, and the script has been corrected.

Database and metric implications:

- Store the positive source amount and derive signed amount from the source classification:
  income is positive, outflow is negative.
- Preserve original transaction type, cash-item ID, income flag, balance flag, bank flag, and
  document relation so calculations remain auditable.
- Treat `saleReturn` as the return signal; exclude `delete`, paired internal moves, collections,
  purchases, and generic cash operations from sales revenue.
- Use transaction ID for idempotency and a rolling date overlap for corrections and deletions.
- Store both employee roles separately: `customer` is the responsible employee and optional
  `worker` is another operation actor.
- Cash transactions can reconcile sales and locate returns, but sale documents and positions remain
  the authoritative source for item-level revenue, cost, margin, and employee KPI.
- Return document details must be inspected next to recover returned positions and cost impact.

```bash
bash scripts/livesklad-discovery/11-cash-period.sh
```

## 12. Return document details

Confirmed on 2026-07-12:

- The bounded period contained 45 sale-return cash records: 44 active and 1 deleted.
- All 45 records were fetched, contained a document relation, used positive amounts, and referenced
  known employees.
- The one-operation difference from the previous complete profile is caused by the moving 30-day
  boundary between runs.
- Two representative active documents were fetched, covering cash and bank returns.
- Both details have `type=saleReturn` and their IDs match the cash transaction document relation.
- A return detail has its own `documentId`, a `parentDocument` relation to the original sale, and
  a positions array.
- Every sampled return position has `salePositionId`, which links directly to the original sale
  position, plus `nomenclatureId`, returned `count`, sold/list prices, and purchase cost.
- Return positions and batches do not use positive `returnCount`; returned quantity is represented
  by the return position's own `count`.
- `minPrice` was null on both sampled return positions.
- Return-document employee did not match the cash-transaction employee in either sample. These are
  different operational roles and cannot be substituted for one another.

Database and metric implications:

- Model returns as separate documents and positions, linked to the original sale document through
  `parentDocument` and to the original item through `salePositionId`.
- Use return position `count`, `soldPrice`, and `purchasePriceSumm` to reverse quantity, revenue,
  cost, and margin.
- Keep both the return-processing employee and cash-operation employee for audit, but attribute KPI
  reversal to the original sale employee unless the customer defines a different rule.
- Exclude deleted return transactions from active metrics while retaining their raw/audit state.
- The API discovery required for the MVP sales, KPI, employee, return, and synchronization model is
  now complete.

```bash
bash scripts/livesklad-discovery/12-return-details.sh
```

## 13. Product category bootstrap sample

LiveSklad exposes product identity through sale positions but no complete analytics-category catalog
was found. The bootstrap script samples up to 60 recent sale details and groups real product names
by stable `nomenclatureId`.

```bash
bash scripts/livesklad-discovery/13-product-category-sample.sh
```

The result intentionally includes product names and source product IDs so the customer can classify
them. It excludes buyers, employees, receipt numbers, serial numbers, and monetary values. This is a
representative seed list, not a complete catalog; production must keep an explicit unmapped-product
state and allow later category assignment.

## Endpoint inventory

Statuses: `TODO`, `IN_PROGRESS`, `CONFIRMED`, `NO_ACCESS`, `NOT_NEEDED`.

| Status | Method | Endpoint | Resource | Questions |
|---|---|---|---|---|
| `CONFIRMED` | POST | `/auth` | Authentication | TTL, limits, errors |
| `CONFIRMED` | GET | `/shops` | Stores | Two stores; required string fields; unique IDs |
| `CONFIRMED` | GET | `/shops/{id}/customers` | Employees | Global IDs with many-to-many store membership |
| `CONFIRMED` | GET | `/shops/{id}/customers/managers` | Managers | Same set as employees in this account |
| `CONFIRMED` | GET | `/shops/{id}/customers/masters` | Masters | Same set as employees in this account |
| `CONFIRMED` | GET | `/shops/{id}/sales` | Sales | Date filter, pagination, prices, payment flags, optional counteragent |
| `CONFIRMED` | GET | `/documents/{id}` | Sale/return detail | Parent sale and original position links confirmed |
| `CONFIRMED` | GET | `/company/orders` | Orders | Pagination, dates, optional employees, status/type/store relations |
| `CONFIRMED` | GET | `/orders/{id}` | Order detail | Relations, positions, employees, cash, custom values |
| `CONFIRMED` | GET | `/type-orders` | Order types | Four source-backed types with unique IDs |
| `CONFIRMED` | GET | `/type-orders/{id}/fields` | Custom fields | Two definitions on one of four types |
| `CONFIRMED` | GET | `/statuses` | Statuses | Five grouped statuses and role permissions |
| `CONFIRMED` | GET | `/shops/{id}/cash-registers` | Cash registers | Four registers; store relations and balance visibility confirmed |
| `CONFIRMED` | GET | `/cash-registers/{id}/cash` | Cash transactions | Full pagination, direction, returns, employees, and deletion semantics confirmed |
| `CONFIRMED` | GET | `/cash-items` | Cash items | Twenty direction/classification definitions |
| `NOT_NEEDED` | GET | `/shops/{id}/carts` | Carts | Outside MVP analytics scope |
| `NOT_NEEDED` | GET | `/how-knows` | Lead sources | Detail relation is sufficient; dictionary not needed for MVP |
| `NOT_NEEDED` | GET | `/counteragents` | Counterparties | PII-heavy and unnecessary for MVP metrics |

## Known gaps

- Public docs expose no clear product/category catalog endpoint.
- `nomenclatureId` is required and reusable, but its catalog lookup contract is unknown.
- Employee identity is global, but role endpoints do not distinguish roles in this account.
- `isWork=true`, positive `returnCount`, and quantity greater than one are not yet observed.
- `cash.elements` structure is confirmed, but the business meaning of multiple elements and
  `isBankTransfer` still needs validation.
- Zero purchase price is observed, but its business meaning is not confirmed.
- Historical and retroactive order changes are read by `lastAction` with the configured overlap;
  a first production deployment still requires a one-time full backfill.
- The documented REST API has no collection endpoint for sale-return documents. A return can be
  dereferenced through `/documents/{id}` only after its ID is learned from cash transactions or
  another source. A return present only in a detailed report cannot currently be discovered by
  polling alone; this requires a supported LiveSklad endpoint or a webhook carrying the document ID.
- Nested schemas for order `createManager`, position `customer`, and order `cash.elements` have
  not yet been profiled, but they are not blockers for the current MVP.
- Transaction corrections need a rolling overlap because `dateChange` is present on only 2 of 1688
  operations.
- Employee attribution is role-specific upstream; the implemented confirmed rule assigns KPI
  reversal to the original sale employee.

## Discovery log

| Date | Area | Result | Follow-up |
|---|---|---|---|
| 2026-07-12 | Auth | Revalidated by the auth script; secrets were not printed | Proceed to stores |
| 2026-07-12 | Stores | Confirmed 2 stores; all fields present; unique string IDs; no `version` field | Proceed to employees |
| 2026-07-12 | Employees | 12 unique IDs; 10 shared across stores; role endpoints equal employee sets | Proceed to sales |
| 2026-07-12 | Sales list | Date filter confirmed; 252 and 42 sales over 7 days; stable header schemas | Profile all pages and edge counts |
| 2026-07-12 | Sales period | 293 complete records; unique IDs; 74 discounts; 13 zero costs; 56 mixed payments | Profile representative details |
| 2026-07-12 | Sale detail | Employee/store relations confirmed; 21 positions; batches and serial fields profiled | Search rare edge cases |
| 2026-07-12 | Sale detail edges | 30 documents; 56 single-batch positions; cash elements profiled; no work, return, or multi-quantity examples | Move to orders and statuses; revisit edges with known examples |
| 2026-07-12 | Orders overview | 92 recent orders; 4 types; 5 grouped statuses; sparse employee attribution | Profile representative details and custom fields |
| 2026-07-12 | Order details | 7 representative details; relations stable; role-specific employees; 2 custom fields on one type | Proceed to cash and return semantics |
| 2026-07-12 | Cash overview | 4 registers; 20 cash items; 150 of 1686 transactions sampled; amounts always positive | Profile all pages and classify direction/returns |
| 2026-07-12 | Complete cash period | 1688 unique records; 1319 income; 369 outflow; 45 active and 1 deleted sale return; all employees matched | Dereference return documents and positions |
| 2026-07-12 | Return details | Parent sale and original position links confirmed; return quantity, revenue, and cost available | API discovery complete; begin database design |
