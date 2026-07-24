ALTER TABLE payroll_runs
    ADD COLUMN source_fingerprint_version integer,
    ADD COLUMN source_sales_hash varchar(64),
    ADD COLUMN source_shifts_hash varchar(64),
    ADD COLUMN source_plan_hash varchar(64),
    ADD COLUMN source_classification_hash varchar(64),
    ADD COLUMN source_scheme_hash varchar(64);

ALTER TABLE payroll_runs
    ADD CONSTRAINT ck_payroll_runs_source_fingerprint
    CHECK (
        (source_fingerprint_version IS NULL
            AND source_sales_hash IS NULL
            AND source_shifts_hash IS NULL
            AND source_plan_hash IS NULL
            AND source_classification_hash IS NULL
            AND source_scheme_hash IS NULL)
        OR
        (source_fingerprint_version = 1
            AND source_sales_hash ~ '^[0-9a-f]{64}$'
            AND source_shifts_hash ~ '^[0-9a-f]{64}$'
            AND source_plan_hash ~ '^[0-9a-f]{64}$'
            AND source_classification_hash ~ '^[0-9a-f]{64}$'
            AND source_scheme_hash ~ '^[0-9a-f]{64}$')
    );
