-- Separate analytical attribution. Financial amounts and employee_id are unchanged.
ALTER TABLE sales_documents ADD COLUMN attach_source_employee_external_id text;

CREATE FUNCTION attach_is_care(value text) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT lower(trim(value)) ~
      '^(future[[:space:]]+store[[:space:]]+)?(privilege|ultimate|elite)[[:space:]_-]+care[+[:space:]]*$'
$$;

CREATE VIEW attach_rate_ordinary_item_facts_v4 AS
WITH source_items AS (
    SELECT
        document.store_id,
        document.business_date,
        CASE WHEN document.document_kind = 'SALE' THEN document.employee_id
             ELSE source_employee.id END AS employee_id,
        document.document_kind,
        item.quantity,
        lower(item.product_name_snapshot) AS normalized_product_name,
        item.condition_type_snapshot,
        category.code AS category_code,
        category.device_family,
        category.counts_as_phone,
        category.counts_as_device
    FROM sales_documents document
    LEFT JOIN employees source_employee
      ON source_employee.connection_id = document.connection_id
     AND source_employee.external_id = document.attach_source_employee_external_id
    JOIN sales_document_items item ON item.sales_document_id = document.id
    JOIN analytics_categories category ON category.id = item.analytics_category_id
    WHERE NOT document.is_deleted
      AND NOT item.is_deleted
      AND category.code <> 'EXCLUDE'
), classified_items AS (
    SELECT
        source.*,
        CASE
            WHEN source.category_code = 'IPHONE_NEW_ASIS'
                 AND source.condition_type_snapshot IN ('NEW', 'ASIS')
                THEN 'IPHONE_NEW_ASIS'
            WHEN source.category_code = 'IPHONE_USED'
                 AND source.condition_type_snapshot = 'USED'
                THEN 'IPHONE_USED'
            WHEN source.category_code = 'SAMSUNG_NEW'
                 AND source.condition_type_snapshot = 'NEW'
                THEN 'SAMSUNG_NEW'
            WHEN source.category_code = 'SAMSUNG_USED'
                 AND source.condition_type_snapshot = 'USED'
                THEN 'SAMSUNG_USED'
            WHEN source.category_code IN (
                'IPHONE_NEW_ASIS', 'IPHONE_USED', 'SAMSUNG_NEW', 'SAMSUNG_USED'
            ) THEN NULL
            WHEN source.counts_as_phone
                THEN 'OTHER_PHONE'
            WHEN source.category_code = 'IPAD_MAC'
                 AND source.normalized_product_name ~ '(ipad|планшет)'
                THEN 'IPAD'
            WHEN source.category_code = 'IPAD_MAC'
                 AND source.normalized_product_name ~ '(macbook|макбук)'
                THEN 'MACBOOK'
            WHEN source.category_code = 'IPAD_MAC'
                 AND source.normalized_product_name ~ '(imac|mac mini)'
                THEN 'MAC_OTHER'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~ 'airpods'
                THEN 'AIRPODS'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~ '(apple watch|iwatch)'
                THEN 'APPLE_WATCH'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~ '(playstation|sony ps)'
                THEN 'PLAYSTATION'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~
                     '(earpods|наушник|headphone|earphone|galaxy buds|sony wf-|sony wh-|marshall major)'
                THEN 'HEADPHONES'
            WHEN source.counts_as_device
                THEN 'OTHER_DEVICE'
        END AS device_role,
        CASE
            WHEN attach_is_care(source.normalized_product_name)
                THEN 'PREMIUM_PROTECTION'
            WHEN source.category_code = 'OTHER_ACCESSORY_PRODUCT'
                 AND source.normalized_product_name ~ '(переходник|адаптер)'
                 AND source.normalized_product_name ~ '(usb|type.?c|lightning|заряд|питан|hdmi)'
                THEN 'CHARGER_CABLE'
            WHEN source.category_code = 'SETUP_SERVICE'
                 AND source.normalized_product_name !~ '(ремонт|repair|замена|заменить)'
                 AND source.normalized_product_name ~
                     '(настрой|активац|office|калибров|очистк|чистка устройства|перенос|установ|уч[её]тн|обновление программ|перезагруз|подзаряд|сброс|защитного покрытия)'
                THEN 'SETUP_SERVICE'
            WHEN source.category_code = 'ACCESSORY_PODS_WATCH'
                 AND source.normalized_product_name !~ '(samsung|galaxy|buds|airtag)'
                THEN 'ACCESSORY_PODS_WATCH'
            WHEN source.category_code = 'ACCESSORY_IPAD_MAC'
                 AND source.normalized_product_name ~ '(ipad|планшет|apple pencil|pencil)'
                THEN 'ACCESSORY_IPAD'
            WHEN source.category_code IN (
                'CASE_APPLE_IPHONE',
                'CHARGER_CABLE',
                'GLASS_IPHONE',
                'GLASS_CAMERA_IPHONE',
                'FILM_PHONE',
                'CASE_SAMSUNG',
                'GLASS_SAMSUNG',
                'GLASS_CAMERA_SAMSUNG'
            ) THEN source.category_code
        END AS numerator_metric_code
    FROM source_items source
)
SELECT
    classified.store_id,
    classified.business_date,
    classified.employee_id,
    CASE classified.document_kind
        WHEN 'SALE' THEN classified.quantity
        ELSE -classified.quantity
    END AS net_quantity,
    classified.numerator_metric_code,
    classified.device_role,
    CASE classified.device_role
        WHEN 'IPHONE_NEW_ASIS' THEN ARRAY[
            'CASE_APPLE_IPHONE', 'CHARGER_CABLE', 'GLASS_IPHONE',
            'GLASS_CAMERA_IPHONE', 'FILM_PHONE', 'SETUP_SERVICE',
            'PREMIUM_PROTECTION'
        ]
        WHEN 'IPHONE_USED' THEN ARRAY[
            'CASE_APPLE_IPHONE', 'CHARGER_CABLE', 'GLASS_IPHONE',
            'GLASS_CAMERA_IPHONE', 'FILM_PHONE', 'SETUP_SERVICE',
            'PREMIUM_PROTECTION'
        ]
        WHEN 'SAMSUNG_NEW' THEN ARRAY[
            'CHARGER_CABLE', 'FILM_PHONE', 'SETUP_SERVICE', 'CASE_SAMSUNG',
            'GLASS_SAMSUNG', 'GLASS_CAMERA_SAMSUNG',
            'PREMIUM_PROTECTION'
        ]
        WHEN 'SAMSUNG_USED' THEN ARRAY[
            'CHARGER_CABLE', 'FILM_PHONE', 'SETUP_SERVICE', 'CASE_SAMSUNG',
            'GLASS_SAMSUNG', 'GLASS_CAMERA_SAMSUNG',
            'PREMIUM_PROTECTION'
        ]
        WHEN 'OTHER_PHONE' THEN ARRAY[
            'CHARGER_CABLE', 'FILM_PHONE', 'SETUP_SERVICE'
        ]
        WHEN 'IPAD' THEN ARRAY['ACCESSORY_IPAD', 'PREMIUM_PROTECTION']
        WHEN 'MACBOOK' THEN ARRAY['SETUP_SERVICE', 'PREMIUM_PROTECTION']
        WHEN 'MAC_OTHER' THEN ARRAY['PREMIUM_PROTECTION']
        WHEN 'AIRPODS' THEN ARRAY['ACCESSORY_PODS_WATCH', 'PREMIUM_PROTECTION']
        WHEN 'APPLE_WATCH' THEN ARRAY['ACCESSORY_PODS_WATCH', 'PREMIUM_PROTECTION']
        WHEN 'HEADPHONES' THEN ARRAY['PREMIUM_PROTECTION']
        WHEN 'PLAYSTATION' THEN ARRAY['SETUP_SERVICE', 'PREMIUM_PROTECTION']
        ELSE ARRAY[]::text[]
    END AS denominator_metric_codes,
    CASE
        WHEN classified.category_code = 'ACCESSORY_IPAD_MAC'
             AND classified.numerator_metric_code IS NULL
            THEN 'IPAD_ACCESSORY_TARGET_UNRESOLVED'
        WHEN (
            classified.category_code = 'IPHONE_NEW_ASIS'
            AND classified.condition_type_snapshot NOT IN ('NEW', 'ASIS')
        ) OR (
            classified.category_code = 'IPHONE_USED'
            AND classified.condition_type_snapshot <> 'USED'
        ) OR (
            classified.category_code = 'SAMSUNG_NEW'
            AND classified.condition_type_snapshot <> 'NEW'
        ) OR (
            classified.category_code = 'SAMSUNG_USED'
            AND classified.condition_type_snapshot <> 'USED'
        ) THEN 'DEVICE_CONDITION_UNKNOWN'
    END AS classification_issue_code
