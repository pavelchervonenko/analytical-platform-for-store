CREATE FUNCTION prevent_audit_log_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        AND (
            NEW.actor_user_id IS NOT DISTINCT FROM OLD.actor_user_id
            OR (OLD.actor_user_id IS NOT NULL AND NEW.actor_user_id IS NULL)
        )
        AND (
            NEW.store_id IS NOT DISTINCT FROM OLD.store_id
            OR (OLD.store_id IS NOT NULL AND NEW.store_id IS NULL)
        )
        AND (
            NEW.actor_user_id IS DISTINCT FROM OLD.actor_user_id
            OR NEW.store_id IS DISTINCT FROM OLD.store_id
        )
        AND NEW.id IS NOT DISTINCT FROM OLD.id
        AND NEW.action IS NOT DISTINCT FROM OLD.action
        AND NEW.entity_type IS NOT DISTINCT FROM OLD.entity_type
        AND NEW.entity_id IS NOT DISTINCT FROM OLD.entity_id
        AND NEW.ip_address IS NOT DISTINCT FROM OLD.ip_address
        AND NEW.user_agent IS NOT DISTINCT FROM OLD.user_agent
        AND NEW.metadata IS NOT DISTINCT FROM OLD.metadata
        AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at
    THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'audit log entries are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation();

ALTER TABLE audit_log
    DROP CONSTRAINT audit_log_store_id_fkey,
    ADD CONSTRAINT audit_log_store_id_fkey
        FOREIGN KEY (store_id) REFERENCES stores(id) ON DELETE SET NULL;

ALTER TABLE audit_log
    ADD CONSTRAINT ck_audit_log_metadata_size
    CHECK (octet_length(metadata::text) <= 32768);

COMMENT ON TABLE audit_log IS
    'Append-only application audit trail with safe versioned before/after summaries.';
