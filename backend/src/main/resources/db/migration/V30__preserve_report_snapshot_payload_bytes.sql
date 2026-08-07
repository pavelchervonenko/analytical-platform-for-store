-- A finalized report is an immutable document whose SHA-256 covers the exact
-- UTF-8 payload returned by the application. PostgreSQL jsonb normalizes object
-- key order and whitespace, so it cannot preserve the bytes hashed before an
-- insert. Store the document as validated JSON text instead.

ALTER TABLE report_snapshots
    DISABLE TRIGGER trg_report_snapshots_immutable;

ALTER TABLE report_snapshots
    ALTER COLUMN payload TYPE text USING payload::text;

-- Existing hashes covered the pre-jsonb serialization, which is no longer
-- recoverable. Re-anchor them once to the semantically identical text produced
-- by jsonb; subsequent finalized rows remain immutable.
UPDATE report_snapshots
SET payload_hash = encode(
        digest(convert_to(payload, 'UTF8'), 'sha256'),
        'hex'
    )
WHERE payload_hash IS NOT NULL;

ALTER TABLE report_snapshots
    ENABLE TRIGGER trg_report_snapshots_immutable;

ALTER TABLE report_snapshots
    ADD CONSTRAINT report_snapshots_payload_json_object_check
        CHECK (jsonb_typeof(payload::jsonb) = 'object');

COMMENT ON COLUMN report_snapshots.payload IS
    'Exact immutable JSON document text; payload_hash is SHA-256 of its UTF-8 bytes.';
