ALTER TABLE raw_record_versions
    ADD CONSTRAINT ck_raw_record_versions_payload_size
    CHECK (octet_length(payload::text) <= 16777216)
    NOT VALID;

ALTER TABLE raw_record_versions
    VALIDATE CONSTRAINT ck_raw_record_versions_payload_size;

COMMENT ON CONSTRAINT ck_raw_record_versions_payload_size
    ON raw_record_versions IS
    'Hard 16 MiB database safety ceiling; the application operational limit is lower by default.';
