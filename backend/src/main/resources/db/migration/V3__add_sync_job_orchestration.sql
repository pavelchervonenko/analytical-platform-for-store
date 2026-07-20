CREATE TABLE sync_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id uuid NOT NULL REFERENCES integration_connections(id),
    requested_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    job_type text NOT NULL CHECK (job_type IN ('BACKFILL', 'INCREMENTAL')),
    status text NOT NULL CHECK (
        status IN ('PENDING', 'RUNNING', 'WAITING_RETRY', 'SUCCESS', 'FAILED', 'CANCELLED')
    ),
    phase text NOT NULL CHECK (phase IN ('STORES', 'EMPLOYEES', 'SALES', 'RETURNS')),
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    cursor_start timestamptz NOT NULL,
    current_window_end timestamptz NOT NULL,
    window_size_minutes integer NOT NULL CHECK (window_size_minutes BETWEEN 15 AND 44640),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts integer NOT NULL CHECK (max_attempts BETWEEN 1 AND 20),
    completed_steps integer NOT NULL DEFAULT 0 CHECK (completed_steps >= 0),
    total_retries integer NOT NULL DEFAULT 0 CHECK (total_retries >= 0),
    cancel_requested boolean NOT NULL DEFAULT false,
    next_attempt_at timestamptz NOT NULL,
    lease_owner varchar(100),
    lease_until timestamptz,
    error_summary text,
    started_at timestamptz,
    finished_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (period_end > period_start),
    CHECK (cursor_start >= period_start AND cursor_start <= period_end),
    CHECK (current_window_end >= cursor_start AND current_window_end <= period_end),
    CHECK (status = 'SUCCESS' OR current_window_end > cursor_start),
    CHECK ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((status IN ('SUCCESS', 'FAILED', 'CANCELLED')) = (finished_at IS NOT NULL)),
    CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at)
);

CREATE UNIQUE INDEX ux_sync_jobs_one_active_per_connection
    ON sync_jobs (connection_id)
    WHERE status IN ('PENDING', 'RUNNING', 'WAITING_RETRY');
CREATE INDEX ix_sync_jobs_claim
    ON sync_jobs (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'WAITING_RETRY');
CREATE INDEX ix_sync_jobs_expired_lease
    ON sync_jobs (lease_until)
    WHERE status = 'RUNNING';
CREATE INDEX ix_sync_jobs_history
    ON sync_jobs (connection_id, created_at DESC);

ALTER TABLE sync_runs
    ADD COLUMN sync_job_id uuid REFERENCES sync_jobs(id) ON DELETE SET NULL;
CREATE INDEX ix_sync_runs_job_started_at
    ON sync_runs (sync_job_id, started_at);

CREATE TRIGGER tr_sync_jobs_updated_at
    BEFORE UPDATE ON sync_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE sync_jobs IS
    'Durable resumable orchestration cursor. Detailed child attempts remain in sync_runs.';
COMMENT ON COLUMN sync_jobs.period_end IS
    'Exclusive upper boundary of the requested synchronization period.';
COMMENT ON COLUMN sync_jobs.current_window_end IS
    'Exclusive upper boundary of the current adaptive sales/returns window.';