FROM classified_items classified;


CREATE TABLE warranty_attach_decisions (
    id uuid PRIMARY KEY,
    source_item_id uuid NOT NULL REFERENCES sales_document_items(id),
    revision bigint NOT NULL CHECK (revision > 0),
    action text NOT NULL CHECK (action IN ('ALLOCATE', 'EXCLUDE', 'DEFER')),
    source_fingerprint text NOT NULL,
    original_warranty_item_id uuid REFERENCES sales_document_items(id),
    actor_id uuid NOT NULL REFERENCES app_users(id),
    reason text NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_item_id, revision)
);
CREATE TABLE warranty_attach_allocations (
    decision_id uuid NOT NULL REFERENCES warranty_attach_decisions(id),
    device_item_id uuid NOT NULL REFERENCES sales_document_items(id),
    quantity numeric(19,3) NOT NULL CHECK (quantity > 0),
    target_fingerprint text NOT NULL,
    device_document_id uuid NOT NULL REFERENCES sales_documents(id),
    device_type text NOT NULL CHECK (device_type IN ('NEW','USED')),
    business_date date NOT NULL,
    employee_id uuid REFERENCES employees(id),
    PRIMARY KEY (decision_id, device_item_id)
);
CREATE INDEX ix_warranty_allocations_device ON warranty_attach_allocations(device_item_id);

