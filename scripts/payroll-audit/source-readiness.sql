-- Payroll T-01 source audit. Run with psql -X -v ON_ERROR_STOP=1 -At -f ...
-- Use a read-only account or a verified local copy. No names, payloads or money totals.
-- Counts diagnose source gaps; an all-zero result is NOT proof of business readiness.
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '2s';
SET LOCAL search_path = app, public, pg_catalog;
WITH capabilities AS (
    SELECT EXISTS (
        SELECT 1 FROM pg_catalog.pg_attribute
        WHERE attrelid = 'sales_documents'::regclass
          AND attname = 'attach_source_employee_external_id'
          AND attnum > 0 AND NOT attisdropped
    ) AS processor_field_available
), documents AS MATERIALIZED (
    SELECT d.*, o.business_date AS original_date, o.employee_id AS original_employee,
           o.document_kind AS original_kind, o.store_id AS original_store,
           o.is_deleted AS original_deleted,
           NULLIF(btrim(to_jsonb(d) ->> 'attach_source_employee_external_id'), '') AS processor_external_id,
           processor.id AS processor_id
    FROM sales_documents d
    LEFT JOIN sales_documents o ON o.id = d.original_document_id
    LEFT JOIN employees processor
      ON processor.connection_id = d.connection_id
     AND processor.external_id = NULLIF(btrim(to_jsonb(d) ->> 'attach_source_employee_external_id'), '')
    WHERE NOT d.is_deleted
), facts AS MATERIALIZED (
    SELECT d.id AS document_id, d.store_id, d.document_kind, d.business_date,
           d.source_document_type, d.employee_id, d.original_document_id,
           d.original_date, d.original_store, d.original_kind, d.original_deleted,
           i.id AS item_id, i.original_item_id, i.product_id, i.quantity,
           i.net_amount, i.cost_amount, i.cost_quality,
           oi.sales_document_id AS original_item_document, oi.product_id AS original_product,
           oi.quantity AS original_quantity, oi.net_amount AS original_net,
           oi.cost_amount AS original_cost, oi.is_deleted AS original_item_deleted,
           c.code AS analytics_code, c.category_kind, c.counts_as_additional_revenue,
           override.id AS override_id,
           COALESCE(override.payroll_category_code,
               resolve_default_payroll_category(c.code, p.name, c.payroll_category_code)) AS payroll_role
    FROM documents d
    JOIN sales_document_items i ON i.sales_document_id = d.id AND NOT i.is_deleted
    JOIN products p ON p.id = i.product_id
    JOIN analytics_categories c ON c.id = i.analytics_category_id
    LEFT JOIN sales_document_items oi ON oi.id = i.original_item_id
    LEFT JOIN LATERAL (
        SELECT a.id, a.payroll_category_code
        FROM product_payroll_category_assignments a
        WHERE a.product_id = i.product_id
          AND a.valid_from <= CASE WHEN d.document_kind = 'SALE'
              THEN d.business_date ELSE d.original_date END
          AND (a.valid_to IS NULL OR a.valid_to > CASE WHEN d.document_kind = 'SALE'
              THEN d.business_date ELSE d.original_date END)
        ORDER BY a.valid_from DESC LIMIT 1
    ) override ON true
), returned AS (
    SELECT original_item_id, max(original_quantity) AS original_quantity,
           max(original_net) AS original_net, max(original_cost) AS original_cost,
           sum(quantity) AS returned_quantity, sum(net_amount) AS returned_net,
           sum(cost_amount) AS returned_cost, count(*) FILTER (WHERE cost_amount IS NULL) AS missing_cost
    FROM facts WHERE document_kind = 'RETURN' AND original_item_id IS NOT NULL
    GROUP BY original_item_id
), roles AS (
    SELECT payroll_role, count(*) AS item_count,
           count(*) FILTER (WHERE override_id IS NOT NULL) AS explicit_override_count,
           count(*) FILTER (WHERE analytics_code = 'EXCLUDE') AS analytically_excluded_count
    FROM facts GROUP BY payroll_role
), source_types AS (
    SELECT source_document_type, document_kind, count(*) AS document_count,
           count(*) FILTER (WHERE employee_id IS NULL) AS missing_employee_count
    FROM documents GROUP BY source_document_type, document_kind
), coverage AS (
    SELECT DISTINCT store_id, date_trunc('month', business_date)::date AS month
    FROM documents
)
SELECT jsonb_build_object(
    'audit_contract', 'payroll-source-readiness-v2',
    'scope', 'all active normalized facts at one database snapshot; no provider verification',
    'stores', (SELECT count(*) FROM stores),
    'products', (SELECT count(*) FROM products),
    'documents', (SELECT count(*) FROM documents),
    'items', (SELECT count(*) FROM facts),
    'sales_without_employee', (SELECT count(*) FROM documents WHERE document_kind = 'SALE' AND employee_id IS NULL),
    'documents_without_active_items', (SELECT count(*) FROM documents d WHERE NOT EXISTS (
        SELECT 1 FROM sales_document_items i WHERE i.sales_document_id = d.id AND NOT i.is_deleted)),
    'invalid_original_documents', (SELECT count(*) FROM documents WHERE document_kind = 'RETURN' AND (
        original_kind IS DISTINCT FROM 'SALE' OR original_deleted IS DISTINCT FROM false
        OR original_store IS DISTINCT FROM store_id)),
    'return_employee_differs_from_original', (SELECT count(*) FROM documents
        WHERE document_kind = 'RETURN' AND original_kind = 'SALE'
          AND employee_id IS DISTINCT FROM original_employee),
    -- D-006: different original/processing employees are legitimate, not an error.
    'processor_field_available', (SELECT processor_field_available FROM capabilities),
    'return_processor_missing', CASE WHEN (SELECT processor_field_available FROM capabilities)
        THEN (SELECT count(*) FROM documents WHERE document_kind = 'RETURN' AND processor_external_id IS NULL) END,
    'return_processor_unresolved', CASE WHEN (SELECT processor_field_available FROM capabilities)
        THEN (SELECT count(*) FROM documents WHERE document_kind = 'RETURN'
            AND processor_external_id IS NOT NULL AND processor_id IS NULL) END,
    'return_processor_differs_from_original', CASE WHEN (SELECT processor_field_available FROM capabilities)
        THEN (SELECT count(*) FROM documents WHERE document_kind = 'RETURN'
            AND processor_id IS NOT NULL AND original_employee IS NOT NULL
            AND processor_id <> original_employee) END,
    'return_stored_employee_differs_from_processor', CASE WHEN (SELECT processor_field_available FROM capabilities)
        THEN (SELECT count(*) FROM documents WHERE document_kind = 'RETURN'
            AND processor_id IS NOT NULL AND employee_id IS DISTINCT FROM processor_id) END,
    'linked_returns_without_original_employee', (SELECT count(*) FROM documents
        WHERE document_kind = 'RETURN' AND original_kind = 'SALE' AND original_employee IS NULL),
    'cross_month_return_documents', (SELECT count(*) FROM documents WHERE document_kind = 'RETURN'
        AND date_trunc('month', business_date) <> date_trunc('month', original_date)),
    'return_before_sale_documents', (SELECT count(*) FROM documents
        WHERE document_kind = 'RETURN' AND business_date < original_date),
    'invalid_original_items', (SELECT count(*) FROM facts WHERE document_kind = 'RETURN' AND (
        original_item_id IS NULL OR original_item_document IS DISTINCT FROM original_document_id
        OR original_product IS DISTINCT FROM product_id OR original_item_deleted IS DISTINCT FROM false)),
    'overreturned_items', (SELECT count(*) FROM returned WHERE returned_quantity > original_quantity),
    'overrefunded_items', (SELECT count(*) FROM returned WHERE returned_net > original_net),
    'fully_returned_net_mismatch', (SELECT count(*) FROM returned
        WHERE returned_quantity = original_quantity AND returned_net <> original_net),
    'fully_returned_cost_unverifiable', (SELECT count(*) FROM returned
        WHERE returned_quantity = original_quantity AND (original_cost IS NULL OR missing_cost > 0)),
    'fully_returned_cost_mismatch', (SELECT count(*) FROM returned
        WHERE returned_quantity = original_quantity AND original_cost IS NOT NULL AND missing_cost = 0
          AND returned_cost <> original_cost),
    'unmapped_payroll_items', (SELECT count(*) FROM facts WHERE payroll_role = 'UNMAPPED' AND analytics_code <> 'EXCLUDE'),
    'gp_items_missing_cost', (SELECT count(*) FROM facts
        WHERE payroll_role IN ('PLAYSTATION_SUBSCRIPTION','PAID_REPAIR') AND cost_amount IS NULL AND analytics_code <> 'EXCLUDE'),
    'gp_items_zero_cost_needs_evidence', (SELECT count(*) FROM facts
        WHERE payroll_role IN ('PLAYSTATION_SUBSCRIPTION','PAID_REPAIR') AND cost_amount = 0 AND analytics_code <> 'EXCLUDE'),
    'cost_quality_requires_review', (SELECT count(*) FROM facts WHERE cost_quality IN ('MISSING','ZERO_UNEXPECTED')),
    'excluded_items_with_paid_override', (SELECT count(*) FROM facts WHERE analytics_code = 'EXCLUDE'
        AND override_id IS NOT NULL AND payroll_role NOT IN ('UNMAPPED','EXCLUDE')),
    'accessory_payroll_outside_analytics_additional', (SELECT count(*) FROM facts WHERE payroll_role = 'ACCESSORY'
        AND NOT counts_as_additional_revenue AND analytics_code <> 'EXCLUDE'),
    'gp_payroll_outside_service_analytics', (SELECT count(*) FROM facts
        WHERE payroll_role IN ('PLAYSTATION_SUBSCRIPTION','PAID_REPAIR')
          AND category_kind NOT IN ('SERVICE','WARRANTY','PROTECTION') AND analytics_code <> 'EXCLUDE'),
    'months_without_store_plan', (SELECT count(*) FROM coverage c WHERE NOT EXISTS (
        SELECT 1 FROM store_performance_plans p WHERE p.store_id = c.store_id AND p.plan_month = c.month)),
    'payroll_roles', (SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY r.payroll_role), '[]'::jsonb) FROM roles r),
    'source_types', (SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY t.source_document_type,t.document_kind), '[]'::jsonb) FROM source_types t),
    'working_data_verified', false,
    'notes', 'Employee attribution differences describe current versus planned D-006 scopes, not automatic errors. No cost breakdown or actual seller-role evidence is implied by populated columns; use the T-01 checklist.'
);
ROLLBACK;
