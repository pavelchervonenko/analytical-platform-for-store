-- Structural preparation only. The migration runner records an explicitly supplied future boundary.
-- No product, sale, assignment or payroll row is changed by this migration.
CREATE TABLE catalog_classification_activation (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    activate_from timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    policy_version text NOT NULL DEFAULT 'catalog-prospective-v1'
        CHECK (policy_version = 'catalog-prospective-v1'),
    CHECK (activate_from >= recorded_at)
);

CREATE OR REPLACE FUNCTION catalog_charger_adapter_metric(category_ text, name_ text, occurred_at_ timestamptz)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT category_ = 'OTHER_ACCESSORY_PRODUCT'
       AND lower(COALESCE(name_, '')) ~ '(переходник|адаптер)'
       AND CASE WHEN occurred_at_ < COALESCE(
           (SELECT activate_from FROM catalog_classification_activation WHERE singleton),
           'infinity'::timestamptz
       ) THEN lower(COALESCE(name_, '')) ~ '(usb|type.?c|lightning|заряд|питан|hdmi)'
         ELSE lower(COALESCE(name_, '')) ~ '(заряд|питан|power[[:space:]]+adapter|wall[[:space:]]+charger|сзу|азу|бзу)'
       END
$$;

CREATE FUNCTION deny_catalog_activation_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Catalog activation boundary is immutable' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER catalog_activation_immutable BEFORE UPDATE OR DELETE ON catalog_classification_activation
    FOR EACH ROW EXECUTE FUNCTION deny_catalog_activation_change();
CREATE TRIGGER catalog_activation_no_truncate BEFORE TRUNCATE ON catalog_classification_activation
    FOR EACH STATEMENT EXECUTE FUNCTION deny_catalog_activation_change();

COMMENT ON TABLE catalog_classification_activation IS
    'Single immutable event-time boundary. Does not authorize historical reclassification or payroll changes.';
