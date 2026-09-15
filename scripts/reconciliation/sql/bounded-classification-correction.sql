\set ON_ERROR_STOP on

BEGIN ISOLATION LEVEL SERIALIZABLE;

SET LOCAL search_path TO :"schema_name", pg_catalog;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '90s';
SET LOCAL idle_in_transaction_session_timeout = '90s';

CREATE TEMP TABLE correction_manifest_json (
  payload jsonb NOT NULL
) ON COMMIT DROP;

INSERT INTO correction_manifest_json (payload)
VALUES (
  convert_from(decode(:'manifest_b64', 'base64'), 'UTF8')::jsonb
);

CREATE TEMP TABLE correction_execution ON COMMIT DROP AS
SELECT
  :'manifest_sha256'::text AS manifest_sha256,
  :'release_commit'::text AS release_commit,
  :'approval_ref'::text AS approval_ref;

CREATE TEMP TABLE correction_manifest ON COMMIT DROP AS
SELECT
  payload,
  payload->>'operation_id' AS operation_id,
  payload->>'required_schema_version' AS required_schema_version,
  payload->>'connection_key' AS connection_key,
  payload->>'store_name' AS store_name,
  payload->>'store_timezone' AS store_timezone,
  (payload->>'period_start')::date AS period_start,
  (payload->>'period_end')::date AS period_end,
  payload->>'change_reason' AS change_reason,
  (payload->'expected'->>'item_count')::integer AS expected_item_count,
  (payload->'expected'->>'quantity')::numeric(19, 3) AS expected_quantity,
  (payload->'expected'->>'net_amount')::numeric(19, 2) AS expected_net_amount,
  (payload->'expected'->>'cost_amount')::numeric(19, 2) AS expected_cost_amount,
  (payload->'expected'->>'analytics_changed_item_count')::integer
    AS expected_analytics_changed_item_count,
  (payload->'expected'->>'payroll_changed_item_count')::integer
    AS expected_payroll_changed_item_count,
  (payload->'expected'->>'analytics_assignment_count')::integer
    AS expected_analytics_assignment_count,
  (payload->'expected'->>'payroll_assignment_count')::integer
    AS expected_payroll_assignment_count
FROM correction_manifest_json;

CREATE TEMP TABLE correction_targets ON COMMIT DROP AS
SELECT target.*
FROM correction_manifest_json manifest
CROSS JOIN LATERAL jsonb_to_recordset(manifest.payload->'items') AS target (
  document_number text,
  document_external_id text,
  item_external_id text,
  product_external_id text,
  product_name_snapshot text,
  employee_ref text,
  business_date date,
  document_kind text,
  source_document_type text,
  source_status text,
  expected_source_kind text,
  quantity numeric(19, 3),
  net_amount numeric(19, 2),
  cost_amount numeric(19, 2),
  is_work boolean,
  condition_type text,
  expected_analytics_category text,
  target_analytics_category text,
  expected_classification_version text,
  target_classification_version text,
  write_analytics_assignment boolean,
  expected_payroll_category text,
  target_payroll_category text,
  write_payroll_assignment boolean
);

DO $validation$
DECLARE
  manifest correction_manifest%ROWTYPE;
  actual_count integer;
