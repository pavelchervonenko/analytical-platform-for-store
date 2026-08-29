CREATE TABLE weekly_review_ai_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id uuid NOT NULL REFERENCES weekly_review_snapshots(id),
    prompt_version text NOT NULL,
    content_schema_version integer NOT NULL CHECK (content_schema_version > 0),
    provider_code text NOT NULL,
    requested_model text NOT NULL,
    status text NOT NULL
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts integer NOT NULL CHECK (max_attempts BETWEEN 1 AND 2),
    next_attempt_at timestamptz NOT NULL,
    deadline_at timestamptz NOT NULL,
    lease_owner text,
    lease_until timestamptz,
    last_error_code text,
    last_error_message text,
    last_validation_codes jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(last_validation_codes) = 'array'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (snapshot_id, prompt_version, content_schema_version),
    CHECK (attempt_count <= max_attempts),
    CHECK (deadline_at > created_at),
    CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status <> 'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL)
    )
);

CREATE INDEX ix_weekly_review_ai_jobs_claim
    ON weekly_review_ai_jobs (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'RETRY_WAIT');

CREATE INDEX ix_weekly_review_ai_jobs_expired_lease
    ON weekly_review_ai_jobs (lease_until)
    WHERE status = 'RUNNING';

CREATE TABLE weekly_review_ai_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id uuid NOT NULL REFERENCES weekly_review_ai_jobs(id),
    attempt_number integer NOT NULL CHECK (attempt_number BETWEEN 1 AND 2),
    status text NOT NULL
        CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'REJECTED')),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    input_hash varchar(64) NOT NULL CHECK (input_hash ~ '^[a-f0-9]{64}$'),
    input_payload jsonb NOT NULL CHECK (jsonb_typeof(input_payload) = 'object'),
    response_payload text,
    response_hash varchar(64) CHECK (response_hash ~ '^[a-f0-9]{64}$'),
    validation_outcome text,
    validation_violations jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(validation_violations) = 'array'),
    provider_request_id text,
    resolved_model text,
    estimated_cost numeric(19, 6),
    actual_cost numeric(19, 6),
    cost_currency char(3),
    input_tokens integer,
    output_tokens integer,
    total_tokens integer,
    latency_ms bigint,
    http_status integer,
    error_code text,
    error_message text,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    UNIQUE (job_id, attempt_number),
    CHECK ((actual_cost IS NULL) = (cost_currency IS NULL)),
    CHECK (
        (status = 'STARTED' AND finished_at IS NULL)
        OR
        (status <> 'STARTED' AND finished_at IS NOT NULL)
    )
);

CREATE INDEX ix_weekly_review_ai_attempts_job
    ON weekly_review_ai_attempts (job_id, attempt_number DESC);

CREATE FUNCTION touch_weekly_review_ai_job_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_weekly_review_ai_jobs_updated_at
    BEFORE UPDATE ON weekly_review_ai_jobs
    FOR EACH ROW EXECUTE FUNCTION touch_weekly_review_ai_job_updated_at();

CREATE FUNCTION prevent_final_weekly_review_ai_attempt_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.finished_at IS NOT NULL THEN
        RAISE EXCEPTION 'Final weekly review AI attempts are immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_weekly_review_ai_attempts_final_immutable
    BEFORE UPDATE OR DELETE ON weekly_review_ai_attempts
    FOR EACH ROW EXECUTE FUNCTION prevent_final_weekly_review_ai_attempt_change();

COMMENT ON TABLE weekly_review_ai_jobs IS
    'Durable, fail-closed generation lifecycle for optional v22/schema4 wording.';

COMMENT ON TABLE weekly_review_ai_attempts IS
    'Privacy-reduced provider requests, receipts and validation outcomes for weekly review AI jobs.';
