CREATE FUNCTION prevent_product_source_identity_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    provisional_identity_claim boolean;
BEGIN
    provisional_identity_claim :=
        OLD.source_system = 'LIVESKLAD'
        AND NEW.source_system = 'LIVESKLAD'
        AND NEW.connection_id IS NOT DISTINCT FROM OLD.connection_id
        AND OLD.source_kind = 'UNKNOWN'
        AND NEW.source_kind IN ('PRODUCT', 'SERVICE')
        AND OLD.external_id = OLD.code
        AND NEW.code = OLD.code
        AND NULLIF(btrim(NEW.external_id), '') IS NOT NULL;

    IF NEW.connection_id IS DISTINCT FROM OLD.connection_id
            OR NEW.source_system IS DISTINCT FROM OLD.source_system
            OR (
                NEW.external_id IS DISTINCT FROM OLD.external_id
                AND NOT provisional_identity_claim
            ) THEN
        RAISE EXCEPTION 'source identity cannot be changed'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER tr_products_identity_immutable ON products;

CREATE TRIGGER tr_products_identity_immutable
    BEFORE UPDATE OF connection_id, source_system, external_id ON products
    FOR EACH ROW EXECUTE FUNCTION prevent_product_source_identity_change();

COMMENT ON FUNCTION prevent_product_source_identity_change() IS
    'Keeps product source identity immutable except for a one-time claim of a '
    'provisional LiveSklad catalog identity by an observed product or service.';