BEGIN
  SELECT * INTO STRICT manifest FROM correction_manifest;

  IF (SELECT payload->>'manifest_version' FROM correction_manifest_json) <> '1' THEN
    RAISE EXCEPTION 'unsupported manifest version';
  END IF;
  IF manifest.period_start IS NULL
      OR manifest.period_end IS NULL
      OR manifest.period_end <= manifest.period_start
      OR manifest.operation_id IS NULL
      OR manifest.connection_key IS NULL
      OR manifest.store_name IS NULL
      OR manifest.store_timezone <> 'Europe/Kaliningrad'
      OR manifest.change_reason IS NULL
      OR length(manifest.change_reason) NOT BETWEEN 10 AND 300 THEN
    RAISE EXCEPTION 'manifest header failed validation';
  END IF;
  IF manifest.period_start <> date_trunc('month', manifest.period_start)::date
      OR manifest.period_end <> (manifest.period_start + interval '1 month')::date THEN
    RAISE EXCEPTION 'manifest must cover exactly one calendar month';
  END IF;

  SELECT count(*) INTO actual_count FROM correction_targets;
  IF actual_count <> manifest.expected_item_count OR actual_count = 0 THEN
    RAISE EXCEPTION 'manifest item count mismatch';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM correction_targets
    WHERE document_number IS NULL
       OR document_external_id IS NULL
       OR item_external_id IS NULL
       OR product_external_id IS NULL
       OR product_name_snapshot IS NULL
       OR employee_ref !~ '^[a-f0-9]{32}$'
       OR business_date < manifest.period_start
       OR business_date >= manifest.period_end
       OR document_kind <> 'SALE'
       OR source_document_type <> 'orderPosition'
       OR source_status IS NULL
       OR expected_source_kind <> 'SERVICE'
       OR quantity <= 0
       OR net_amount < 0
       OR cost_amount < 0
       OR is_work IS DISTINCT FROM true
       OR condition_type IS NULL
       OR expected_analytics_category IS NULL
       OR target_analytics_category IS NULL
       OR expected_classification_version IS NULL
       OR target_classification_version IS NULL
       OR write_analytics_assignment IS NULL
       OR expected_payroll_category IS NULL
       OR target_payroll_category IS NULL
       OR write_payroll_assignment IS NULL
  ) THEN
    RAISE EXCEPTION 'manifest contains an invalid target';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM correction_targets
    WHERE expected_analytics_category IN ('IPAD_MAC', 'PODS_WATCH_OTHER_DEVICE')
       OR target_analytics_category IN ('IPAD_MAC', 'PODS_WATCH_OTHER_DEVICE')
  ) THEN
    RAISE EXCEPTION 'dynamic payroll categories are not supported by this correction';
  END IF;
  IF (SELECT count(DISTINCT (document_external_id, item_external_id))
        FROM correction_targets) <> actual_count THEN
    RAISE EXCEPTION 'manifest contains duplicate document/item identities';
  END IF;
  IF (SELECT COALESCE(sum(quantity), 0) FROM correction_targets)
        <> manifest.expected_quantity
      OR (SELECT COALESCE(sum(net_amount), 0) FROM correction_targets)
        <> manifest.expected_net_amount
      OR (SELECT COALESCE(sum(cost_amount), 0) FROM correction_targets)
        <> manifest.expected_cost_amount THEN
    RAISE EXCEPTION 'manifest quantity or monetary totals mismatch';
  END IF;
  IF (SELECT count(*) FROM correction_targets
        WHERE expected_analytics_category <> target_analytics_category)
        <> manifest.expected_analytics_changed_item_count
      OR (SELECT count(*) FROM correction_targets
        WHERE expected_payroll_category <> target_payroll_category)
        <> manifest.expected_payroll_changed_item_count THEN
    RAISE EXCEPTION 'manifest changed-item counts mismatch';
  END IF;
  IF EXISTS (
    SELECT 1 FROM correction_targets
    WHERE (expected_analytics_category <> target_analytics_category)
          IS DISTINCT FROM write_analytics_assignment
  ) THEN
    RAISE EXCEPTION 'analytics transition must have an explicit assignment';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM correction_targets target
    WHERE NOT target.write_analytics_assignment
      AND EXISTS (
        SELECT 1 FROM correction_targets changed
        WHERE changed.product_external_id = target.product_external_id
          AND changed.write_analytics_assignment
      )
  ) OR EXISTS (
    SELECT 1
    FROM correction_targets target
    WHERE NOT target.write_payroll_assignment
      AND target.expected_payroll_category = target.target_payroll_category
      AND EXISTS (
        SELECT 1 FROM correction_targets changed
        WHERE changed.product_external_id = target.product_external_id
          AND changed.write_payroll_assignment
      )
  ) THEN
    RAISE EXCEPTION 'one product has inconsistent assignment coverage';
  END IF;
  IF EXISTS (
    SELECT product_external_id
    FROM correction_targets
    WHERE write_analytics_assignment
    GROUP BY product_external_id
    HAVING count(DISTINCT (target_analytics_category, condition_type)) <> 1
  ) OR EXISTS (
    SELECT product_external_id
    FROM correction_targets
    WHERE write_payroll_assignment
    GROUP BY product_external_id
    HAVING count(DISTINCT target_payroll_category) <> 1
  ) THEN
    RAISE EXCEPTION 'one product has conflicting target categories';
  END IF;
  IF (SELECT count(DISTINCT product_external_id) FROM correction_targets
        WHERE write_analytics_assignment)
        <> manifest.expected_analytics_assignment_count
      OR (SELECT count(DISTINCT product_external_id) FROM correction_targets
        WHERE write_payroll_assignment)
        <> manifest.expected_payroll_assignment_count THEN
    RAISE EXCEPTION 'manifest assignment counts mismatch';
  END IF;
