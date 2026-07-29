CREATE TABLE report_backfill_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id),
    requested_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    idempotency_key varchar(100) NOT NULL,
    report_year integer NOT NULL CHECK (report_year BETWEEN 2000 AND 2100),
    status text NOT NULL CHECK (
        status IN ('PENDING', 'RUNNING', 'WAITING_RETRY', 'SUCCESS', 'FAILED', 'CANCELLED')
    ),
    phase text NOT NULL CHECK (phase IN ('MONTHLY', 'ANNUAL')),
    cursor_month integer NOT NULL CHECK (cursor_month BETWEEN 1 AND 12),
    paid_month_count integer NOT NULL DEFAULT 0 CHECK (paid_month_count BETWEEN 0 AND 12),
    monthly_created_count integer NOT NULL DEFAULT 0 CHECK (monthly_created_count BETWEEN 0 AND 12),
    monthly_existing_count integer NOT NULL DEFAULT 0 CHECK (monthly_existing_count BETWEEN 0 AND 12),
    annual_report_id uuid REFERENCES report_snapshots(id),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts integer NOT NULL CHECK (max_attempts BETWEEN 1 AND 10),
    completed_steps integer NOT NULL DEFAULT 0 CHECK (completed_steps BETWEEN 0 AND 13),
    total_retries integer NOT NULL DEFAULT 0 CHECK (total_retries >= 0),
    cancel_requested boolean NOT NULL DEFAULT false,
    next_attempt_at timestamptz NOT NULL,
    lease_owner varchar(100),
    lease_until timestamptz,
    error_summary varchar(300),
    started_at timestamptz,
    finished_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (monthly_created_count + monthly_existing_count = paid_month_count),
    CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CHECK ((status IN ('SUCCESS', 'FAILED', 'CANCELLED')) = (finished_at IS NOT NULL)),
    CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at)
);

CREATE UNIQUE INDEX ux_report_backfill_jobs_idempotency
    ON report_backfill_jobs (requested_by, idempotency_key)
    WHERE requested_by IS NOT NULL;
CREATE UNIQUE INDEX ux_report_backfill_jobs_one_active_per_store
    ON report_backfill_jobs (store_id)
    WHERE status IN ('PENDING', 'RUNNING', 'WAITING_RETRY');
CREATE INDEX ix_report_backfill_jobs_claim
    ON report_backfill_jobs (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'WAITING_RETRY');
CREATE INDEX ix_report_backfill_jobs_expired_lease
    ON report_backfill_jobs (lease_until)
    WHERE status = 'RUNNING';
CREATE INDEX ix_report_backfill_jobs_history
    ON report_backfill_jobs (store_id, created_at DESC);

CREATE TRIGGER tr_report_backfill_jobs_updated_at
    BEFORE UPDATE ON report_backfill_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE report_backfill_jobs IS
    'Durable resumable administrative report backfill; one atomic month or annual step per claim.';
COMMENT ON COLUMN report_backfill_jobs.idempotency_key IS
    'Opaque caller key scoped to the requesting administrator; never logged or exposed as a metric.';
