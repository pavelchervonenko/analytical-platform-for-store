WITH ranked_running_runs AS (
    SELECT
        run.id,
        job.status AS job_status,
        row_number() OVER (
            PARTITION BY run.sync_job_id
            ORDER BY run.started_at DESC, run.id DESC
        ) AS attempt_rank
    FROM sync_runs run
    JOIN sync_jobs job ON job.id = run.sync_job_id
    WHERE run.status = 'RUNNING'
), repaired_runs AS (
    UPDATE sync_runs run
    SET status = 'FAILED',
        finished_at = GREATEST(run.started_at, now()),
        records_failed = GREATEST(run.records_failed, 1),
        error_summary = 'Synchronization worker lease expired'
    FROM ranked_running_runs candidate
    WHERE run.id = candidate.id
      AND (
          candidate.job_status <> 'RUNNING'
          OR candidate.attempt_rank > 1
      )
    RETURNING run.id
)
INSERT INTO sync_run_errors (
    sync_run_id,
    stage,
    error_code,
    error_message,
    is_retryable
)
SELECT
    id,
    'SYNC_JOB_RECOVERY',
    'SYNC_WORKER_LEASE_EXPIRED',
    'Synchronization worker lease expired',
    true
FROM repaired_runs;

CREATE UNIQUE INDEX ux_sync_runs_one_running_per_job
    ON sync_runs (sync_job_id)
    WHERE sync_job_id IS NOT NULL AND status = 'RUNNING';

COMMENT ON INDEX ux_sync_runs_one_running_per_job IS
    'A durable synchronization job may have only one active child attempt.';
