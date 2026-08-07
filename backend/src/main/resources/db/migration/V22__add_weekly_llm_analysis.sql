CREATE TABLE analytics_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id),
    snapshot_type text NOT NULL DEFAULT 'WEEKLY'
        CHECK (snapshot_type IN ('WEEKLY')),
    period_start date NOT NULL,
    period_end date NOT NULL,
    timezone text NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    supersedes_snapshot_id uuid REFERENCES analytics_snapshots(id),
    revision_reason_code text NOT NULL,
    revision_note text,
    source_sync_job_id uuid NOT NULL REFERENCES sync_jobs(id),
    source_sync_completed_at timestamptz NOT NULL,
    source_data_cutoff timestamptz NOT NULL,
    facts_schema_version integer NOT NULL CHECK (facts_schema_version > 0),
    metrics_contract_version text NOT NULL,
    calculation_version text NOT NULL,
    quality_policy_version text NOT NULL,
    quality_status text NOT NULL
        CHECK (quality_status IN ('READY', 'PARTIAL', 'BLOCKED')),
    facts_payload jsonb NOT NULL
        CHECK (jsonb_typeof(facts_payload) = 'object'),
    facts_hash varchar(64) NOT NULL
        CHECK (facts_hash ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (period_end = period_start + 6),
    CHECK (btrim(timezone) <> ''),
    CHECK (btrim(revision_reason_code) <> ''),
    CHECK (
        (revision = 1
            AND supersedes_snapshot_id IS NULL
            AND revision_reason_code = 'INITIAL')
        OR
        (revision > 1 AND supersedes_snapshot_id IS NOT NULL)
    ),
    UNIQUE (store_id, snapshot_type, period_start, period_end, revision)
);

CREATE INDEX ix_analytics_snapshots_store_latest
    ON analytics_snapshots (
        store_id,
        snapshot_type,
        period_start DESC,
        period_end DESC,
        revision DESC
    );
CREATE INDEX ix_analytics_snapshots_source_sync
    ON analytics_snapshots (source_sync_job_id);

CREATE FUNCTION validate_analytics_snapshot_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    sync_connection_id uuid;
    sync_status text;
    store_connection_id uuid;
    previous_snapshot analytics_snapshots%ROWTYPE;
BEGIN
    SELECT connection_id, status
    INTO sync_connection_id, sync_status
    FROM sync_jobs
    WHERE id = NEW.source_sync_job_id;

    SELECT connection_id
    INTO store_connection_id
    FROM stores
    WHERE id = NEW.store_id;

    IF sync_status IS DISTINCT FROM 'SUCCESS'
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

CREATE FUNCTION prevent_analytics_snapshot_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Analytics snapshots are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_analytics_snapshots_validate
    BEFORE INSERT ON analytics_snapshots
    FOR EACH ROW EXECUTE FUNCTION validate_analytics_snapshot_insert();

CREATE TRIGGER tr_analytics_snapshots_immutable
    BEFORE UPDATE OR DELETE ON analytics_snapshots
    FOR EACH ROW EXECUTE FUNCTION prevent_analytics_snapshot_change();

CREATE TABLE analytics_snapshot_employees (
    snapshot_id uuid NOT NULL REFERENCES analytics_snapshots(id),
    employee_id uuid NOT NULL REFERENCES employees(id),
    employee_ref varchar(5) NOT NULL
        CHECK (employee_ref ~ '^E[0-9]{2,4}$'),
    display_name_snapshot text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (snapshot_id, employee_id),
    UNIQUE (snapshot_id, employee_ref),
    CHECK (btrim(display_name_snapshot) <> '')
);

CREATE INDEX ix_analytics_snapshot_employees_employee_recent
    ON analytics_snapshot_employees (employee_id, created_at DESC);

CREATE FUNCTION prevent_analytics_snapshot_employee_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Analytics snapshot employee membership is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_analytics_snapshot_employees_immutable
    BEFORE UPDATE OR DELETE ON analytics_snapshot_employees
    FOR EACH ROW EXECUTE FUNCTION prevent_analytics_snapshot_employee_change();

