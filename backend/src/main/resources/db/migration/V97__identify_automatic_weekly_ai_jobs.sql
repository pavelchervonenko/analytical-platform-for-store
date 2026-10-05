-- Existing jobs and exact approvals remain exact: never infer authorization from old rows.
ALTER TABLE weekly_review_ai_jobs
    ADD COLUMN planning_origin text NOT NULL DEFAULT 'EXACT',
    ADD COLUMN automatic_store_id uuid REFERENCES stores(id),
    ADD COLUMN automatic_period_start date,
    ADD COLUMN automatic_period_end date,
    ADD CONSTRAINT ck_weekly_ai_planning_origin CHECK (
        (planning_origin = 'EXACT' AND automatic_store_id IS NULL
            AND automatic_period_start IS NULL AND automatic_period_end IS NULL)
        OR (planning_origin = 'AUTOMATIC' AND automatic_store_id IS NOT NULL
            AND automatic_period_start IS NOT NULL AND automatic_period_end IS NOT NULL
            AND extract(isodow FROM automatic_period_start) = 1
            AND automatic_period_end = automatic_period_start + 6));

CREATE UNIQUE INDEX ux_weekly_ai_automatic_store_week
    ON weekly_review_ai_jobs(automatic_store_id, automatic_period_start)
    WHERE planning_origin = 'AUTOMATIC';

CREATE FUNCTION validate_weekly_ai_planning_binding()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF ROW(OLD.planning_origin, OLD.automatic_store_id, OLD.automatic_period_start,
                OLD.automatic_period_end) IS DISTINCT FROM
           ROW(NEW.planning_origin, NEW.automatic_store_id, NEW.automatic_period_start,
                NEW.automatic_period_end) THEN
            RAISE EXCEPTION 'Weekly AI planning provenance is immutable' USING ERRCODE = '23514';
        END IF;
        IF OLD.snapshot_id IS DISTINCT FROM NEW.snapshot_id AND (
            OLD.planning_origin <> 'AUTOMATIC' OR OLD.attempt_count <> 0 OR NEW.attempt_count <> 0
            OR OLD.status NOT IN ('PENDING','RETRY_WAIT','RUNNING','FAILED')
            OR (OLD.status = 'FAILED' AND OLD.last_error_code IS DISTINCT FROM 'SNAPSHOT_NOT_CURRENT')
            OR (OLD.status = 'RUNNING' AND OLD.lease_until > clock_timestamp())
            OR OLD.deadline_at <= clock_timestamp()
            OR ROW(OLD.max_attempts, OLD.provider_code, OLD.requested_model, OLD.prompt_version,
                OLD.content_schema_version, OLD.deadline_at) IS DISTINCT FROM
               ROW(NEW.max_attempts, NEW.provider_code, NEW.requested_model, NEW.prompt_version,
                NEW.content_schema_version, NEW.deadline_at)
            OR NEW.status <> 'PENDING' OR NEW.lease_owner IS NOT NULL OR NEW.lease_until IS NOT NULL
            OR EXISTS (SELECT 1 FROM weekly_review_ai_attempts WHERE job_id = OLD.id)
            OR EXISTS (SELECT 1 FROM weekly_review_ai_enrichments WHERE snapshot_id = OLD.snapshot_id)) THEN
            RAISE EXCEPTION 'Weekly AI snapshot binding cannot change after authorization or spend'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.planning_origin = 'AUTOMATIC' AND NOT EXISTS (
        SELECT 1 FROM weekly_review_snapshots snapshot WHERE snapshot.id = NEW.snapshot_id
            AND snapshot.report_contract_version = 3 AND snapshot.store_id = NEW.automatic_store_id
            AND snapshot.period_start = NEW.automatic_period_start
            AND snapshot.period_end = NEW.automatic_period_end) THEN
        RAISE EXCEPTION 'Automatic weekly AI binding must match its seller store/week'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_weekly_ai_planning_binding
    BEFORE INSERT OR UPDATE ON weekly_review_ai_jobs
    FOR EACH ROW EXECUTE FUNCTION validate_weekly_ai_planning_binding();

COMMENT ON COLUMN weekly_review_ai_jobs.planning_origin IS
    'EXACT approvals never retarget; AUTOMATIC can rebind only before any paid attempt within the same deadline.';
