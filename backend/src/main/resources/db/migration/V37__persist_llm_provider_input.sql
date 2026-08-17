ALTER TABLE llm_analysis_attempts
    ADD COLUMN provider_input_hash varchar(64),
    ADD COLUMN provider_input_body text,
    ADD CONSTRAINT ck_llm_attempt_provider_input_pair CHECK (
        (provider_input_hash IS NULL) = (provider_input_body IS NULL)
    ),
    ADD CONSTRAINT ck_llm_attempt_provider_input_hash CHECK (
        provider_input_hash IS NULL
        OR provider_input_hash ~ '^[a-f0-9]{64}$'
    ),
    ADD CONSTRAINT ck_llm_attempt_provider_input_size CHECK (
        provider_input_body IS NULL
        OR octet_length(provider_input_body) <= 524288
    ),
    ADD CONSTRAINT ck_llm_attempt_provider_input_object CHECK (
        provider_input_body IS NULL
        OR jsonb_typeof(provider_input_body::jsonb) = 'object'
    );

COMMENT ON COLUMN llm_analysis_attempts.provider_input_body IS
    'Exact compact JSON input sent to the provider for this attempt. '
    'Legacy attempts created before this migration may be null.';
COMMENT ON COLUMN llm_analysis_attempts.provider_input_hash IS
    'Lowercase SHA-256 of provider_input_body. '
    'Legacy attempts created before this migration may be null.';

COMMENT ON COLUMN llm_analysis_attempts.request_hash IS
    'Lowercase SHA-256 of the complete provider request material, including '
    'prompt, input, response schema and generation parameters.';