CREATE FUNCTION deny_warranty_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Warranty attribution history is immutable' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER warranty_decisions_immutable BEFORE UPDATE OR DELETE ON warranty_attach_decisions
    FOR EACH ROW EXECUTE FUNCTION deny_warranty_history_change();
CREATE TRIGGER warranty_allocations_immutable BEFORE UPDATE OR DELETE ON warranty_attach_allocations
    FOR EACH ROW EXECUTE FUNCTION deny_warranty_history_change();

CREATE VIEW warranty_attach_items AS
SELECT i.id, i.sales_document_id AS document_id, i.original_item_id, i.product_id,
       d.connection_id, d.store_id, d.employee_id, d.business_date, d.document_kind,
       d.external_id AS document_external_id, d.document_number, i.external_id AS item_external_id,
       i.product_name_snapshot AS name, i.quantity,
       NOT (d.is_deleted OR i.is_deleted) AS active,
       c.counts_as_device AS is_device,
       c.code IN ('WARRANTY_GENERIC', 'PREMIUM_PROTECTION')
           AND NOT attach_is_care(i.product_name_snapshot) AS is_warranty,
       CASE WHEN c.code IN ('IPHONE_USED','SAMSUNG_USED') AND i.condition_type_snapshot = 'USED'
                THEN 'USED'
            WHEN c.code = 'IPHONE_NEW_ASIS' AND i.condition_type_snapshot IN ('NEW','ASIS')
                OR c.code = 'SAMSUNG_NEW' AND i.condition_type_snapshot = 'NEW' THEN 'NEW'
       END AS device_type,
       md5(jsonb_build_array(i.id, d.id, d.store_id, d.connection_id, d.employee_id,
           d.business_date, d.document_kind, i.original_item_id, i.product_id,
           i.quantity, d.is_deleted, i.is_deleted, c.code, i.condition_type_snapshot,
           attach_is_care(i.product_name_snapshot))::text) AS fingerprint
FROM sales_document_items i
JOIN sales_documents d ON d.id = i.sales_document_id
JOIN analytics_categories c ON c.id = i.analytics_category_id;

CREATE VIEW warranty_attach_document_context AS
SELECT document_id,
       md5(string_agg(fingerprint, ',' ORDER BY id)) AS fingerprint,
       count(*) FILTER (WHERE active AND is_device) AS device_count,
       count(*) FILTER (WHERE active AND device_type IS NOT NULL) AS eligible_device_count,
       count(DISTINCT device_type) FILTER (WHERE active) AS type_count,
       min(device_type) FILTER (WHERE active) AS device_type
FROM warranty_attach_items
GROUP BY document_id;

CREATE VIEW warranty_attach_latest_decisions AS
SELECT DISTINCT ON (source_item_id) * FROM warranty_attach_decisions
ORDER BY source_item_id, revision DESC;

