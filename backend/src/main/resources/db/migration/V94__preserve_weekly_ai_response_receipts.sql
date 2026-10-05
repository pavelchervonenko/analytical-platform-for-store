CREATE TABLE weekly_review_ai_response_receipts (
    attempt_id uuid PRIMARY KEY REFERENCES weekly_review_ai_attempts(id),
    receipt_hash varchar(64) NOT NULL CHECK (receipt_hash ~ '^[a-f0-9]{64}$'),
    response_payload text NOT NULL CHECK (octet_length(response_payload) <= 1048576),
    response_hash varchar(64) NOT NULL CHECK (response_hash ~ '^[a-f0-9]{64}$'),
    validation_outcome text NOT NULL,
    validation_violations jsonb NOT NULL CHECK (jsonb_typeof(validation_violations) = 'array'),
    provider_request_id text,
    resolved_model text,
    actual_cost numeric(19, 6) CHECK (actual_cost >= 0),
    cost_currency char(3) CHECK (cost_currency ~ '^[A-Z]{3}$'),
    input_tokens integer CHECK (input_tokens >= 0),
    output_tokens integer CHECK (output_tokens >= 0),
    total_tokens integer CHECK (total_tokens >= 0),
    latency_ms bigint CHECK (latency_ms >= 0),
    http_status integer CHECK (http_status BETWEEN 100 AND 599),
    received_at timestamptz NOT NULL,
    CHECK ((actual_cost IS NULL) = (cost_currency IS NULL))
);

CREATE FUNCTION prevent_weekly_ai_response_receipt_change()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Weekly review AI response receipts are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_weekly_ai_response_receipt_immutable
    BEFORE UPDATE OR DELETE ON weekly_review_ai_response_receipts
    FOR EACH ROW EXECUTE FUNCTION prevent_weekly_ai_response_receipt_change();

COMMENT ON TABLE weekly_review_ai_response_receipts IS
    'One immutable provider response and validation receipt per attempt, independent of job lease and publication.';
