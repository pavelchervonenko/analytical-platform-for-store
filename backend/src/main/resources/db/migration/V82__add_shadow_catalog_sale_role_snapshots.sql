-- Shadow evidence only: no changes to official metrics, monetary facts or payroll.
CREATE FUNCTION catalog_sale_role_fact(item_id_ uuid) RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT jsonb_build_object(
        'item', i.id, 'document', d.id, 'connection', d.connection_id, 'store', d.store_id,
        'itemExternal', i.external_id, 'documentExternal', d.external_id,
        'kind', d.document_kind, 'occurredEpoch', extract(epoch FROM d.occurred_at),
        'originalDocument', d.original_document_id, 'originalItem', i.original_item_id,
        'product', i.product_id, 'name', i.product_name_snapshot,
        'group', i.source_group_name_snapshot, 'category', c.code,
        'assignment', i.category_assignment_id, 'classificationVersion', i.classification_version,
        'condition', i.condition_type_snapshot, 'work', i.is_work)
    FROM sales_document_items i JOIN sales_documents d ON d.id = i.sales_document_id
    JOIN analytics_categories c ON c.id = i.analytics_category_id WHERE i.id = item_id_
$$;

CREATE TABLE catalog_sale_role_snapshots (
    item_id uuid PRIMARY KEY REFERENCES sales_document_items(id),
    fact_identity jsonb NOT NULL CHECK (jsonb_typeof(fact_identity) = 'object'),
    monetary_category text NOT NULL,
    policy_version text NOT NULL,
    registry_sha256 text NOT NULL CHECK (registry_sha256 ~ '^[a-f0-9]{64}$'),
    outcome text NOT NULL CHECK (outcome IN
        ('ASSIGNED','NO_CONTRIBUTION','REVIEW_PRODUCT','REVIEW_SALE','DEFER_TO_EXISTING')),
    role text CHECK (role IN ('ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH','FILM_PHONE')),
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 100),
    origin text NOT NULL CHECK (origin IN ('SALE_PROJECTION','ORIGINAL_SALE','LEGACY_RETURN')),
    original_snapshot_item_id uuid REFERENCES catalog_sale_role_snapshots(item_id),
    confirmation_id uuid REFERENCES catalog_compatibility_decisions(id),
    observation_fingerprint text CHECK (observation_fingerprint ~ '^[a-f0-9]{64}$'),
    observed_group_path text,
    observed_connection_key text,
    observed_external_id text,
    captured_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((outcome = 'ASSIGNED') = (role IS NOT NULL)),
    CHECK ((origin = 'ORIGINAL_SALE') = (original_snapshot_item_id IS NOT NULL)),
    CHECK (origin <> 'LEGACY_RETURN' OR
        (outcome = 'DEFER_TO_EXISTING' AND confirmation_id IS NULL AND observation_fingerprint IS NULL)),
    CHECK (origin <> 'SALE_PROJECTION' OR (observation_fingerprint IS NOT NULL
        AND observed_connection_key IS NOT NULL AND observed_external_id IS NOT NULL))
);

CREATE FUNCTION validate_catalog_sale_role_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    item_ sales_document_items;
    document_ sales_documents;
    original_item_ sales_document_items;
    original_document_ sales_documents;
    original_ catalog_sale_role_snapshots;
