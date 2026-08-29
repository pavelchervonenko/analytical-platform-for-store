CREATE TABLE weekly_review_ai_enrichments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id uuid NOT NULL REFERENCES weekly_review_snapshots(id),
    prompt_version text NOT NULL,
    content_schema_version integer NOT NULL
        CHECK (content_schema_version > 0),
    input_hash varchar(64) NOT NULL
        CHECK (input_hash ~ '^[a-f0-9]{64}$'),
    content_payload jsonb NOT NULL
        CHECK (jsonb_typeof(content_payload) = 'object'),
    content_hash varchar(64) NOT NULL
        CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    validated_at timestamptz NOT NULL,
    published_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (snapshot_id, prompt_version, content_schema_version),
    CHECK (published_at >= validated_at),
    CHECK (
        (content_payload ->> 'schemaVersion')::integer
            = content_schema_version
    )
);

CREATE INDEX ix_weekly_review_ai_enrichments_snapshot
    ON weekly_review_ai_enrichments (
        snapshot_id,
        prompt_version,
        content_schema_version
    );

CREATE FUNCTION prevent_weekly_review_ai_enrichment_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Weekly review AI enrichments are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_weekly_review_ai_enrichments_immutable
    BEFORE UPDATE OR DELETE ON weekly_review_ai_enrichments
    FOR EACH ROW EXECUTE FUNCTION prevent_weekly_review_ai_enrichment_change();

COMMENT ON TABLE weekly_review_ai_enrichments IS
    'Immutable validated wording linked to an exact deterministic V45 snapshot.';
