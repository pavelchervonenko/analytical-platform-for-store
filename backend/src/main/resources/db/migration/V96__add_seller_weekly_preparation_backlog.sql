-- Dormant, free deterministic preparation queue. No baseline, planner or paid job is activated.
CREATE TABLE seller_weekly_backlog_state (
    store_id uuid PRIMARY KEY REFERENCES stores(id),
    authoritative_from timestamptz NOT NULL,
    timezone text NOT NULL CHECK (length(timezone) BETWEEN 1 AND 120),
    next_period_start date NOT NULL CHECK (extract(isodow FROM next_period_start) = 1),
    updated_at timestamptz NOT NULL
);

CREATE TABLE seller_weekly_preparation_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES seller_weekly_backlog_state(store_id),
    period_start date NOT NULL CHECK (extract(isodow FROM period_start) = 1),
    period_end date NOT NULL CHECK (period_end = period_start + 6),
    timezone text NOT NULL CHECK (length(timezone) BETWEEN 1 AND 120),
    status text NOT NULL CHECK (status IN
        ('PENDING', 'RUNNING', 'WAITING_SOURCES', 'WAITING_HISTORY', 'FAILED', 'SUCCEEDED')),
    preparation_attempt_count integer NOT NULL DEFAULT 0 CHECK (preparation_attempt_count >= 0),
    next_evaluation_at timestamptz NOT NULL,
    lease_owner text CHECK (length(lease_owner) BETWEEN 1 AND 120),
    lease_token uuid,
    lease_until timestamptz,
    last_reason_code text CHECK (last_reason_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    snapshot_id uuid REFERENCES weekly_review_snapshots(id),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (store_id, period_start),
    CHECK ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (status = 'RUNNING' OR (lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)),
    CHECK ((status = 'SUCCEEDED') = (snapshot_id IS NOT NULL))
);

CREATE INDEX ix_seller_weekly_preparation_due
    ON seller_weekly_preparation_jobs(next_evaluation_at, period_start, id)
    WHERE status IN ('PENDING', 'WAITING_SOURCES', 'WAITING_HISTORY');
CREATE INDEX ix_seller_weekly_preparation_expired
    ON seller_weekly_preparation_jobs(lease_until, id) WHERE status = 'RUNNING';

COMMENT ON TABLE seller_weekly_preparation_jobs IS
    'Free store/week preparation backlog with leased claims. No provider requests or baseline inference.';