CREATE VIEW warranty_attach_sources AS
SELECT source.*, latest.id AS decision_id, COALESCE(latest.revision, 0) AS revision,
       latest.action, latest.reason AS decision_reason,
       COALESCE(source.original_item_id, latest.original_warranty_item_id) AS warranty_original_id,
       md5(concat_ws(':', source.fingerprint, context.fingerprint,
           original.fingerprint, original_decision.id)) AS source_fingerprint,
       context.device_count, context.eligible_device_count,
       context.type_count, context.device_type AS document_device_type,
       CASE WHEN latest.id IS NULL THEN false ELSE
       latest.source_fingerprint = md5(concat_ws(':', source.fingerprint, context.fingerprint,
           original.fingerprint, original_decision.id))
       AND NOT EXISTS (
           SELECT 1 FROM warranty_attach_allocations a
           LEFT JOIN warranty_attach_items target ON target.id = a.device_item_id
           LEFT JOIN warranty_attach_document_context tc ON tc.document_id = target.document_id
           WHERE a.decision_id = latest.id AND (
               target.id IS NULL OR NOT target.active OR target.device_type IS NULL
               OR target.document_kind <> 'SALE' OR target.store_id <> source.store_id
               OR target.connection_id IS DISTINCT FROM source.connection_id
               OR a.target_fingerprint <> md5(concat_ws(':', target.fingerprint, tc.fingerprint))
           )
       ) END AS decision_valid
FROM warranty_attach_items source
JOIN warranty_attach_document_context context ON context.document_id = source.document_id
LEFT JOIN warranty_attach_latest_decisions latest ON latest.source_item_id = source.id
LEFT JOIN warranty_attach_items original
    ON original.id = COALESCE(source.original_item_id, latest.original_warranty_item_id)
LEFT JOIN warranty_attach_latest_decisions original_decision ON original_decision.source_item_id = original.id
WHERE source.is_warranty AND source.active;

CREATE VIEW warranty_attach_sale_allocations AS
SELECT source.id AS source_item_id, source.store_id, target.employee_id, target.business_date,
       target.device_type, target.document_id AS device_document_id,
       target.id AS device_item_id, a.quantity
FROM warranty_attach_sources source
JOIN warranty_attach_allocations a ON a.decision_id = source.decision_id
JOIN warranty_attach_items target ON target.id = a.device_item_id
WHERE source.document_kind = 'SALE' AND source.action = 'ALLOCATE' AND source.decision_valid
UNION ALL
SELECT source.id, source.store_id, source.employee_id, source.business_date,
       source.document_device_type, source.document_id, NULL::uuid, source.quantity
FROM warranty_attach_sources source
WHERE source.document_kind = 'SALE' AND source.decision_id IS NULL
  AND source.type_count = 1 AND source.device_count = source.eligible_device_count;

CREATE VIEW warranty_attach_return_allocations AS
WITH sale_allocations AS MATERIALIZED (
    SELECT * FROM warranty_attach_sale_allocations
), originals AS (
    SELECT source_item_id, count(*) AS allocation_count, sum(quantity) AS quantity
    FROM sale_allocations GROUP BY source_item_id
), totals AS (
    SELECT warranty_original_id, sum(quantity) AS returned_quantity
    FROM warranty_attach_sources
    WHERE document_kind = 'RETURN'
      AND NOT (COALESCE(decision_valid, false) AND action = 'EXCLUDE')
    GROUP BY warranty_original_id
)
SELECT source.id AS source_item_id, original.source_item_id AS original_warranty_item_id,
       source.store_id, original.employee_id, original.business_date, original.device_type,
       original.device_document_id, original.device_item_id,
       CASE WHEN stats.allocation_count = 1 THEN source.quantity ELSE original.quantity END AS quantity
FROM warranty_attach_sources source
JOIN sale_allocations original ON original.source_item_id = source.warranty_original_id
JOIN originals stats ON stats.source_item_id = original.source_item_id
JOIN totals ON totals.warranty_original_id = original.source_item_id
WHERE source.document_kind = 'RETURN' AND source.decision_id IS NULL
  AND source.store_id = original.store_id
  AND totals.returned_quantity <= stats.quantity
  AND (stats.allocation_count = 1 OR source.quantity = stats.quantity)
UNION ALL
SELECT source.id, source.warranty_original_id, source.store_id, target.employee_id,
       target.business_date, target.device_type, target.document_id, target.id, a.quantity
