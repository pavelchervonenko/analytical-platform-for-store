ALTER TABLE raw_record_versions
    ADD COLUMN payload_policy_version integer NOT NULL DEFAULT 0;

ALTER TABLE raw_record_versions
    ADD CONSTRAINT ck_raw_record_versions_payload_policy_version
        CHECK (payload_policy_version BETWEEN 0 AND 1);

COMMENT ON COLUMN raw_record_versions.payload_policy_version IS
    '0 marks legacy full vendor JSON; 1 marks the explicit retained-field allowlist applied before hashing.';