CREATE TABLE analytics_snapshot_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id),
    requested_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    job_type text NOT NULL
        CHECK (job_type IN ('INITIAL', 'AUTO_REVISION', 'MANUAL_BACKFILL')),
    period_start date NOT NULL,
    period_end date NOT NULL,
    timezone text NOT NULL,
    source_sync_job_id uuid NOT NULL REFERENCES sync_jobs(id),
    source_data_cutoff timestamptz NOT NULL,
    facts_schema_version integer NOT NULL CHECK (facts_schema_version > 0),
    metrics_contract_version text NOT NULL,
    calculation_version text NOT NULL,
    quality_policy_version text NOT NULL,
    base_snapshot_id uuid REFERENCES analytics_snapshots(id),
    status text NOT NULL
        CHECK (status IN (
            'PENDING',
            'RUNNING',
            'WAITING_RETRY',
            'SUCCESS',
            'FAILED',
            'CANCELLED'
        )),
    outcome text CHECK (outcome IN ('CREATED', 'UNCHANGED')),
    result_snapshot_id uuid REFERENCES analytics_snapshots(id),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts integer NOT NULL CHECK (max_attempts BETWEEN 1 AND 20),
    next_attempt_at timestamptz NOT NULL,
    lease_owner varchar(100),
    lease_until timestamptz,
    cancel_requested boolean NOT NULL DEFAULT false,
    error_code text,
    error_summary text,
    started_at timestamptz,
    finished_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (period_end = period_start + 6),
    CHECK (btrim(timezone) <> ''),
    CHECK (job_type <> 'AUTO_REVISION' OR base_snapshot_id IS NOT NULL),
    CHECK ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (
        (status IN ('SUCCESS', 'FAILED', 'CANCELLED'))
        = (finished_at IS NOT NULL)
    ),
    CHECK (
        (status = 'SUCCESS')
        = (outcome IS NOT NULL AND result_snapshot_id IS NOT NULL)
    ),
    CHECK (
        outcome IS DISTINCT FROM 'UNCHANGED'
        OR result_snapshot_id = base_snapshot_id
    ),
    CHECK ((outcome IS NULL) = (result_snapshot_id IS NULL)),
    CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at)
);

CREATE UNIQUE INDEX ux_analytics_snapshot_jobs_request
    ON analytics_snapshot_jobs (
        store_id,
        period_start,
        period_end,
        source_sync_job_id,
        facts_schema_version,
        metrics_contract_version,
        calculation_version,
        quality_policy_version
    );

CREATE UNIQUE INDEX ux_analytics_snapshot_jobs_one_active
    ON analytics_snapshot_jobs (store_id, period_start, period_end)
    WHERE status IN ('PENDING', 'RUNNING', 'WAITING_RETRY');

CREATE INDEX ix_analytics_snapshot_jobs_claim
    ON analytics_snapshot_jobs (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'WAITING_RETRY');

CREATE INDEX ix_analytics_snapshot_jobs_expired_lease
    ON analytics_snapshot_jobs (lease_until)
    WHERE status = 'RUNNING';

CREATE TRIGGER tr_analytics_snapshot_jobs_updated_at
    BEFORE UPDATE ON analytics_snapshot_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE llm_analysis_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id uuid NOT NULL REFERENCES analytics_snapshots(id),
    generation_revision integer NOT NULL CHECK (generation_revision > 0),
    trigger_type text NOT NULL
        CHECK (trigger_type IN (
            'INITIAL',
            'SNAPSHOT_REVISION',
            'MANUAL_REGENERATION',
            'MODEL_CHANGE'
        )),
    requested_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    provider_code text NOT NULL,
    requested_model text NOT NULL,
    provider_config_version text NOT NULL,
    content_schema_version integer NOT NULL CHECK (content_schema_version > 0),
    prompt_version text NOT NULL,
    analysis_policy_version text NOT NULL,
    budget_policy_version text NOT NULL,
    generation_parameters jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(generation_parameters) = 'object'),
    input_hash varchar(64) NOT NULL
        CHECK (input_hash ~ '^[a-f0-9]{64}$'),
    status text NOT NULL
        CHECK (status IN (
            'PENDING',
            'RUNNING',
            'WAITING_RETRY',
            'SUCCESS',
            'VALIDATION_FAILED',
            'FAILED',
            'SKIPPED',
            'CANCELLED'
        )),
    phase text NOT NULL
        CHECK (phase IN ('PREPARE', 'CALL_PROVIDER', 'VALIDATE_RESPONSE', 'PUBLISH')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    transport_retry_count integer NOT NULL DEFAULT 0 CHECK (transport_retry_count >= 0),
    validation_retry_count integer NOT NULL DEFAULT 0 CHECK (validation_retry_count >= 0),
    max_transport_retries integer NOT NULL CHECK (max_transport_retries BETWEEN 0 AND 10),
    max_validation_retries integer NOT NULL CHECK (max_validation_retries BETWEEN 0 AND 1),
    next_attempt_at timestamptz NOT NULL,
    deadline_at timestamptz NOT NULL,
    lease_owner varchar(100),
    lease_until timestamptz,
    cancel_requested boolean NOT NULL DEFAULT false,
    terminal_reason_code text,
    error_summary text,
    started_at timestamptz,
    finished_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (snapshot_id, generation_revision),
    CHECK ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (
        (status IN (
            'SUCCESS',
            'VALIDATION_FAILED',
            'FAILED',
            'SKIPPED',
            'CANCELLED'
        ))
        = (finished_at IS NOT NULL)
    ),
    CHECK (deadline_at > created_at),
    CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at)
);

