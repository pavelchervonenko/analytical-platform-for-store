CREATE TABLE store_product_inventory_daily (
    store_id uuid NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    product_id uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    snapshot_date date NOT NULL,
    opening_quantity numeric(19, 3) NOT NULL,
    closing_quantity numeric(19, 3) NOT NULL,
    minimum_quantity numeric(19, 3) NOT NULL,
    maximum_quantity numeric(19, 3) NOT NULL,
    closing_retail_price numeric(19, 2)
        CHECK (closing_retail_price IS NULL OR closing_retail_price >= 0),
    closing_cost_amount numeric(19, 2)
        CHECK (closing_cost_amount IS NULL OR closing_cost_amount >= 0),
    was_out_of_stock boolean NOT NULL,
    observation_count bigint NOT NULL CHECK (observation_count > 0),
    first_observed_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, product_id, snapshot_date),
    CHECK (minimum_quantity <= maximum_quantity),
    CHECK (opening_quantity BETWEEN minimum_quantity AND maximum_quantity),
    CHECK (closing_quantity BETWEEN minimum_quantity AND maximum_quantity),
    CHECK (last_observed_at >= first_observed_at)
);
CREATE INDEX ix_inventory_daily_store_date
    ON store_product_inventory_daily (store_id, snapshot_date DESC);
CREATE INDEX ix_inventory_daily_date
    ON store_product_inventory_daily (snapshot_date);

CREATE TRIGGER tr_store_product_inventory_daily_updated_at
    BEFORE UPDATE ON store_product_inventory_daily
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tr_store_product_inventory_daily_connection
    BEFORE INSERT OR UPDATE OF store_id, product_id
    ON store_product_inventory_daily
    FOR EACH ROW EXECUTE FUNCTION validate_inventory_store_product();

CREATE TABLE store_product_inventory_monthly (
    store_id uuid NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    product_id uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    month_start date NOT NULL,
    opening_quantity numeric(19, 3) NOT NULL,
    closing_quantity numeric(19, 3) NOT NULL,
    minimum_quantity numeric(19, 3) NOT NULL,
    maximum_quantity numeric(19, 3) NOT NULL,
    closing_retail_price numeric(19, 2)
        CHECK (closing_retail_price IS NULL OR closing_retail_price >= 0),
    closing_cost_amount numeric(19, 2)
        CHECK (closing_cost_amount IS NULL OR closing_cost_amount >= 0),
    days_out_of_stock integer NOT NULL CHECK (days_out_of_stock >= 0),
    observed_days integer NOT NULL CHECK (observed_days > 0),
    observation_count bigint NOT NULL CHECK (observation_count > 0),
    first_observed_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, product_id, month_start),
    CHECK (month_start = date_trunc('month', month_start)::date),
    CHECK (minimum_quantity <= maximum_quantity),
    CHECK (opening_quantity BETWEEN minimum_quantity AND maximum_quantity),
    CHECK (closing_quantity BETWEEN minimum_quantity AND maximum_quantity),
    CHECK (days_out_of_stock <= observed_days),
    CHECK (last_observed_at >= first_observed_at)
);
CREATE INDEX ix_inventory_monthly_store_month
    ON store_product_inventory_monthly (store_id, month_start DESC);
CREATE INDEX ix_inventory_monthly_month
    ON store_product_inventory_monthly (month_start);

CREATE TRIGGER tr_store_product_inventory_monthly_updated_at
    BEFORE UPDATE ON store_product_inventory_monthly
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tr_store_product_inventory_monthly_connection
    BEFORE INSERT OR UPDATE OF store_id, product_id
    ON store_product_inventory_monthly
    FOR EACH ROW EXECUTE FUNCTION validate_inventory_store_product();

ALTER TABLE raw_record_versions
    DROP CONSTRAINT raw_record_versions_first_sync_run_id_fkey,
    DROP CONSTRAINT raw_record_versions_last_sync_run_id_fkey,
    ALTER COLUMN first_sync_run_id DROP NOT NULL,
    ALTER COLUMN last_sync_run_id DROP NOT NULL,
    ADD CONSTRAINT raw_record_versions_first_sync_run_id_fkey
        FOREIGN KEY (first_sync_run_id) REFERENCES sync_runs(id) ON DELETE SET NULL,
    ADD CONSTRAINT raw_record_versions_last_sync_run_id_fkey
        FOREIGN KEY (last_sync_run_id) REFERENCES sync_runs(id) ON DELETE SET NULL;

ALTER TABLE store_product_inventory
    DROP CONSTRAINT store_product_inventory_last_sync_run_id_fkey,
    ADD CONSTRAINT store_product_inventory_last_sync_run_id_fkey
        FOREIGN KEY (last_sync_run_id) REFERENCES sync_runs(id) ON DELETE SET NULL;

