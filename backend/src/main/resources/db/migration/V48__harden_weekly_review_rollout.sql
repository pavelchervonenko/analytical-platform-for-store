ALTER TABLE weekly_review_snapshots
    ADD CONSTRAINT ck_weekly_review_snapshot_payload_header
    CHECK ((
        jsonb_typeof(report_payload -> 'contractVersion') = 'number'
        AND (report_payload ->> 'contractVersion')::integer
            = report_contract_version
        AND jsonb_typeof(report_payload -> 'reportState') = 'string'
        AND report_payload ->> 'reportState' = report_state
        AND jsonb_typeof(report_payload #> '{versions,metricsPolicy}') = 'string'
        AND report_payload #>> '{versions,metricsPolicy}' = metrics_policy_version
        AND jsonb_typeof(report_payload #> '{versions,snapshotPolicy}') = 'string'
        AND report_payload #>> '{versions,snapshotPolicy}' = snapshot_policy_version
        AND jsonb_typeof(report_payload #> '{versions,qualityPolicy}') = 'string'
        AND report_payload #>> '{versions,qualityPolicy}' = quality_policy_version
        AND jsonb_typeof(report_payload #> '{period,timezone}') = 'string'
        AND report_payload #>> '{period,timezone}' = timezone
        AND jsonb_typeof(report_payload #> '{period,current,start}') = 'string'
        AND (report_payload #>> '{period,current,start}')::date = period_start
        AND jsonb_typeof(report_payload #> '{period,current,end}') = 'string'
        AND (report_payload #>> '{period,current,end}')::date = period_end
        AND jsonb_typeof(
            report_payload #> '{provenance,snapshotPublicId}'
        ) = 'string'
        AND (report_payload #>> '{provenance,snapshotPublicId}')::uuid = id
        AND jsonb_typeof(
            report_payload #> '{provenance,revision}'
        ) = 'number'
        AND (report_payload #>> '{provenance,revision}')::integer = revision
    ) IS TRUE);

ALTER TABLE weekly_review_ai_enrichments
    ADD CONSTRAINT ck_weekly_review_ai_enrichment_schema
    CHECK ((
        jsonb_typeof(content_payload -> 'schemaVersion') = 'number'
        AND (content_payload ->> 'schemaVersion')::integer
            = content_schema_version
    ) IS TRUE);

ALTER TABLE weekly_review_ai_attempts
    ADD COLUMN provider_outcome text;

ALTER TABLE weekly_review_ai_attempts
    DISABLE TRIGGER tr_weekly_review_ai_attempts_final_immutable;

UPDATE weekly_review_ai_attempts
SET provider_outcome = CASE
    WHEN status = 'STARTED' THEN NULL
    WHEN response_payload IS NOT NULL THEN 'RESPONSE_RECEIVED'
    ELSE 'UNKNOWN'
END;

ALTER TABLE weekly_review_ai_attempts
    ENABLE TRIGGER tr_weekly_review_ai_attempts_final_immutable;

ALTER TABLE weekly_review_ai_attempts
    ADD CONSTRAINT ck_weekly_review_ai_attempt_provider_outcome
    CHECK (
        provider_outcome IN ('NOT_SENT', 'UNKNOWN', 'RESPONSE_RECEIVED')
    ),
    ADD CONSTRAINT ck_weekly_review_ai_attempt_outcome_lifecycle
    CHECK (
        (status = 'STARTED' AND provider_outcome IS NULL)
        OR
        (status <> 'STARTED' AND provider_outcome IS NOT NULL)
    );

COMMENT ON COLUMN weekly_review_ai_attempts.provider_outcome IS
    'Whether a failed request was not sent, has unknown outcome, or received a response.';
