-- Mutable planning checkpoint. Immutable weekly_review_snapshots remain untouched.
CREATE TABLE weekly_review_generation_state (
    store_id uuid NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    period_start date NOT NULL,
    period_end date NOT NULL,
    target_contract_version integer NOT NULL CHECK (target_contract_version = 3),
    last_evaluated_source_identity_hash varchar(64) NOT NULL
        CHECK (last_evaluated_source_identity_hash ~ '^[a-f0-9]{64}$'),
    last_evaluated_source_revision bigint NOT NULL CHECK (last_evaluated_source_revision >= 0),
    compatible_snapshot_id uuid NOT NULL REFERENCES weekly_review_snapshots(id),
    evaluated_at timestamptz NOT NULL,
    outcome text NOT NULL CHECK (outcome IN ('CREATED', 'REUSED', 'FAILED')),
    PRIMARY KEY (store_id, period_start, period_end, target_contract_version),
    CHECK (period_end = period_start + 6)
);

COMMENT ON TABLE weekly_review_generation_state IS
    'Mutable internal v3 generation checkpoint, separate from immutable report revisions';