ALTER TABLE store_product_inventory_history
    DROP CONSTRAINT store_product_inventory_history_sync_run_id_fkey,
    ALTER COLUMN sync_run_id DROP NOT NULL,
    ADD CONSTRAINT store_product_inventory_history_sync_run_id_fkey
        FOREIGN KEY (sync_run_id) REFERENCES sync_runs(id) ON DELETE SET NULL;

ALTER TABLE sales_documents
    DROP CONSTRAINT sales_documents_raw_record_version_id_fkey,
    DROP CONSTRAINT sales_documents_last_sync_run_id_fkey,
    ALTER COLUMN last_sync_run_id DROP NOT NULL,
    ADD CONSTRAINT sales_documents_raw_record_version_id_fkey
        FOREIGN KEY (raw_record_version_id)
        REFERENCES raw_record_versions(id) ON DELETE SET NULL,
    ADD CONSTRAINT sales_documents_last_sync_run_id_fkey
        FOREIGN KEY (last_sync_run_id) REFERENCES sync_runs(id) ON DELETE SET NULL;

ALTER TABLE audit_log
    ADD COLUMN retention_class text,
    ADD COLUMN retain_until timestamptz;

-- V10 blocks ordinary audit updates. V12 owns this transactional backfill and
-- reinstalls the stricter trigger below before the migration commits.
DROP TRIGGER tr_audit_log_immutable ON audit_log;

UPDATE audit_log
SET retention_class = CASE
        WHEN action IN (
            'PERFORMANCE_PLAN_CHANGED',
            'WORK_SCHEDULE_REPLACED',
            'PAYROLL_SCHEME_CREATED',
            'PAYROLL_PRODUCT_CLASSIFIED',
            'PAYROLL_CALCULATED',
            'PAYROLL_RECALCULATED',
            'PAYROLL_REVISION_CREATED',
            'PAYROLL_ADJUSTMENT_CREATED',
            'PAYROLL_ADJUSTMENT_VOIDED',
            'PAYROLL_APPROVED',
            'PAYROLL_PAID',
            'MONTHLY_REPORT_FINALIZED',
            'ANNUAL_REPORT_FINALIZED',
            'REPORT_BACKFILL_REQUESTED'
        ) THEN 'FINANCIAL'
        WHEN action IN (
            'USER_CREATED',
            'USER_CHANGED',
            'USER_STORE_ACCESS_CHANGED',
            'USER_PASSWORD_RESET'
        ) THEN 'SECURITY'
        WHEN action IN (
            'MANUAL_SYNC_STARTED',
            'SYNC_JOB_CANCELLATION_REQUESTED',
            'TECHNICAL_DATA_RETENTION_COMPLETED'
        ) THEN 'OPERATIONAL'
        ELSE 'BUSINESS'
    END,
    retain_until = CASE
        WHEN action IN (
            'PERFORMANCE_PLAN_CHANGED',
            'WORK_SCHEDULE_REPLACED',
            'PAYROLL_SCHEME_CREATED',
            'PAYROLL_PRODUCT_CLASSIFIED',
            'PAYROLL_CALCULATED',
            'PAYROLL_RECALCULATED',
            'PAYROLL_REVISION_CREATED',
            'PAYROLL_ADJUSTMENT_CREATED',
            'PAYROLL_ADJUSTMENT_VOIDED',
            'PAYROLL_APPROVED',
            'PAYROLL_PAID',
            'MONTHLY_REPORT_FINALIZED',
            'ANNUAL_REPORT_FINALIZED',
            'REPORT_BACKFILL_REQUESTED'
        ) THEN NULL
        WHEN action IN (
            'USER_CREATED',
            'USER_CHANGED',
            'USER_STORE_ACCESS_CHANGED',
            'USER_PASSWORD_RESET'
        ) THEN created_at + interval '5 years'
        WHEN action IN (
            'MANUAL_SYNC_STARTED',
            'SYNC_JOB_CANCELLATION_REQUESTED',
            'TECHNICAL_DATA_RETENTION_COMPLETED'
        ) THEN created_at + interval '1 year'
        ELSE created_at + interval '3 years'
    END;

ALTER TABLE audit_log
    ALTER COLUMN retention_class SET NOT NULL,
    ADD CONSTRAINT ck_audit_log_retention_class
        CHECK (retention_class IN ('FINANCIAL', 'SECURITY', 'BUSINESS', 'OPERATIONAL')),
    ADD CONSTRAINT ck_audit_log_retention_deadline
        CHECK (
            (retention_class = 'FINANCIAL' AND retain_until IS NULL)
            OR (
                retention_class <> 'FINANCIAL'
                AND retain_until IS NOT NULL
                AND retain_until >= created_at
            )
        );

CREATE INDEX ix_audit_log_retention
    ON audit_log (retain_until, id)
    WHERE retain_until IS NOT NULL;

