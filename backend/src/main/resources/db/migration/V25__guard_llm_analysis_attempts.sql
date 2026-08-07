CREATE UNIQUE INDEX ux_llm_analysis_attempts_one_open
    ON llm_analysis_attempts (job_id)
    WHERE status IN ('STARTED', 'RESPONSE_RECEIVED');

COMMENT ON INDEX ux_llm_analysis_attempts_one_open IS
    'At most one unfinished provider call or unvalidated response per LLM job.';