BEGIN
    SELECT * INTO item_ FROM sales_document_items WHERE id = NEW.item_id FOR SHARE;
    SELECT * INTO document_ FROM sales_documents WHERE id = item_.sales_document_id FOR SHARE;
    IF item_.id IS NULL OR item_.is_deleted OR document_.is_deleted
        OR NEW.fact_identity IS DISTINCT FROM catalog_sale_role_fact(NEW.item_id)
        OR NEW.monetary_category IS DISTINCT FROM (NEW.fact_identity ->> 'category') THEN
        RAISE EXCEPTION 'Sale role snapshot does not match its fact' USING ERRCODE = '23514';
    END IF;
    NEW.captured_at := clock_timestamp();
    IF document_.occurred_at > NEW.captured_at THEN
        RAISE EXCEPTION 'Future sale cannot have a current role snapshot' USING ERRCODE = '23514';
    END IF;
    IF NEW.origin = 'SALE_PROJECTION' THEN
        IF document_.document_kind <> 'SALE' OR item_.original_item_id IS NOT NULL THEN
            RAISE EXCEPTION 'Only a sale can be projected from catalog' USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM products p JOIN integration_connections c ON c.id = p.connection_id
            WHERE p.id = item_.product_id AND p.connection_id = document_.connection_id
              AND p.external_id = NEW.observed_external_id AND c.connection_key = NEW.observed_connection_key) THEN
            RAISE EXCEPTION 'Observed catalog identity differs from sale' USING ERRCODE = '23514';
        END IF;
        IF NEW.confirmation_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM catalog_compatibility_history h WHERE h.id = NEW.confirmation_id
              AND h.product_id = item_.product_id AND h.recorded_at <= document_.occurred_at
              AND (h.valid_to IS NULL OR document_.occurred_at < h.valid_to)) THEN
            RAISE EXCEPTION 'Confirmation does not belong to sale product and time' USING ERRCODE = '23514';
        END IF;
    ELSIF document_.document_kind <> 'RETURN' THEN
        RAISE EXCEPTION 'Only returns may inherit a snapshot' USING ERRCODE = '23514';
    END IF;
    IF NEW.origin = 'ORIGINAL_SALE' THEN
        SELECT * INTO original_ FROM catalog_sale_role_snapshots
            WHERE item_id = NEW.original_snapshot_item_id;
        SELECT * INTO original_item_ FROM sales_document_items WHERE id = original_.item_id FOR SHARE;
        SELECT * INTO original_document_ FROM sales_documents
            WHERE id = original_item_.sales_document_id FOR SHARE;
        IF original_.item_id IS NULL OR original_.origin <> 'SALE_PROJECTION'
            OR original_item_.is_deleted OR original_document_.is_deleted
            OR original_.fact_identity IS DISTINCT FROM catalog_sale_role_fact(original_.item_id)
            OR item_.original_item_id IS DISTINCT FROM original_.item_id
            OR item_.product_id IS DISTINCT FROM original_item_.product_id
            OR document_.original_document_id IS DISTINCT FROM original_document_.id
            OR document_.connection_id IS DISTINCT FROM original_document_.connection_id
            OR document_.store_id IS DISTINCT FROM original_document_.store_id
            OR document_.occurred_at < original_document_.occurred_at
            OR ROW(NEW.monetary_category,NEW.policy_version,NEW.registry_sha256,NEW.outcome,NEW.role,
                   NEW.reason,NEW.confirmation_id,NEW.observation_fingerprint,NEW.observed_group_path,
                   NEW.observed_connection_key,NEW.observed_external_id)
               IS DISTINCT FROM ROW(original_.monetary_category,original_.policy_version,
                   original_.registry_sha256,original_.outcome,original_.role,original_.reason,
                   original_.confirmation_id,original_.observation_fingerprint,original_.observed_group_path,
                   original_.observed_connection_key,original_.observed_external_id) THEN
            RAISE EXCEPTION 'Return snapshot must inherit its exact original sale' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER catalog_sale_role_validate BEFORE INSERT ON catalog_sale_role_snapshots
    FOR EACH ROW EXECUTE FUNCTION validate_catalog_sale_role_snapshot();
CREATE TRIGGER catalog_sale_role_immutable BEFORE UPDATE OR DELETE ON catalog_sale_role_snapshots
    FOR EACH ROW EXECUTE FUNCTION deny_catalog_compatibility_history_change();

CREATE VIEW catalog_sale_role_snapshot_states AS
SELECT s.*,
    CASE WHEN i.is_deleted OR d.is_deleted THEN 'DELETED'
         WHEN s.fact_identity IS DISTINCT FROM catalog_sale_role_fact(s.item_id) THEN 'STALE'
         WHEN s.origin = 'ORIGINAL_SALE' AND
             (o.fact_identity IS DISTINCT FROM catalog_sale_role_fact(o.item_id)
              OR oi.is_deleted OR od.is_deleted) THEN 'STALE'
         ELSE 'CURRENT' END AS state
FROM catalog_sale_role_snapshots s
JOIN sales_document_items i ON i.id = s.item_id
JOIN sales_documents d ON d.id = i.sales_document_id
LEFT JOIN catalog_sale_role_snapshots o ON o.item_id = s.original_snapshot_item_id
LEFT JOIN sales_document_items oi ON oi.id = o.item_id
LEFT JOIN sales_documents od ON od.id = oi.sales_document_id;

COMMENT ON TABLE catalog_sale_role_snapshots IS
    'Immutable shadow accessory-role evidence; not yet consumed by official metrics. No historical backfill.';
