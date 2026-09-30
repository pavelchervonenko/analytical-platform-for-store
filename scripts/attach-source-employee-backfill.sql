-- Local/operator only. See docs/runbooks/attach-attribution.md.
-- Required psql variables: store_id, period_start, period_end, after_id, apply.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '3s';
WITH batch AS MATERIALIZED (
    SELECT d.id, d.store_id, d.connection_id, d.external_id, d.raw_record_version_id
    FROM sales_documents d
    WHERE d.store_id = :'store_id'::uuid AND d.document_kind = 'RETURN' AND NOT d.is_deleted
      AND d.attach_source_employee_external_id IS NULL
      AND d.business_date BETWEEN :'period_start'::date AND :'period_end'::date
      AND :'period_end'::date - :'period_start'::date BETWEEN 0 AND 365
      AND d.id > :'after_id'::uuid
    ORDER BY d.id LIMIT 500 FOR UPDATE OF d
), prepared AS MATERIALIZED (
    SELECT b.*, CASE WHEN r.connection_id = b.connection_id AND r.store_id = b.store_id
                      AND r.external_id = b.external_id AND r.entity_type = 'RETURN_DOCUMENT'
                      AND r.payload #>> '{detail,id}' = b.external_id
                      AND jsonb_typeof(r.payload #> '{detail,customer,id}') IN ('string','number')
                    THEN nullif(trim(r.payload #>> '{detail,customer,id}'), '') END AS source_employee
    FROM batch b LEFT JOIN raw_record_versions r ON r.id = b.raw_record_version_id
), updated AS (
    UPDATE sales_documents d SET attach_source_employee_external_id = p.source_employee
    FROM prepared p WHERE d.id = p.id AND p.source_employee IS NOT NULL AND :'apply' = 'true'
    RETURNING d.store_id
), invalidated AS (
    INSERT INTO attach_attribution_changes (store_id, changed_at)
    SELECT DISTINCT store_id, statement_timestamp() FROM updated
    ON CONFLICT (store_id) DO UPDATE SET changed_at = EXCLUDED.changed_at
    RETURNING store_id
)
SELECT count(*) AS scanned, count(source_employee) AS recoverable,
       count(*) FILTER (WHERE source_employee IS NULL) AS missing_evidence,
       (SELECT count(*) FROM updated) AS updated,
       (SELECT count(*) FROM invalidated) AS invalidated_stores,
       max(id::text) AS next_cursor
FROM prepared;
COMMIT;