END
$validation$;

DO $schema_check$
DECLARE
  expected_version text;
  actual_version text;
BEGIN
  SELECT required_schema_version INTO STRICT expected_version
  FROM correction_manifest;
  SELECT version INTO actual_version
  FROM flyway_schema_history
  WHERE success
  ORDER BY installed_rank DESC
  LIMIT 1;
  IF actual_version IS DISTINCT FROM expected_version THEN
    RAISE EXCEPTION 'database schema version does not match the manifest';
  END IF;
END
$schema_check$;

CREATE TEMP TABLE correction_context ON COMMIT DROP AS
SELECT
  store.id AS store_id,
  store.connection_id,
  manifest.operation_id,
  manifest.store_timezone,
  manifest.period_start,
  manifest.period_end,
  manifest.change_reason,
  manifest.expected_item_count,
  manifest.expected_quantity,
  manifest.expected_net_amount,
  manifest.expected_cost_amount,
  manifest.expected_analytics_changed_item_count,
  manifest.expected_payroll_changed_item_count,
  manifest.expected_analytics_assignment_count,
  manifest.expected_payroll_assignment_count
FROM correction_manifest manifest
JOIN integration_connections connection
  ON connection.connection_key = manifest.connection_key
 AND connection.source_system = 'LIVESKLAD'
JOIN stores store
  ON store.connection_id = connection.id
 AND store.source_system = 'LIVESKLAD'
 AND store.name = manifest.store_name
 AND store.timezone = manifest.store_timezone
 AND store.is_active;

DO $context_check$
BEGIN
  IF (SELECT count(*) FROM correction_context) <> 1 THEN
    RAISE EXCEPTION 'manifest did not resolve to exactly one active store';
  END IF;
  IF NOT pg_try_advisory_xact_lock(
      hashtextextended('bounded-classification-correction', 0))
      OR NOT pg_try_advisory_xact_lock(
      hashtextextended((SELECT operation_id FROM correction_context), 0)) THEN
    RAISE EXCEPTION 'another correction holds the operation lock';
  END IF;
END
$context_check$;

\if :do_apply
LOCK TABLE
  sales_documents,
  sales_document_items,
  product_category_assignments,
  product_payroll_category_assignments,
  payroll_runs,
  report_snapshots,
  sync_runs,
  sync_jobs,
  report_backfill_jobs,
  livesklad_webhook_receipts
IN SHARE ROW EXCLUSIVE MODE;
\endif

CREATE TEMP TABLE correction_actual ON COMMIT DROP AS
SELECT
  target.*,
  document.id AS document_id,
  item.id AS item_id,
  product.id AS product_id,
  product.name AS actual_product_name,
  product.source_kind AS actual_source_kind,
  document.business_date AS actual_business_date,
  document.document_kind AS actual_document_kind,
  document.source_document_type AS actual_source_document_type,
  document.source_status AS actual_source_status,
  md5(context.operation_id || ':' || employee.external_id) AS actual_employee_ref,
  item.product_name_snapshot AS actual_product_name_snapshot,
  item.quantity AS actual_quantity,
  item.net_amount AS actual_net_amount,
  item.cost_amount AS actual_cost_amount,
  item.is_work AS actual_is_work,
  item.condition_type_snapshot AS actual_condition_type,
  category.code AS actual_analytics_category,
  item.classification_version AS actual_classification_version,
  COALESCE(payroll_override.payroll_category_code, category.payroll_category_code)
    AS actual_payroll_category
FROM correction_targets target
CROSS JOIN correction_context context
JOIN sales_documents document
  ON document.connection_id = context.connection_id
 AND document.store_id = context.store_id
 AND document.external_id = target.document_external_id
 AND document.document_number = target.document_number
 AND NOT document.is_deleted
