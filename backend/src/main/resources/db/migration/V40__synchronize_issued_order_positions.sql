ALTER TABLE sync_jobs
    DROP CONSTRAINT IF EXISTS sync_jobs_phase_check;

ALTER TABLE sync_jobs
    ADD CONSTRAINT sync_jobs_phase_check
    CHECK (phase IN ('STORES', 'EMPLOYEES', 'SALES', 'RETURNS', 'ORDERS'));

ALTER TABLE sync_runs
    DROP CONSTRAINT IF EXISTS sync_runs_sync_scope_check;

ALTER TABLE sync_runs
    ADD CONSTRAINT sync_runs_sync_scope_check
    CHECK (sync_scope IN (
        'FULL',
        'STORES',
        'EMPLOYEES',
        'PRODUCTS',
        'SALES',
        'RETURNS',
        'ORDERS',
        'PERIOD'
    ));

CREATE INDEX idx_sales_documents_order_positions
    ON sales_documents (connection_id, external_id)
    WHERE source_document_type = 'orderPosition';
