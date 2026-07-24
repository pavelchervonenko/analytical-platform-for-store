ALTER TABLE stores
    ADD COLUMN reporting_started_on date;

UPDATE stores
SET reporting_started_on = DATE '2026-01-01'
WHERE reporting_started_on IS NULL;

ALTER TABLE stores
    ALTER COLUMN reporting_started_on SET NOT NULL,
    ALTER COLUMN reporting_started_on SET DEFAULT CURRENT_DATE;

ALTER TABLE report_snapshots
    RENAME COLUMN formula_version TO template_version;
ALTER TABLE report_snapshots
    RENAME COLUMN classification_version TO data_contract_version;
ALTER TABLE report_snapshots
    RENAME COLUMN input_hash TO source_hash;
UPDATE report_snapshots
SET metadata = metadata || jsonb_build_object('legacyReportType', report_type),
    report_type = CASE
        WHEN period_type = 'MONTH' THEN 'MONTHLY'
        ELSE 'ANNUAL'
    END
WHERE report_type NOT IN ('MONTHLY', 'ANNUAL');


ALTER TABLE report_snapshots
    DROP CONSTRAINT report_snapshots_status_check;

ALTER TABLE report_snapshots
    ADD CONSTRAINT report_snapshots_status_check
        CHECK (status IN ('DRAFT', 'CALCULATED', 'APPROVED', 'ARCHIVED', 'FINALIZED'));

ALTER TABLE report_snapshots
    DROP CONSTRAINT report_snapshots_check1;

ALTER TABLE report_snapshots
    ADD CONSTRAINT report_snapshots_approval_check
        CHECK ((status IN ('APPROVED', 'ARCHIVED', 'FINALIZED') AND approved_at IS NOT NULL)
            OR status IN ('DRAFT', 'CALCULATED'));

ALTER TABLE report_snapshots
    ADD COLUMN schema_version integer NOT NULL DEFAULT 1,
    ADD COLUMN revision integer NOT NULL DEFAULT 1,
    ADD COLUMN supersedes_snapshot_id uuid REFERENCES report_snapshots(id),
    ADD COLUMN payroll_run_id uuid REFERENCES payroll_runs(id),
    ADD COLUMN revision_reason text,
    ADD COLUMN payload_hash varchar(64);
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY store_id, report_type, period_start, period_end
               ORDER BY generated_at, id
           ) AS calculated_revision
    FROM report_snapshots
)
UPDATE report_snapshots report
SET revision = ranked.calculated_revision
FROM ranked
WHERE ranked.id = report.id;


