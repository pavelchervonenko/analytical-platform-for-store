CREATE TABLE employee_rating_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id),
    period_start date NOT NULL,
    period_end date NOT NULL,
    formula_code varchar(100) NOT NULL,
    result_payload text NOT NULL,
    result_sha256 varchar(64) NOT NULL,
    finalized_by uuid NOT NULL REFERENCES app_users(id),
    finalized_by_name varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT employee_rating_snapshots_period_check
        CHECK (period_end >= period_start),
    CONSTRAINT employee_rating_snapshots_payload_check
        CHECK (jsonb_typeof(result_payload::jsonb) = 'object'),
    CONSTRAINT employee_rating_snapshots_hash_check
        CHECK (result_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT employee_rating_snapshots_store_period_unique
        UNIQUE (store_id, period_start, period_end)
);

CREATE INDEX idx_employee_rating_snapshots_store_period
    ON employee_rating_snapshots (store_id, period_start DESC, period_end DESC);

COMMENT ON TABLE employee_rating_snapshots IS
    'Immutable finalized employee rating results. There is no update or delete API.';

COMMENT ON COLUMN employee_rating_snapshots.result_payload IS
    'Exact JSON rating result captured at finalization time.';

COMMENT ON COLUMN employee_rating_snapshots.result_sha256 IS
    'SHA-256 of result_payload, verified whenever the snapshot is read.';
