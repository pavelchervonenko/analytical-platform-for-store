CREATE INDEX ix_analytics_snapshot_jobs_created_result
    ON analytics_snapshot_jobs (result_snapshot_id)
    WHERE status = 'SUCCESS' AND outcome = 'CREATED';

COMMENT ON INDEX ix_analytics_snapshot_jobs_created_result IS
    'Supports reconciliation handoff from created snapshots to LLM analysis jobs.';
