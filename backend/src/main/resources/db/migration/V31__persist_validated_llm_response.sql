ALTER TABLE llm_analysis_attempts
    ADD COLUMN validated_response_hash varchar(64)
        CHECK (
            validated_response_hash IS NULL
            OR validated_response_hash ~ '^[a-f0-9]{64}$'
        ),
    ADD COLUMN validated_response_body text
        CHECK (
            validated_response_body IS NULL
            OR octet_length(validated_response_body) <= 1048576
        );

COMMENT ON COLUMN llm_analysis_attempts.response_body IS
    'Exact provider response retained for audit and validation replay.';
COMMENT ON COLUMN llm_analysis_attempts.validated_response_body IS
    'Canonical backend-normalized content approved for publication.';
COMMENT ON COLUMN llm_analysis_attempts.validated_response_hash IS
    'Lowercase SHA-256 of validated_response_body.';