FROM warranty_attach_sources source
JOIN warranty_attach_allocations a ON a.decision_id = source.decision_id
JOIN warranty_attach_items target ON target.id = a.device_item_id
JOIN originals stats ON stats.source_item_id = source.warranty_original_id
JOIN totals ON totals.warranty_original_id = source.warranty_original_id
WHERE source.document_kind = 'RETURN' AND source.action = 'ALLOCATE' AND source.decision_valid
  AND totals.returned_quantity <= stats.quantity
  AND EXISTS (SELECT 1 FROM sale_allocations original
      WHERE original.source_item_id = source.warranty_original_id
        AND (original.device_item_id = target.id
             OR original.device_item_id IS NULL AND original.device_document_id = target.document_id));

CREATE VIEW warranty_attach_effective_allocations AS
SELECT source_item_id, store_id, employee_id, business_date, device_type,
       device_document_id, device_item_id, quantity AS net_quantity
FROM warranty_attach_sale_allocations
UNION ALL
SELECT source_item_id, store_id, employee_id, business_date, device_type,
       device_document_id, device_item_id, -quantity
FROM warranty_attach_return_allocations;

CREATE VIEW warranty_attach_cases AS
WITH resolved AS MATERIALIZED (
    SELECT DISTINCT source_item_id FROM warranty_attach_effective_allocations
), return_totals AS MATERIALIZED (
    SELECT warranty_original_id,
           sum(quantity) FILTER (WHERE NOT (COALESCE(decision_valid,false) AND action = 'EXCLUDE')) AS quantity,
           string_agg(concat_ws(':', fingerprint, decision_id), ',' ORDER BY id) AS fingerprint
    FROM warranty_attach_sources WHERE document_kind = 'RETURN'
    GROUP BY warranty_original_id
)
SELECT source.*,
       (SELECT full_name FROM employees WHERE id = source.employee_id) AS financial_employee_name,
       md5(concat_ws(':', source.source_fingerprint, source.decision_valid,
           original.decision_valid,
           (SELECT string_agg(concat_ws(':', t.fingerprint, tc.fingerprint), ',' ORDER BY t.id)
            FROM warranty_attach_allocations a
            JOIN warranty_attach_items t ON t.id = a.device_item_id
            JOIN warranty_attach_document_context tc ON tc.document_id = t.document_id
            WHERE a.decision_id = source.decision_id),
           return_totals.fingerprint)) AS review_fingerprint,
       CASE WHEN source.document_kind = 'SALE'
                THEN greatest(0, source.quantity - COALESCE(return_totals.quantity, 0))
            WHEN original.id IS NULL OR resolved_original.source_item_id IS NOT NULL
                OR return_totals.quantity > original.quantity
                THEN source.quantity ELSE 0 END AS pending_quantity,
       CASE
           WHEN source.decision_id IS NOT NULL AND NOT COALESCE(source.decision_valid, false) THEN 'CONFLICT'
           WHEN source.action = 'EXCLUDE' THEN 'EXCLUDED'
           WHEN source.action = 'DEFER' THEN 'DEFERRED'
           WHEN resolved.source_item_id IS NOT NULL
               THEN CASE WHEN source.decision_id IS NULL THEN 'RESOLVED_AUTO' ELSE 'RESOLVED_MANUAL' END
           WHEN source.document_kind = 'RETURN' AND original.action = 'EXCLUDE' AND original.decision_valid
               THEN 'EXCLUDED'
           ELSE 'CONFLICT'
       END AS state,
       CASE
           WHEN source.decision_id IS NOT NULL AND NOT COALESCE(source.decision_valid, false) THEN 'SOURCE_CHANGED'
           WHEN source.action IN ('EXCLUDE','DEFER') THEN source.action
           WHEN resolved.source_item_id IS NOT NULL THEN NULL
           WHEN source.document_kind = 'RETURN' AND original.action = 'EXCLUDE' AND original.decision_valid THEN NULL
           WHEN source.document_kind = 'RETURN' AND original.id IS NULL THEN 'ORIGINAL_WARRANTY_NOT_FOUND'
           WHEN source.document_kind = 'RETURN' THEN 'RETURN_ALLOCATION_REQUIRED'
           WHEN source.type_count > 1 THEN 'MIXED_DEVICE_TYPES'
           WHEN source.device_count > source.eligible_device_count THEN 'UNSUPPORTED_DEVICE'
           ELSE 'DEVICE_SALE_NOT_FOUND'
       END AS conflict_code