JOIN sales_document_items item
  ON item.sales_document_id = document.id
 AND item.external_id = target.item_external_id
 AND NOT item.is_deleted
JOIN products product
  ON product.id = item.product_id
 AND product.connection_id = context.connection_id
 AND product.external_id = target.product_external_id
LEFT JOIN employees employee ON employee.id = document.employee_id
JOIN analytics_categories category ON category.id = item.analytics_category_id
LEFT JOIN LATERAL (
  SELECT assignment.payroll_category_code
  FROM product_payroll_category_assignments assignment
  WHERE assignment.product_id = product.id
    AND assignment.valid_from <= document.business_date
    AND (assignment.valid_to IS NULL OR assignment.valid_to > document.business_date)
  ORDER BY assignment.valid_from DESC
  LIMIT 1
) payroll_override ON true;

DO $current_fact_check$
DECLARE
  context correction_context%ROWTYPE;
BEGIN
  SELECT * INTO STRICT context FROM correction_context;
  IF (SELECT count(*) FROM correction_actual) <> context.expected_item_count THEN
    RAISE EXCEPTION 'not all exact document/item/product identities were found';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM correction_actual
    WHERE actual_business_date IS DISTINCT FROM business_date
       OR actual_document_kind IS DISTINCT FROM document_kind
       OR actual_source_document_type IS DISTINCT FROM source_document_type
       OR actual_source_status IS DISTINCT FROM source_status
       OR actual_employee_ref IS DISTINCT FROM employee_ref
       OR actual_product_name IS DISTINCT FROM product_name_snapshot
       OR actual_product_name_snapshot IS DISTINCT FROM product_name_snapshot
       OR actual_source_kind IS DISTINCT FROM expected_source_kind
       OR actual_quantity IS DISTINCT FROM quantity
       OR actual_net_amount IS DISTINCT FROM net_amount
       OR actual_cost_amount IS DISTINCT FROM cost_amount
       OR actual_is_work IS DISTINCT FROM is_work
       OR actual_condition_type IS DISTINCT FROM condition_type
  ) THEN
    RAISE EXCEPTION 'a guarded source fact differs from the manifest';
  END IF;
END
$current_fact_check$;

CREATE TEMP TABLE correction_changed_products ON COMMIT DROP AS
SELECT DISTINCT actual.product_id, actual.product_external_id
FROM correction_actual actual
WHERE actual.write_analytics_assignment OR actual.write_payroll_assignment;

DO $scope_check$
DECLARE
  expected_count integer;
  actual_count integer;
BEGIN
  SELECT count(*) INTO expected_count
  FROM correction_targets target
  JOIN correction_changed_products changed USING (product_external_id);

  SELECT count(*) INTO actual_count
  FROM sales_document_items item
  JOIN sales_documents document ON document.id = item.sales_document_id
  JOIN correction_changed_products changed ON changed.product_id = item.product_id
  CROSS JOIN correction_context context
  WHERE document.connection_id = context.connection_id
    AND document.business_date >= context.period_start
    AND document.business_date < context.period_end
    AND NOT document.is_deleted
    AND NOT item.is_deleted;

  IF actual_count <> expected_count OR EXISTS (
    SELECT 1
    FROM sales_document_items item
    JOIN sales_documents document ON document.id = item.sales_document_id
    JOIN correction_changed_products changed ON changed.product_id = item.product_id
    CROSS JOIN correction_context context
    WHERE document.connection_id = context.connection_id
      AND document.business_date >= context.period_start
      AND document.business_date < context.period_end
      AND NOT document.is_deleted
      AND NOT item.is_deleted
      AND NOT EXISTS (
        SELECT 1 FROM correction_targets target
        WHERE target.document_external_id = document.external_id
          AND target.item_external_id = item.external_id
      )
  ) THEN
    RAISE EXCEPTION 'product-level assignment would affect an unlisted item';
  END IF;
END
$scope_check$;

\if :expect_target
DO $target_state_check$
DECLARE
  context correction_context%ROWTYPE;