ALTER TABLE report_snapshots
    ADD CONSTRAINT report_snapshots_schema_version_check CHECK (schema_version > 0),
    ADD CONSTRAINT report_snapshots_revision_check CHECK (revision > 0),
    ADD CONSTRAINT report_snapshots_report_type_check
        CHECK (report_type IN ('MONTHLY', 'ANNUAL')),
    ADD CONSTRAINT report_snapshots_payload_hash_check
        CHECK (payload_hash IS NULL OR payload_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT report_snapshots_finalized_contract_check
        CHECK (status <> 'FINALIZED' OR (
            report_type IN ('MONTHLY', 'ANNUAL')
            AND period_type IN ('MONTH', 'YEAR')
            AND source_hash IS NOT NULL
            AND payload_hash IS NOT NULL
        )),
    ADD CONSTRAINT report_snapshots_monthly_payroll_check
        CHECK (status <> 'FINALIZED' OR (
            (report_type = 'MONTHLY' AND payroll_run_id IS NOT NULL)
            OR (report_type <> 'MONTHLY' AND payroll_run_id IS NULL)));

CREATE UNIQUE INDEX ux_report_snapshots_period_revision
    ON report_snapshots (store_id, report_type, period_start, period_end, revision);

CREATE UNIQUE INDEX ux_report_snapshots_payroll_run
    ON report_snapshots (payroll_run_id)
    WHERE payroll_run_id IS NOT NULL;

CREATE INDEX ix_report_snapshots_supersedes
    ON report_snapshots (supersedes_snapshot_id);

CREATE TABLE annual_report_months (
    annual_report_id uuid NOT NULL REFERENCES report_snapshots(id),
    monthly_report_id uuid NOT NULL REFERENCES report_snapshots(id),
    month_number smallint NOT NULL CHECK (month_number BETWEEN 1 AND 12),
    PRIMARY KEY (annual_report_id, month_number),
    UNIQUE (annual_report_id, monthly_report_id)
);

CREATE INDEX ix_annual_report_months_monthly
    ON annual_report_months (monthly_report_id);


CREATE FUNCTION validate_finalized_report_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    source_store_id uuid;
    source_period_month date;
    source_status text;
    previous_store_id uuid;
    previous_report_type text;
    previous_period_start date;
    previous_period_end date;
    previous_revision integer;
    previous_status text;
BEGIN
    IF NEW.status <> 'FINALIZED' THEN
        RETURN NEW;
    END IF;

    IF NEW.source_hash IS NULL THEN
        RAISE EXCEPTION 'Finalized report source hash is required';
    END IF;

    IF NEW.report_type = 'MONTHLY' THEN
        IF NEW.period_type <> 'MONTH'
                OR NEW.period_start <> date_trunc('month', NEW.period_start)::date
                OR NEW.period_end <> (NEW.period_start + INTERVAL '1 month - 1 day')::date THEN
            RAISE EXCEPTION 'Monthly report period must be one complete calendar month';
        END IF;
        SELECT store_id, period_month, status
        INTO source_store_id, source_period_month, source_status
        FROM payroll_runs
        WHERE id = NEW.payroll_run_id;
        IF source_store_id IS DISTINCT FROM NEW.store_id
                OR source_period_month IS DISTINCT FROM NEW.period_start
                OR source_status IS DISTINCT FROM 'PAID' THEN
            RAISE EXCEPTION 'Monthly report requires the matching paid payroll revision';
        END IF;
    ELSE
        IF NEW.period_type <> 'YEAR'
                OR EXTRACT(YEAR FROM NEW.period_start) <> EXTRACT(YEAR FROM NEW.period_end)
                OR NEW.period_start <> date_trunc('month', NEW.period_start)::date
                OR NEW.period_end <> make_date(EXTRACT(YEAR FROM NEW.period_end)::integer, 12, 31)
                OR NEW.payroll_run_id IS NOT NULL THEN
            RAISE EXCEPTION 'Annual report period or payroll reference is invalid';
        END IF;
    END IF;

    IF NEW.revision = 1 THEN
        IF NEW.supersedes_snapshot_id IS NOT NULL OR NEW.revision_reason IS NOT NULL THEN
            RAISE EXCEPTION 'First report revision cannot supersede another revision';
        END IF;
    ELSE
        IF NEW.supersedes_snapshot_id IS NULL OR NEW.revision_reason IS NULL THEN
            RAISE EXCEPTION 'Later report revisions require provenance and a reason';
        END IF;
        SELECT store_id, report_type, period_start, period_end, revision, status
        INTO previous_store_id, previous_report_type, previous_period_start,
             previous_period_end, previous_revision, previous_status
        FROM report_snapshots
        WHERE id = NEW.supersedes_snapshot_id;
        IF previous_store_id IS DISTINCT FROM NEW.store_id
                OR previous_report_type IS DISTINCT FROM NEW.report_type
                OR previous_period_start IS DISTINCT FROM NEW.period_start
                OR previous_period_end IS DISTINCT FROM NEW.period_end
                OR previous_revision IS DISTINCT FROM NEW.revision - 1
                OR previous_status IS DISTINCT FROM 'FINALIZED' THEN
            RAISE EXCEPTION 'Superseded report revision is inconsistent';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_report_snapshots_finalized_contract
BEFORE INSERT OR UPDATE ON report_snapshots
FOR EACH ROW EXECUTE FUNCTION validate_finalized_report_snapshot();
CREATE FUNCTION prevent_finalized_report_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Finalized report snapshots cannot be deleted';
    END IF;
    IF OLD.status = 'FINALIZED' THEN
        RAISE EXCEPTION 'Finalized report snapshots cannot be updated';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_report_snapshots_immutable
BEFORE UPDATE OR DELETE ON report_snapshots
FOR EACH ROW EXECUTE FUNCTION prevent_finalized_report_change();


CREATE FUNCTION validate_annual_report_month_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    annual_store_id uuid;
    annual_type text;
    annual_status text;
    annual_start date;
    annual_end date;
    monthly_store_id uuid;
    monthly_type text;
    monthly_status text;
    monthly_start date;
    monthly_end date;
BEGIN
    SELECT store_id, report_type, status, period_start, period_end
    INTO annual_store_id, annual_type, annual_status, annual_start, annual_end
    FROM report_snapshots
    WHERE id = NEW.annual_report_id;
    SELECT store_id, report_type, status, period_start, period_end
    INTO monthly_store_id, monthly_type, monthly_status, monthly_start, monthly_end
    FROM report_snapshots
    WHERE id = NEW.monthly_report_id;

    IF annual_type IS DISTINCT FROM 'ANNUAL'
            OR annual_status IS DISTINCT FROM 'FINALIZED'
            OR monthly_type IS DISTINCT FROM 'MONTHLY'
            OR monthly_status IS DISTINCT FROM 'FINALIZED'
            OR annual_store_id IS DISTINCT FROM monthly_store_id THEN
        RAISE EXCEPTION 'Annual provenance requires finalized reports from one store';
    END IF;
    IF EXTRACT(MONTH FROM monthly_start)::smallint <> NEW.month_number
            OR monthly_start <> date_trunc('month', monthly_start)::date
            OR monthly_end <> (monthly_start + INTERVAL '1 month - 1 day')::date
            OR monthly_start < annual_start
            OR monthly_end > annual_end THEN
        RAISE EXCEPTION 'Annual provenance month does not match the source period';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_annual_report_months_contract
BEFORE INSERT ON annual_report_months
FOR EACH ROW EXECUTE FUNCTION validate_annual_report_month_insert();
CREATE FUNCTION prevent_annual_report_month_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Annual report month references are immutable';
END;
$$;

CREATE TRIGGER trg_annual_report_months_immutable
BEFORE UPDATE OR DELETE ON annual_report_months
FOR EACH ROW EXECUTE FUNCTION prevent_annual_report_month_change();

COMMENT ON COLUMN stores.reporting_started_on IS
    'First business date included in immutable reporting; permits a partial first calendar year.';
COMMENT ON TABLE report_snapshots IS
    'Immutable finalized monthly and annual report revisions. Dashboard calculations are not persisted.';
COMMENT ON TABLE annual_report_months IS
    'Immutable provenance links from an annual report revision to the exact monthly revisions used.';