FROM warranty_attach_sources source
LEFT JOIN warranty_attach_sources original ON original.id = source.warranty_original_id
LEFT JOIN resolved ON resolved.source_item_id = source.id
LEFT JOIN resolved resolved_original ON resolved_original.source_item_id = source.warranty_original_id
LEFT JOIN return_totals ON return_totals.warranty_original_id = COALESCE(source.warranty_original_id, source.id);

CREATE VIEW attach_rate_item_facts_v4 AS
SELECT store_id, business_date, employee_id, net_quantity, numerator_metric_code,
       device_role, denominator_metric_codes, classification_issue_code
FROM attach_rate_ordinary_item_facts_v4
UNION ALL
SELECT store_id, business_date, employee_id, net_quantity,
       'WARRANTY_GENERIC_' || device_type, NULL, ARRAY[]::text[], NULL
FROM warranty_attach_effective_allocations
UNION ALL
SELECT device.store_id, device.business_date, device.employee_id,
       device.quantity - COALESCE((SELECT sum(returned.quantity) FROM warranty_attach_items returned
           WHERE returned.original_item_id = device.id AND returned.active
             AND returned.document_kind = 'RETURN'), 0),
       NULL, device.device_type, ARRAY['WARRANTY_GENERIC_' || device.device_type], NULL
FROM warranty_attach_items device
WHERE device.active AND device.document_kind = 'SALE' AND device.device_type IS NOT NULL;

-- Validate a complete decision at commit, after all of its allocation rows exist.
CREATE FUNCTION validate_warranty_decision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    decision warranty_attach_decisions;
    allocated numeric;
    source_quantity numeric;
BEGIN
    IF TG_TABLE_NAME = 'warranty_attach_decisions' THEN
        SELECT * INTO decision FROM warranty_attach_decisions WHERE id = NEW.id;
    ELSE
        SELECT * INTO decision FROM warranty_attach_decisions WHERE id = NEW.decision_id;
    END IF;
    SELECT quantity INTO source_quantity FROM sales_document_items WHERE id = decision.source_item_id;
    SELECT COALESCE(sum(quantity), 0) INTO allocated FROM warranty_attach_allocations
        WHERE decision_id = decision.id;
    IF (decision.action = 'ALLOCATE' AND allocated <> source_quantity)
       OR (decision.action <> 'ALLOCATE' AND allocated <> 0) THEN
        RAISE EXCEPTION 'Incomplete warranty allocation' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM warranty_attach_allocations a
        JOIN warranty_attach_items target ON target.id = a.device_item_id
        JOIN warranty_attach_items source ON source.id = decision.source_item_id
        WHERE a.decision_id = decision.id AND (
            source.store_id <> target.store_id OR source.connection_id IS DISTINCT FROM target.connection_id
            OR NOT target.active OR target.document_kind <> 'SALE' OR target.device_type IS NULL
            OR target.business_date > source.business_date
            OR a.device_document_id <> target.document_id OR a.device_type <> target.device_type
            OR a.business_date <> target.business_date OR a.employee_id IS DISTINCT FROM target.employee_id)
    ) THEN
        RAISE EXCEPTION 'Invalid warranty device allocation' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM warranty_attach_sources source
               WHERE source.id = decision.source_item_id AND source.decision_id = decision.id
                 AND NOT COALESCE(source.decision_valid, false)) THEN
        RAISE EXCEPTION 'Warranty source changed while saving' USING ERRCODE = '40001';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER warranty_decision_complete AFTER INSERT ON warranty_attach_decisions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_warranty_decision();
CREATE CONSTRAINT TRIGGER warranty_allocation_complete AFTER INSERT ON warranty_attach_allocations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_warranty_decision();

-- Durable invalidation survives process restarts; readers generate a new deterministic snapshot.
CREATE TABLE attach_attribution_changes (
    store_id uuid PRIMARY KEY REFERENCES stores(id),
    changed_at timestamptz NOT NULL
);
CREATE FUNCTION mark_warranty_attribution_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO attach_attribution_changes (store_id, changed_at)
    SELECT d.store_id, clock_timestamp() FROM sales_document_items i
    JOIN sales_documents d ON d.id = i.sales_document_id WHERE i.id = NEW.source_item_id
    ON CONFLICT (store_id) DO UPDATE SET changed_at = EXCLUDED.changed_at;
    RETURN NEW;
