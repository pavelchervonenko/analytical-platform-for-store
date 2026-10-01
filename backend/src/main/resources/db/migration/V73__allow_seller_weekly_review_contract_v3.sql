ALTER TABLE weekly_review_snapshots
    DROP CONSTRAINT weekly_review_snapshots_report_contract_version_check;

ALTER TABLE weekly_review_snapshots
    ADD CONSTRAINT ck_weekly_review_snapshot_contract_version
        CHECK (report_contract_version IN (2, 3)),
    ADD COLUMN report_scope text,
    ADD COLUMN source_identity_hash varchar(64);

ALTER TABLE weekly_review_snapshots
    ADD CONSTRAINT ck_weekly_review_snapshot_scope_identity
    CHECK ((
        (report_contract_version = 2
            AND report_scope IS NULL
            AND source_identity_hash IS NULL)
        OR
        (report_contract_version = 3
            AND report_scope = 'SELLERS'
            AND source_identity_hash ~ '^[a-f0-9]{64}$'
            AND jsonb_typeof(report_payload -> 'scope') = 'string'
            AND report_payload ->> 'scope' = report_scope
            AND jsonb_typeof(report_payload -> 'sourceIdentityHash') = 'string'
            AND report_payload ->> 'sourceIdentityHash' = source_identity_hash
            AND jsonb_typeof(report_payload #> '{membership,currentCohortHash}') = 'string'
            AND report_payload #>> '{membership,currentCohortHash}' ~ '^[a-f0-9]{64}$'
            AND jsonb_typeof(report_payload #> '{membership,previousCohortHash}') = 'string'
            AND report_payload #>> '{membership,previousCohortHash}' ~ '^[a-f0-9]{64}$'
            AND report_payload #>> '{membership,currentCohortHash}'
                = report_payload #>> '{membership,previousCohortHash}')
    ) IS TRUE);

COMMENT ON TABLE weekly_review_snapshots IS
    'Immutable versioned weekly reviews; v2 is legacy store-wide, v3 is seller-scoped.';
COMMENT ON COLUMN weekly_review_snapshots.report_scope IS
    'NULL for legacy v2; SELLERS for contract v3.';
COMMENT ON COLUMN weekly_review_snapshots.source_identity_hash IS
    'Semantic source identity for v3, excluding personal names and monetary values.';
