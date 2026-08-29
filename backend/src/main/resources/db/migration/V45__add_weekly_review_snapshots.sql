CREATE TABLE weekly_review_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id),
    period_start date NOT NULL,
    period_end date NOT NULL,
    timezone text NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    supersedes_snapshot_id uuid REFERENCES weekly_review_snapshots(id),
    report_contract_version integer NOT NULL CHECK (report_contract_version = 2),
    metrics_policy_version text NOT NULL,
    snapshot_policy_version text NOT NULL,
    quality_policy_version text NOT NULL,
    report_state text NOT NULL
        CHECK (report_state IN ('PREPARING', 'READY', 'PARTIAL', 'BLOCKED')),
    source_data_updated_at timestamptz,
    report_payload jsonb NOT NULL
        CHECK (jsonb_typeof(report_payload) = 'object'),
    content_hash varchar(64) NOT NULL
        CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (store_id, period_start, period_end, revision),
    CHECK (period_end = period_start + 6),
    CHECK (
        (revision = 1 AND supersedes_snapshot_id IS NULL)
        OR
        (revision > 1 AND supersedes_snapshot_id IS NOT NULL)
    )
);

CREATE INDEX ix_weekly_review_snapshots_store_latest
    ON weekly_review_snapshots (
        store_id,
        period_start DESC,
        period_end DESC,
        revision DESC
    );

CREATE FUNCTION validate_weekly_review_snapshot_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    previous_snapshot weekly_review_snapshots%ROWTYPE;
BEGIN
    IF NEW.revision > 1 THEN
        SELECT * INTO previous_snapshot
        FROM weekly_review_snapshots
        WHERE id = NEW.supersedes_snapshot_id;

        IF previous_snapshot.id IS NULL
                OR previous_snapshot.store_id IS DISTINCT FROM NEW.store_id
                OR previous_snapshot.period_start IS DISTINCT FROM NEW.period_start
                OR previous_snapshot.period_end IS DISTINCT FROM NEW.period_end
                OR previous_snapshot.revision IS DISTINCT FROM NEW.revision - 1 THEN
            RAISE EXCEPTION 'Superseded weekly review snapshot is inconsistent';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION prevent_weekly_review_snapshot_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Weekly review snapshots are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_weekly_review_snapshots_validate
    BEFORE INSERT ON weekly_review_snapshots
    FOR EACH ROW EXECUTE FUNCTION validate_weekly_review_snapshot_insert();

CREATE TRIGGER tr_weekly_review_snapshots_immutable
    BEFORE UPDATE OR DELETE ON weekly_review_snapshots
    FOR EACH ROW EXECUTE FUNCTION prevent_weekly_review_snapshot_change();

COMMENT ON TABLE weekly_review_snapshots IS
    'Immutable deterministic weekly-review-contract-v2 reports, isolated from v21 snapshots.';