BEGIN
  SELECT * INTO STRICT context FROM correction_context;
  IF EXISTS (
    SELECT 1 FROM correction_actual
    WHERE actual_analytics_category IS DISTINCT FROM target_analytics_category
       OR actual_classification_version IS DISTINCT FROM target_classification_version
       OR actual_payroll_category IS DISTINCT FROM target_payroll_category
  ) THEN
    RAISE EXCEPTION 'target classification state is not exact';
  END IF;
  IF (SELECT count(*)
      FROM product_category_assignments assignment
      JOIN correction_changed_products changed ON changed.product_id = assignment.product_id
      WHERE assignment.rule_version = context.operation_id
        AND assignment.valid_from =
          (context.period_start::timestamp AT TIME ZONE context.store_timezone)
        AND assignment.valid_to =
          (context.period_end::timestamp AT TIME ZONE context.store_timezone))
      <> context.expected_analytics_assignment_count THEN
    RAISE EXCEPTION 'analytics assignment verification count mismatch';
  END IF;
  IF (SELECT count(*)
      FROM product_payroll_category_assignments assignment
      JOIN correction_changed_products changed ON changed.product_id = assignment.product_id
      WHERE assignment.change_reason = context.operation_id
        AND assignment.valid_from = context.period_start
        AND assignment.valid_to = context.period_end)
      <> context.expected_payroll_assignment_count THEN
    RAISE EXCEPTION 'payroll assignment verification count mismatch';
  END IF;
  IF (SELECT count(*) FROM audit_log
      WHERE action = 'ANALYTICS_PRODUCT_CLASSIFIED'
        AND metadata->>'operationId' = context.operation_id
        AND metadata->>'manifestSha256' =
          (SELECT manifest_sha256 FROM correction_execution))
      <> context.expected_analytics_assignment_count
      OR (SELECT count(*) FROM audit_log
      WHERE action = 'PAYROLL_PRODUCT_CLASSIFIED'
        AND metadata->>'operationId' = context.operation_id
        AND metadata->>'manifestSha256' =
          (SELECT manifest_sha256 FROM correction_execution))
      <> context.expected_payroll_assignment_count THEN
    RAISE EXCEPTION 'immutable audit verification count mismatch';
  END IF;
END
$target_state_check$;
\else
DO $preflight_state_check$
DECLARE
  context correction_context%ROWTYPE;
BEGIN
  SELECT * INTO STRICT context FROM correction_context;
  IF EXISTS (
    SELECT 1 FROM correction_actual
    WHERE actual_analytics_category IS DISTINCT FROM expected_analytics_category
       OR actual_classification_version IS DISTINCT FROM expected_classification_version
       OR actual_payroll_category IS DISTINCT FROM expected_payroll_category
  ) THEN
    RAISE EXCEPTION 'current classification state differs from the manifest';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM product_category_assignments assignment
    JOIN correction_actual actual ON actual.product_id = assignment.product_id
    WHERE actual.write_analytics_assignment
      AND tstzrange(
        assignment.valid_from,
        COALESCE(assignment.valid_to, 'infinity'::timestamptz),
        '[)'
      ) && tstzrange(
        context.period_start::timestamp AT TIME ZONE context.store_timezone,
        context.period_end::timestamp AT TIME ZONE context.store_timezone,
        '[)'
      )
  ) OR EXISTS (
    SELECT 1
    FROM product_payroll_category_assignments assignment
    JOIN correction_actual actual ON actual.product_id = assignment.product_id
    WHERE actual.write_payroll_assignment
      AND daterange(
        assignment.valid_from,
        COALESCE(assignment.valid_to, 'infinity'::date),
        '[)'
      ) && daterange(context.period_start, context.period_end, '[)')
  ) THEN
    RAISE EXCEPTION 'an existing effective-dated assignment overlaps the manifest';
  END IF;
  IF EXISTS (
    SELECT 1 FROM payroll_runs
    WHERE store_id = context.store_id
      AND period_month = context.period_start
      AND status IN ('APPROVED', 'PAID')
  ) OR EXISTS (
    SELECT 1 FROM report_snapshots
    WHERE store_id = context.store_id
      AND period_start < context.period_end
      AND period_end >= context.period_start
      AND status IN ('APPROVED', 'ARCHIVED', 'FINALIZED')
  ) THEN
    RAISE EXCEPTION 'approved or immutable payroll/report data overlaps the manifest';
  END IF;
  IF EXISTS (
    SELECT 1 FROM sync_runs
    WHERE (store_id = context.store_id OR connection_id = context.connection_id)
      AND status IN ('PENDING', 'RUNNING')
  ) OR EXISTS (
    SELECT 1 FROM sync_jobs
    WHERE connection_id = context.connection_id
      AND status IN ('PENDING', 'RUNNING', 'WAITING_RETRY')
  ) OR EXISTS (
    SELECT 1 FROM report_backfill_jobs
    WHERE store_id = context.store_id
      AND status IN ('PENDING', 'RUNNING', 'WAITING_RETRY')
  ) OR EXISTS (
    SELECT 1 FROM livesklad_webhook_receipts
    WHERE processing_status IN ('RECEIVED', 'PROCESSING')
       OR (recovery_requested_by IS NOT NULL AND processing_status = 'FAILED')
  ) THEN
    RAISE EXCEPTION 'an active sync, recovery, webhook or report job blocks correction';
  END IF;
