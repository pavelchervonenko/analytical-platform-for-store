-- Dormant temporal roster storage. No baseline is inferred from current assignments here.
-- A separate, verified bootstrap must set authoritative_from for each store.
CREATE TABLE store_seller_membership_state (
    store_id uuid PRIMARY KEY REFERENCES stores(id),
    membership_revision bigint NOT NULL DEFAULT 0 CHECK (membership_revision >= 0),
    authoritative_from timestamptz NOT NULL,
    baseline_source text NOT NULL CHECK (length(baseline_source) BETWEEN 1 AND 120),
    baseline_recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (authoritative_from <= baseline_recorded_at)
);

CREATE TABLE seller_membership_history (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id uuid NOT NULL REFERENCES employees(id),
    store_id uuid NOT NULL REFERENCES stores(id),
    employee_active boolean NOT NULL,
    assignment_active boolean NOT NULL,
    participates_in_ranking boolean NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz,
    change_source text NOT NULL CHECK (change_source IN ('BASELINE', 'SYNC', 'MANUAL')),
    effective_time_source text NOT NULL
        CHECK (effective_time_source IN ('APPROVED_BASELINE', 'SOURCE', 'OBSERVED')),
    actor_id uuid,
    sync_run_id uuid REFERENCES sync_runs(id),
    reason text CHECK (reason IS NULL OR length(reason) <= 240),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT fk_seller_membership_history_baseline
        FOREIGN KEY (store_id) REFERENCES store_seller_membership_state(store_id),
    CONSTRAINT ex_seller_membership_history_no_overlap EXCLUDE USING gist (
        store_id WITH =,
        employee_id WITH =,
        tstzrange(valid_from, valid_to, '[)') WITH &&
    )
);

CREATE UNIQUE INDEX ux_seller_membership_history_open
    ON seller_membership_history (store_id, employee_id)
    WHERE valid_to IS NULL;
CREATE INDEX ix_seller_membership_history_period
    ON seller_membership_history (store_id, valid_from, valid_to);
CREATE INDEX ix_seller_membership_history_employee
    ON seller_membership_history (employee_id, valid_from DESC);

CREATE FUNCTION enforce_seller_membership_history_append_only()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.valid_to IS NULL
       AND NEW.valid_to IS NOT NULL
       AND NEW.valid_to > OLD.valid_from
       AND (to_jsonb(NEW) - 'valid_to') = (to_jsonb(OLD) - 'valid_to') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'seller membership history is append-only';
END;
$$;

CREATE TRIGGER tr_seller_membership_history_append_only
    BEFORE UPDATE OR DELETE ON seller_membership_history
    FOR EACH ROW EXECUTE FUNCTION enforce_seller_membership_history_append_only();

CREATE FUNCTION reject_seller_membership_history_truncate()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'seller membership history cannot be truncated';
END;
$$;

CREATE TRIGGER tr_seller_membership_history_no_truncate
    BEFORE TRUNCATE ON seller_membership_history
    FOR EACH STATEMENT EXECUTE FUNCTION reject_seller_membership_history_truncate();

-- The verified lower bound and its provenance cannot be moved after publication.
CREATE FUNCTION reject_seller_membership_baseline_change()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'seller membership baseline is immutable';
END;
$$;

CREATE TRIGGER tr_store_seller_membership_baseline_immutable
    BEFORE UPDATE OF authoritative_from, baseline_source, baseline_recorded_at
        OR DELETE ON store_seller_membership_state
    FOR EACH ROW EXECUTE FUNCTION reject_seller_membership_baseline_change();

CREATE TRIGGER tr_store_seller_membership_no_truncate
    BEFORE TRUNCATE ON store_seller_membership_state
    FOR EACH STATEMENT EXECUTE FUNCTION reject_seller_membership_baseline_change();

CREATE FUNCTION validate_seller_membership_history_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    lower_bound timestamptz;
BEGIN
    SELECT authoritative_from INTO STRICT lower_bound
    FROM store_seller_membership_state WHERE store_id = NEW.store_id;
    IF NEW.valid_from < lower_bound THEN
        RAISE EXCEPTION 'seller membership interval predates verified baseline'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_seller_membership_history_boundary
    BEFORE INSERT ON seller_membership_history
    FOR EACH ROW EXECUTE FUNCTION validate_seller_membership_history_insert();

-- Reuse the current assignment rule: non-manual employees must share the store connection.
CREATE TRIGGER tr_seller_membership_history_connection
    BEFORE INSERT OR UPDATE OF employee_id, store_id ON seller_membership_history
    FOR EACH ROW EXECUTE FUNCTION validate_employee_store_assignment();