END;
$$;
CREATE TRIGGER warranty_attribution_changed AFTER INSERT ON warranty_attach_decisions
    FOR EACH ROW EXECUTE FUNCTION mark_warranty_attribution_changed();

CREATE TABLE attach_snapshot_checks (
    store_id uuid NOT NULL REFERENCES stores(id),
    snapshot_id uuid NOT NULL REFERENCES weekly_review_snapshots(id) ON DELETE CASCADE,
    checked_through timestamptz NOT NULL,
    PRIMARY KEY (store_id, snapshot_id)
);

CREATE VIEW warranty_attach_coverage AS
SELECT i.document_id, i.device_type, sum(i.quantity) AS sold,
       sum(COALESCE((SELECT sum(r.quantity) FROM warranty_attach_items r
                     WHERE r.original_item_id = i.id AND r.active AND r.document_kind = 'RETURN'), 0)) AS returned,
       COALESCE((SELECT sum(a.net_quantity) FROM warranty_attach_effective_allocations a
                 WHERE a.device_document_id = i.document_id AND a.device_type = i.device_type), 0) AS warranties
FROM warranty_attach_items i WHERE i.active AND i.document_kind = 'SALE' AND i.device_type IS NOT NULL
GROUP BY i.document_id, i.device_type;

CREATE VIEW warranty_attach_warning_sources AS
WITH mixed_products AS (
    SELECT i.store_id, i.product_id FROM warranty_attach_sale_allocations a
    JOIN warranty_attach_items i ON i.id = a.source_item_id
    GROUP BY i.store_id, i.product_id HAVING count(DISTINCT a.device_type) > 1
)
SELECT DISTINCT a.source_item_id
FROM warranty_attach_sale_allocations a
JOIN warranty_attach_items i ON i.id = a.source_item_id
JOIN warranty_attach_coverage d ON d.document_id = a.device_document_id AND d.device_type = a.device_type
WHERE d.warranties > d.sold OR d.warranties > greatest(0, d.sold - d.returned)
   OR d.returned > 0 AND d.warranties > 0 OR i.business_date > a.business_date
   OR EXISTS (SELECT 1 FROM mixed_products p WHERE p.store_id = i.store_id AND p.product_id = i.product_id);

-- Invalidate derived current reviews on material input changes, including recovery and late links.
CREATE FUNCTION mark_attach_input_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE changed_store uuid;
BEGIN
    IF TG_TABLE_NAME = 'sales_documents' THEN
        changed_store := NEW.store_id;
    ELSE
        SELECT store_id INTO changed_store FROM sales_documents WHERE id = NEW.sales_document_id;
    END IF;
    IF EXISTS (SELECT 1 FROM weekly_review_snapshots WHERE store_id = changed_store) THEN
        INSERT INTO attach_attribution_changes (store_id, changed_at) VALUES (changed_store, clock_timestamp())
        ON CONFLICT (store_id) DO UPDATE SET changed_at = EXCLUDED.changed_at;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER attach_item_inserted AFTER INSERT ON sales_document_items
    FOR EACH ROW EXECUTE FUNCTION mark_attach_input_changed();
CREATE TRIGGER attach_item_changed AFTER UPDATE ON sales_document_items
    FOR EACH ROW WHEN ((OLD.quantity, OLD.analytics_category_id, OLD.condition_type_snapshot,
        OLD.is_deleted, OLD.original_item_id, OLD.product_name_snapshot, OLD.product_id)
        IS DISTINCT FROM (NEW.quantity, NEW.analytics_category_id, NEW.condition_type_snapshot,
        NEW.is_deleted, NEW.original_item_id, NEW.product_name_snapshot, NEW.product_id))
    EXECUTE FUNCTION mark_attach_input_changed();
CREATE TRIGGER attach_document_changed AFTER UPDATE ON sales_documents
    FOR EACH ROW WHEN ((OLD.business_date, OLD.employee_id, OLD.is_deleted, OLD.attach_source_employee_external_id)
        IS DISTINCT FROM (NEW.business_date, NEW.employee_id, NEW.is_deleted, NEW.attach_source_employee_external_id))
    EXECUTE FUNCTION mark_attach_input_changed();