END
$preflight_state_check$;
\endif

\if :do_apply
CREATE TEMP TABLE correction_inserted_analytics ON COMMIT DROP AS
WITH requested AS (
  SELECT DISTINCT ON (actual.product_id)
    actual.product_id,
    category.id AS analytics_category_id,
    actual.condition_type
  FROM correction_actual actual
  JOIN analytics_categories category
    ON category.code = actual.target_analytics_category
   AND category.is_active
  WHERE actual.write_analytics_assignment
  ORDER BY actual.product_id
), inserted AS (
  INSERT INTO product_category_assignments (
    product_id,
    analytics_category_id,
    condition_type,
    assignment_source,
    rule_version,
    valid_from,
    valid_to,
    assigned_by,
    change_reason
  )
  SELECT
    requested.product_id,
    requested.analytics_category_id,
    requested.condition_type,
    'MANUAL',
    context.operation_id,
    context.period_start::timestamp AT TIME ZONE context.store_timezone,
    context.period_end::timestamp AT TIME ZONE context.store_timezone,
    NULL,
    context.change_reason
  FROM requested
  CROSS JOIN correction_context context
  RETURNING id, product_id, analytics_category_id
)
SELECT * FROM inserted;

UPDATE sales_document_items item
SET analytics_category_id = inserted.analytics_category_id,
    category_assignment_id = inserted.id,
    classification_version = context.operation_id,
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM correction_actual actual
JOIN correction_inserted_analytics inserted
  ON inserted.product_id = actual.product_id
CROSS JOIN correction_context context
WHERE item.id = actual.item_id
  AND actual.write_analytics_assignment;

CREATE TEMP TABLE correction_inserted_payroll ON COMMIT DROP AS
WITH requested AS (
  SELECT DISTINCT ON (actual.product_id)
    actual.product_id,
    actual.target_payroll_category
  FROM correction_actual actual
  WHERE actual.write_payroll_assignment
  ORDER BY actual.product_id
), inserted AS (
  INSERT INTO product_payroll_category_assignments (
    product_id,
    payroll_category_code,
    valid_from,
    valid_to,
    assigned_by,
    change_reason
  )
  SELECT
    requested.product_id,
    requested.target_payroll_category,
    context.period_start,
    context.period_end,
    NULL,
    context.operation_id
  FROM requested
  CROSS JOIN correction_context context
  RETURNING id, product_id, payroll_category_code
)
SELECT * FROM inserted;

INSERT INTO audit_log (
  actor_user_id,
  store_id,
  action,
  entity_type,
  entity_id,
  metadata,
  retention_class,
  retain_until
)
SELECT
  NULL,
  context.store_id,
  'ANALYTICS_PRODUCT_CLASSIFIED',
  'PRODUCT_CATEGORY_ASSIGNMENT',
  inserted.id::text,
  jsonb_build_object(
    'operationId', context.operation_id,
    'manifestSha256', execution.manifest_sha256,
    'releaseCommit', execution.release_commit,
    'approvalRef', execution.approval_ref,
    'periodStart', context.period_start,
    'periodEndExclusive', context.period_end,
    'targetCategory', category.code,
    'reason', context.change_reason
  ),
  'BUSINESS',
  clock_timestamp() + interval '3 years'