CREATE TABLE audit_retention_holds (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_log_id uuid NOT NULL REFERENCES audit_log(id) ON DELETE CASCADE,
    reason text NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 2000),
    placed_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    placed_at timestamptz NOT NULL DEFAULT now(),
    released_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    released_at timestamptz,
    CHECK (
        (released_at IS NULL AND released_by IS NULL)
        OR released_at IS NOT NULL
    ),
    CHECK (released_at IS NULL OR released_at >= placed_at)
);
CREATE UNIQUE INDEX ux_audit_retention_holds_active
    ON audit_retention_holds (audit_log_id)
    WHERE released_at IS NULL;

CREATE OR REPLACE FUNCTION prevent_audit_log_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
        AND current_setting('app.audit_retention_cleanup', true) = 'on'
        AND OLD.retention_class <> 'FINANCIAL'
        AND OLD.retain_until <= clock_timestamp()
        AND NOT EXISTS (
            SELECT 1
            FROM audit_retention_holds hold_entry
            WHERE hold_entry.audit_log_id = OLD.id
              AND hold_entry.released_at IS NULL
        )
    THEN
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE'
        AND (
            NEW.actor_user_id IS NOT DISTINCT FROM OLD.actor_user_id
            OR (OLD.actor_user_id IS NOT NULL AND NEW.actor_user_id IS NULL)
        )
        AND (
            NEW.store_id IS NOT DISTINCT FROM OLD.store_id
            OR (OLD.store_id IS NOT NULL AND NEW.store_id IS NULL)
        )
        AND (
            NEW.actor_user_id IS DISTINCT FROM OLD.actor_user_id
            OR NEW.store_id IS DISTINCT FROM OLD.store_id
        )
        AND NEW.id IS NOT DISTINCT FROM OLD.id
        AND NEW.action IS NOT DISTINCT FROM OLD.action
        AND NEW.entity_type IS NOT DISTINCT FROM OLD.entity_type
        AND NEW.entity_id IS NOT DISTINCT FROM OLD.entity_id
        AND NEW.ip_address IS NOT DISTINCT FROM OLD.ip_address
        AND NEW.user_agent IS NOT DISTINCT FROM OLD.user_agent
        AND NEW.metadata IS NOT DISTINCT FROM OLD.metadata
        AND NEW.retention_class IS NOT DISTINCT FROM OLD.retention_class
        AND NEW.retain_until IS NOT DISTINCT FROM OLD.retain_until
        AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at
    THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'audit log entries are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation();


CREATE INDEX ix_raw_record_versions_retention
    ON raw_record_versions (normalization_status, last_seen_at, id);
CREATE INDEX ix_raw_record_versions_retention_identity
    ON raw_record_versions (
        connection_id, store_id, source_system, entity_type, external_id,
        last_seen_at DESC, id DESC
    );
CREATE INDEX ix_sync_runs_retention
    ON sync_runs (status, finished_at, id)
    WHERE finished_at IS NOT NULL;
CREATE INDEX ix_sync_runs_retention_identity
    ON sync_runs (
        connection_id, store_id, source_system, sync_scope, finished_at DESC, id DESC
    ) WHERE finished_at IS NOT NULL;
CREATE INDEX ix_sync_runs_retention_coverage
    ON sync_runs (
        connection_id, store_id, source_system, sync_scope,
        period_end DESC, finished_at DESC, id DESC
    )
    WHERE status IN ('SUCCESS', 'PARTIAL_SUCCESS') AND period_end IS NOT NULL;
CREATE INDEX ix_sync_jobs_retention
    ON sync_jobs (status, finished_at, id)
    WHERE finished_at IS NOT NULL;
CREATE INDEX ix_sync_jobs_retention_identity
    ON sync_jobs (connection_id, job_type, finished_at DESC, id DESC)
    WHERE finished_at IS NOT NULL;
CREATE INDEX ix_quality_issues_retention
    ON data_quality_issues (resolved_at, id)
    WHERE status IN ('RESOLVED', 'IGNORED');

COMMENT ON TABLE store_product_inventory_daily IS
    'Daily inventory rollup retained for three years after detailed observations expire.';
COMMENT ON TABLE audit_log IS
    'Immutable while retained; only guarded expiration of non-financial entries is permitted.';
COMMENT ON TABLE store_product_inventory_monthly IS
    'Long-term monthly inventory rollup retained indefinitely.';
COMMENT ON COLUMN audit_log.retention_class IS
    'Immutable retention category assigned when the audit event is created.';
COMMENT ON COLUMN audit_log.retain_until IS
    'Earliest permitted purge time; NULL means indefinite retention.';
COMMENT ON TABLE audit_retention_holds IS
    'Administrative holds that prevent otherwise eligible audit entries from being purged.';
