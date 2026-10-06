ALTER TABLE sync_jobs DROP CONSTRAINT sync_jobs_job_type_check;
ALTER TABLE sync_jobs ADD CONSTRAINT sync_jobs_job_type_check
    CHECK (job_type IN ('BACKFILL', 'INCREMENTAL', 'HISTORICAL_SALES'));
ALTER TABLE sync_jobs ADD CONSTRAINT sync_jobs_historical_sales_scope_check
    CHECK (job_type <> 'HISTORICAL_SALES'
        OR (phase = 'SALES' AND period_end <= period_start + interval '3 hours'));

CREATE TABLE historical_sales_refresh_state (
    connection_id uuid PRIMARY KEY REFERENCES integration_connections(id),
    history_start timestamptz NOT NULL,
    cursor_start timestamptz NOT NULL,
    cycle_end timestamptz NOT NULL,
    active_job_id uuid REFERENCES sync_jobs(id) ON DELETE SET NULL,
    preferred_window_minutes integer NOT NULL CHECK (preferred_window_minutes BETWEEN 15 AND 180),
    last_success_at timestamptz,
    last_cycle_completed_at timestamptz,
    next_cycle_at timestamptz,
    cycle_started_at timestamptz NOT NULL,
    blocked_at timestamptz,
    blocked_start timestamptz,
    blocked_end timestamptz,
    repair_start timestamptz,
    repair_end timestamptz,
    backfill_repair_allowed boolean NOT NULL DEFAULT true,
    repair_dependencies_required boolean NOT NULL DEFAULT false,
    repair_return_ids uuid[] NOT NULL DEFAULT '{}',
    repair_parent_ids uuid[] NOT NULL DEFAULT '{}',
    block_reason text CHECK (block_reason IN ('PERMANENT_FAILURE', 'CLASSIFICATION_REQUIRED')),
    budget_day date NOT NULL,
    request_attempts integer NOT NULL DEFAULT 0 CHECK (request_attempts >= 0),
    CHECK (cursor_start >= history_start AND cycle_end >= cursor_start),
    CHECK ((blocked_at IS NULL AND blocked_start IS NULL AND blocked_end IS NULL
            AND repair_start IS NULL AND repair_end IS NULL)
        OR (blocked_at IS NOT NULL AND blocked_start IS NOT NULL AND blocked_end IS NOT NULL
            AND repair_start IS NOT NULL AND repair_end IS NOT NULL AND block_reason = 'PERMANENT_FAILURE')),
    CHECK (blocked_end IS NULL OR (blocked_end > blocked_start
        AND repair_start <= blocked_start AND repair_end >= blocked_end)),
    CHECK (cardinality(repair_return_ids) = cardinality(repair_parent_ids)
        AND array_position(repair_return_ids, NULL) IS NULL
        AND array_position(repair_parent_ids, NULL) IS NULL
        AND (array_ndims(repair_return_ids) IS NULL OR array_ndims(repair_return_ids) = 1)
        AND (array_ndims(repair_parent_ids) IS NULL OR array_ndims(repair_parent_ids) = 1)),
    CHECK ((NOT repair_dependencies_required AND cardinality(repair_return_ids) = 0)
        OR (repair_dependencies_required AND blocked_at IS NOT NULL AND cardinality(repair_return_ids) > 0))
);

COMMENT ON TABLE historical_sales_refresh_state IS
    'Explicit bounded historical SALE sweep; linked RETURN repair requires retained exact identities and accepted coherent dependency publication, not covering backfill SUCCESS alone.';


-- SALE-only history refresh is not full-phase weekly snapshot coverage.
CREATE OR REPLACE FUNCTION validate_analytics_snapshot_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    sync_connection_id uuid;
    sync_status text;
    sync_job_type text;
    store_connection_id uuid;
    previous_snapshot analytics_snapshots%ROWTYPE;
BEGIN
    SELECT connection_id, status, job_type
    INTO sync_connection_id, sync_status, sync_job_type
    FROM sync_jobs
    WHERE id = NEW.source_sync_job_id;

    SELECT connection_id
    INTO store_connection_id
    FROM stores
    WHERE id = NEW.store_id;

    IF sync_status IS DISTINCT FROM 'SUCCESS'
            OR sync_job_type IS NULL OR sync_job_type NOT IN ('BACKFILL', 'INCREMENTAL')
            OR sync_connection_id IS DISTINCT FROM store_connection_id THEN
        RAISE EXCEPTION 'Analytics snapshot source sync job is inconsistent';
    END IF;

    IF NEW.revision > 1 THEN
        SELECT *
        INTO previous_snapshot
        FROM analytics_snapshots
        WHERE id = NEW.supersedes_snapshot_id;

        IF previous_snapshot.id IS NULL
                OR previous_snapshot.store_id IS DISTINCT FROM NEW.store_id
                OR previous_snapshot.snapshot_type IS DISTINCT FROM NEW.snapshot_type
                OR previous_snapshot.period_start IS DISTINCT FROM NEW.period_start
                OR previous_snapshot.period_end IS DISTINCT FROM NEW.period_end
                OR previous_snapshot.revision IS DISTINCT FROM NEW.revision - 1 THEN
            RAISE EXCEPTION 'Superseded analytics snapshot is inconsistent';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;
