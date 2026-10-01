-- Prospective product compatibility only. No category, sale, metric or payroll rewrites.
CREATE FUNCTION catalog_compatibility_targets_valid(values_ text[], coverage_ text)
RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
    SELECT values_ IS NOT NULL AND coalesce(array_ndims(values_), 1) = 1
      AND array_position(values_, NULL) IS NULL
      AND cardinality(values_) = (SELECT count(DISTINCT value) FROM unnest(values_) value)
      AND values_ <@ ARRAY[
        'IPHONE','SAMSUNG_PHONE','OTHER_PHONE','PHONE_GENERIC','PHONE_UNIVERSAL',
        'IPAD','OTHER_TABLET','TABLET_GENERIC','MACBOOK','OTHER_LAPTOP','LAPTOP_GENERIC',
        'APPLE_WATCH','SAMSUNG_WATCH','OTHER_WATCH','AIRPODS','APPLE_HEADPHONES',
        'SAMSUNG_HEADPHONES','OTHER_HEADPHONES','CONSOLE','SPEAKER','MICROPHONE',
        'FITNESS','SMART_GLASSES','CAMERA','HAIR_STYLER','OTHER_DEVICE','OTHER_NON_PHONE_DEVICE'
      ]::text[]
      AND CASE coverage_
        WHEN 'UNDETERMINED' THEN cardinality(values_) > 0
        WHEN 'EXCLUSIVE' THEN cardinality(values_) = 1 AND NOT 'PHONE_UNIVERSAL' = ANY(values_)
        WHEN 'MULTI_DEVICE' THEN cardinality(values_) >= 2 AND NOT 'PHONE_UNIVERSAL' = ANY(values_)
        WHEN 'UNIVERSAL_PHONE' THEN values_ = ARRAY['PHONE_UNIVERSAL']::text[]
        ELSE false END
$$;

CREATE TABLE catalog_compatibility_decisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES products(id),
    connection_id uuid NOT NULL REFERENCES integration_connections(id),
    connection_key_snapshot text NOT NULL,
    external_id_snapshot text NOT NULL,
    product_name_snapshot text NOT NULL,
    group_path_snapshot text,
    observation_fingerprint text NOT NULL CHECK (observation_fingerprint ~ '^[a-f0-9]{64}$'),
    revision bigint NOT NULL CHECK (revision > 0),
    action text NOT NULL CHECK (action IN ('CONFIRM','REVOKE')),
    coverage text NOT NULL CHECK (coverage IN ('UNDETERMINED','EXCLUSIVE','MULTI_DEVICE','UNIVERSAL_PHONE')),
    targets text[] NOT NULL,
    actor_id uuid NOT NULL REFERENCES app_users(id),
    reason text NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 1000),
    origin text NOT NULL CHECK (origin IN ('DIRECT_REVIEW','LEGACY_ADOPTION')),
    evidence_sha256 text,
    evidence_key text,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (product_id, revision),
    CHECK ((action = 'CONFIRM' AND catalog_compatibility_targets_valid(targets, coverage))
        OR (action = 'REVOKE' AND coverage = 'UNDETERMINED' AND cardinality(targets) = 0)),
    CHECK ((origin = 'DIRECT_REVIEW' AND evidence_sha256 IS NULL AND evidence_key IS NULL)
        OR (origin = 'LEGACY_ADOPTION' AND action = 'CONFIRM'
            AND coverage IN ('UNDETERMINED','UNIVERSAL_PHONE')
            AND evidence_sha256 IS NOT NULL AND evidence_sha256 ~ '^[a-f0-9]{64}$'
            AND evidence_key IS NOT NULL AND evidence_key ~ '^PRODUCT:[A-Za-z0-9._-]{1,100}$'))
);
CREATE INDEX ix_catalog_compatibility_effective
    ON catalog_compatibility_decisions(product_id, recorded_at DESC, revision DESC);

CREATE FUNCTION validate_catalog_compatibility_decision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    product_ products;
    previous_ catalog_compatibility_decisions;
    group_path_ text;
BEGIN
    SELECT * INTO product_ FROM products WHERE id = NEW.product_id FOR UPDATE;
    IF NOT FOUND OR NOT product_.is_active OR product_.source_system <> 'LIVESKLAD'
        OR product_.source_kind <> 'PRODUCT' OR product_.connection_id IS DISTINCT FROM NEW.connection_id
        OR product_.external_id <> NEW.external_id_snapshot OR product_.name <> NEW.product_name_snapshot THEN
        RAISE EXCEPTION 'Catalog identity or observation changed' USING ERRCODE = '23514';
    END IF;
    PERFORM 1 FROM integration_connections WHERE id = NEW.connection_id AND is_active
        AND connection_key = NEW.connection_key_snapshot FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Catalog connection is inactive' USING ERRCODE = '23514';
    END IF;
    IF product_.source_group_id IS NOT NULL THEN
        SELECT path INTO group_path_ FROM source_product_groups WHERE id = product_.source_group_id FOR SHARE;
    END IF;
    IF group_path_ IS DISTINCT FROM NEW.group_path_snapshot THEN
        RAISE EXCEPTION 'Catalog group changed' USING ERRCODE = '23514';
    END IF;
    IF NEW.origin = 'LEGACY_ADOPTION'
        AND NEW.evidence_key IS DISTINCT FROM ('PRODUCT:' || product_.code) THEN
        RAISE EXCEPTION 'Legacy evidence code does not match the product' USING ERRCODE = '23514';
    END IF;
    -- Internal command is administrator-only until full catalog-scope delegation is implemented.
    PERFORM 1 FROM app_users WHERE id = NEW.actor_id AND is_active AND role = 'ADMIN'
        AND NOT password_change_required FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Catalog actor is not authorized' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO previous_ FROM catalog_compatibility_decisions
        WHERE product_id = NEW.product_id ORDER BY revision DESC LIMIT 1;
    IF NEW.revision <> COALESCE(previous_.revision, 0) + 1 THEN
        RAISE EXCEPTION 'Catalog revision changed' USING ERRCODE = '23514';
    END IF;
    -- Never trust a supplied approval date. Adoption records when the operator adopts
    -- old evidence, not an invented date/author of its original approval.
    NEW.recorded_at := greatest(clock_timestamp(), previous_.recorded_at + interval '1 microsecond');
    RETURN NEW;
END;
$$;
CREATE TRIGGER catalog_compatibility_validate BEFORE INSERT ON catalog_compatibility_decisions
    FOR EACH ROW EXECUTE FUNCTION validate_catalog_compatibility_decision();

CREATE FUNCTION deny_catalog_compatibility_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Catalog compatibility history is immutable' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER catalog_compatibility_immutable BEFORE UPDATE OR DELETE ON catalog_compatibility_decisions
    FOR EACH ROW EXECUTE FUNCTION deny_catalog_compatibility_history_change();

CREATE VIEW catalog_compatibility_history AS
SELECT decision.*, lead(recorded_at) OVER (PARTITION BY product_id ORDER BY revision) AS valid_to
FROM catalog_compatibility_decisions decision;

COMMENT ON TABLE catalog_compatibility_decisions IS
    'Immutable prospective compatibility/adoption history; not a category assignment or sale decision.';