CREATE INDEX ix_llm_analysis_jobs_claim
    ON llm_analysis_jobs (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'WAITING_RETRY');

CREATE INDEX ix_llm_analysis_jobs_expired_lease
    ON llm_analysis_jobs (lease_until)
    WHERE status = 'RUNNING';

CREATE INDEX ix_llm_analysis_jobs_snapshot
    ON llm_analysis_jobs (snapshot_id, generation_revision DESC);

CREATE TRIGGER tr_llm_analysis_jobs_updated_at
    BEFORE UPDATE ON llm_analysis_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE llm_analysis_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id uuid NOT NULL REFERENCES llm_analysis_jobs(id),
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    attempt_type text NOT NULL
        CHECK (attempt_type IN ('INITIAL', 'TRANSPORT_RETRY', 'VALIDATION_RETRY')),
    status text NOT NULL
        CHECK (status IN (
            'STARTED',
            'RESPONSE_RECEIVED',
            'SUCCEEDED',
            'TRANSIENT_FAILED',
            'PERMANENT_FAILED',
            'STRUCTURAL_INVALID',
            'SEMANTIC_INVALID',
            'UNKNOWN_OUTCOME',
            'CANCELLED'
        )),
    provider_code text NOT NULL,
    requested_model text NOT NULL,
    resolved_model text,
    provider_request_id text,
    request_hash varchar(64) NOT NULL
        CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    response_hash varchar(64)
        CHECK (response_hash IS NULL OR response_hash ~ '^[a-f0-9]{64}$'),
    response_body text CHECK (
        response_body IS NULL OR octet_length(response_body) <= 1048576
    ),
    validation_violations jsonb CHECK (
        validation_violations IS NULL
        OR jsonb_typeof(validation_violations) = 'array'
    ),
    input_tokens integer CHECK (input_tokens IS NULL OR input_tokens >= 0),
    output_tokens integer CHECK (output_tokens IS NULL OR output_tokens >= 0),
    cached_input_tokens integer
        CHECK (cached_input_tokens IS NULL OR cached_input_tokens >= 0),
    reasoning_tokens integer CHECK (reasoning_tokens IS NULL OR reasoning_tokens >= 0),
    total_tokens integer CHECK (total_tokens IS NULL OR total_tokens >= 0),
    cost_amount numeric(19, 6) CHECK (cost_amount IS NULL OR cost_amount >= 0),
    cost_currency char(3) CHECK (
        cost_currency IS NULL OR cost_currency ~ '^[A-Z]{3}$'
    ),
    latency_ms bigint CHECK (latency_ms IS NULL OR latency_ms >= 0),
    http_status integer CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    error_code text,
    error_summary text,
    started_at timestamptz NOT NULL DEFAULT now(),
    response_received_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (job_id, attempt_number),
    CHECK (
        (status IN ('STARTED', 'RESPONSE_RECEIVED'))
        = (finished_at IS NULL)
    ),
    CHECK (
        response_received_at IS NULL OR response_received_at >= started_at
    ),
    CHECK (finished_at IS NULL OR finished_at >= started_at)
);

CREATE INDEX ix_llm_analysis_attempts_job
    ON llm_analysis_attempts (job_id, attempt_number DESC);