FROM correction_inserted_analytics inserted
JOIN analytics_categories category ON category.id = inserted.analytics_category_id
CROSS JOIN correction_context context
CROSS JOIN correction_execution execution;

INSERT INTO audit_log (
  actor_user_id,
  store_id,
  action,
  entity_type,
  entity_id,
  metadata,
  retention_class,
  retain_until
)
SELECT
  NULL,
  context.store_id,
  'PAYROLL_PRODUCT_CLASSIFIED',
  'PRODUCT_PAYROLL_CATEGORY_ASSIGNMENT',
  inserted.id::text,
  jsonb_build_object(
    'operationId', context.operation_id,
    'manifestSha256', execution.manifest_sha256,
    'releaseCommit', execution.release_commit,
    'approvalRef', execution.approval_ref,
    'periodStart', context.period_start,
    'periodEndExclusive', context.period_end,
    'targetCategory', inserted.payroll_category_code,
    'reason', context.change_reason
  ),
  'FINANCIAL',
  NULL
FROM correction_inserted_payroll inserted
CROSS JOIN correction_context context
CROSS JOIN correction_execution execution;

DROP TABLE correction_actual;

CREATE TEMP TABLE correction_actual ON COMMIT DROP AS
SELECT
  target.*,
  document.id AS document_id,
  item.id AS item_id,
  product.id AS product_id,
  category.code AS actual_analytics_category,
  item.classification_version AS actual_classification_version,
  COALESCE(payroll_override.payroll_category_code, category.payroll_category_code)
    AS actual_payroll_category
FROM correction_targets target
CROSS JOIN correction_context context
JOIN sales_documents document
  ON document.connection_id = context.connection_id
 AND document.store_id = context.store_id
 AND document.external_id = target.document_external_id
 AND document.document_number = target.document_number
 AND NOT document.is_deleted
JOIN sales_document_items item
  ON item.sales_document_id = document.id
 AND item.external_id = target.item_external_id
 AND NOT item.is_deleted
JOIN products product
  ON product.id = item.product_id
 AND product.connection_id = context.connection_id
 AND product.external_id = target.product_external_id
JOIN analytics_categories category ON category.id = item.analytics_category_id
LEFT JOIN LATERAL (
  SELECT assignment.payroll_category_code
  FROM product_payroll_category_assignments assignment
  WHERE assignment.product_id = product.id
    AND assignment.valid_from <= document.business_date
    AND (assignment.valid_to IS NULL OR assignment.valid_to > document.business_date)
  ORDER BY assignment.valid_from DESC
  LIMIT 1
) payroll_override ON true;

DO $post_apply_check$
DECLARE
  context correction_context%ROWTYPE;
BEGIN
  SELECT * INTO STRICT context FROM correction_context;
  IF (SELECT count(*) FROM correction_inserted_analytics)
        <> context.expected_analytics_assignment_count
      OR (SELECT count(*) FROM correction_inserted_payroll)
        <> context.expected_payroll_assignment_count
      OR EXISTS (
        SELECT 1 FROM correction_actual
        WHERE actual_analytics_category IS DISTINCT FROM target_analytics_category
           OR actual_classification_version IS DISTINCT FROM target_classification_version
           OR actual_payroll_category IS DISTINCT FROM target_payroll_category
      ) THEN
    RAISE EXCEPTION 'post-apply verification failed; transaction will roll back';
  END IF;
END
$post_apply_check$;
\endif

SELECT json_build_object(
  'status', CASE
    WHEN :'correction_mode' = 'preflight' THEN 'PREFLIGHT_PASS'
    WHEN :'correction_mode' = 'apply' THEN 'APPLY_PASS'
    ELSE 'VERIFY_PASS'
  END,
  'operationId', context.operation_id,
  'itemCount', context.expected_item_count,
  'quantity', context.expected_quantity,
  'netAmount', context.expected_net_amount,
  'costAmount', context.expected_cost_amount,
  'analyticsChangedItems', context.expected_analytics_changed_item_count,
  'payrollChangedItems', context.expected_payroll_changed_item_count
)
FROM correction_context context;

\if :do_apply
COMMIT;
\else
ROLLBACK;
\endif