CREATE TABLE llm_interpretations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id),
    snapshot_id uuid NOT NULL REFERENCES analytics_snapshots(id),
    analysis_job_id uuid NOT NULL UNIQUE REFERENCES llm_analysis_jobs(id),
    successful_attempt_id uuid NOT NULL UNIQUE REFERENCES llm_analysis_attempts(id),
    interpretation_type text NOT NULL DEFAULT 'WEEKLY'
        CHECK (interpretation_type IN ('WEEKLY')),
    period_start date NOT NULL,
    period_end date NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    supersedes_interpretation_id uuid REFERENCES llm_interpretations(id),
    publication_reason_code text NOT NULL,
    content_payload jsonb NOT NULL
        CHECK (jsonb_typeof(content_payload) = 'object'),
    content_hash varchar(64) NOT NULL
        CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    validated_at timestamptz NOT NULL,
    published_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (store_id, interpretation_type, period_start, period_end, revision),
    CHECK (period_end = period_start + 6),
    CHECK (
        (revision = 1 AND supersedes_interpretation_id IS NULL)
        OR
        (revision > 1 AND supersedes_interpretation_id IS NOT NULL)
    ),
    CHECK (published_at >= validated_at)
);

CREATE INDEX ix_llm_interpretations_store_latest
    ON llm_interpretations (
        store_id,
        interpretation_type,
        period_start DESC,
        period_end DESC,
        revision DESC
    );

CREATE FUNCTION validate_llm_interpretation_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    source_snapshot analytics_snapshots%ROWTYPE;
    source_job llm_analysis_jobs%ROWTYPE;
    source_attempt llm_analysis_attempts%ROWTYPE;
    previous_interpretation llm_interpretations%ROWTYPE;
BEGIN
    SELECT * INTO source_snapshot
    FROM analytics_snapshots
    WHERE id = NEW.snapshot_id;

    SELECT * INTO source_job
    FROM llm_analysis_jobs
    WHERE id = NEW.analysis_job_id;

    SELECT * INTO source_attempt
    FROM llm_analysis_attempts
    WHERE id = NEW.successful_attempt_id;

    IF source_snapshot.id IS NULL
            OR source_snapshot.store_id IS DISTINCT FROM NEW.store_id
            OR source_snapshot.period_start IS DISTINCT FROM NEW.period_start
            OR source_snapshot.period_end IS DISTINCT FROM NEW.period_end
            OR source_job.snapshot_id IS DISTINCT FROM NEW.snapshot_id
            OR source_attempt.job_id IS DISTINCT FROM NEW.analysis_job_id
            OR source_attempt.status IS DISTINCT FROM 'SUCCEEDED' THEN
        RAISE EXCEPTION 'LLM interpretation provenance is inconsistent';
    END IF;

    IF NEW.revision > 1 THEN
        SELECT * INTO previous_interpretation
        FROM llm_interpretations
        WHERE id = NEW.supersedes_interpretation_id;

        IF previous_interpretation.id IS NULL
                OR previous_interpretation.store_id IS DISTINCT FROM NEW.store_id
                OR previous_interpretation.interpretation_type
                    IS DISTINCT FROM NEW.interpretation_type
                OR previous_interpretation.period_start IS DISTINCT FROM NEW.period_start
                OR previous_interpretation.period_end IS DISTINCT FROM NEW.period_end
                OR previous_interpretation.revision IS DISTINCT FROM NEW.revision - 1 THEN
            RAISE EXCEPTION 'Superseded LLM interpretation is inconsistent';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION prevent_llm_interpretation_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Published LLM interpretations are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_llm_interpretations_validate
    BEFORE INSERT ON llm_interpretations
    FOR EACH ROW EXECUTE FUNCTION validate_llm_interpretation_insert();

CREATE TRIGGER tr_llm_interpretations_immutable
    BEFORE UPDATE OR DELETE ON llm_interpretations
    FOR EACH ROW EXECUTE FUNCTION prevent_llm_interpretation_change();

COMMENT ON TABLE analytics_snapshots IS
    'Immutable, reproducible weekly backend facts used by dashboard and LLM.';
COMMENT ON TABLE analytics_snapshot_jobs IS
    'Durable jobs that build or reuse immutable weekly analytics snapshots.';
COMMENT ON TABLE llm_analysis_jobs IS
    'Provider-neutral durable state machine for one snapshot generation.';
COMMENT ON TABLE llm_analysis_attempts IS
    'One persisted external LLM call, including bounded crash-recovery response.';
COMMENT ON TABLE llm_interpretations IS
    'Structurally and semantically validated, automatically published content.';
