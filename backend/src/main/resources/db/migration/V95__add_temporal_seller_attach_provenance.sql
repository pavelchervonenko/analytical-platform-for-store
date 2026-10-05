-- Opt-in historical projection only; do not replace existing attach views or allocations.
-- The ordinary classifier is copied unchanged from the reviewed catalog v4 definition.
-- Published migrations and snapshots remain untouched.

CREATE VIEW seller_attach_ordinary_facts_v1 AS
WITH source_items AS (
    SELECT
        document.id AS membership_document_id,
        item.id AS source_item_id,
        document.occurred_at AS membership_at,
        document.store_id,
        document.business_date,
        COALESCE(original_document.occurred_at, document.occurred_at) AS classification_occurred_at,
        CASE WHEN document.document_kind = 'SALE' THEN document.employee_id
             ELSE source_employee.id END AS employee_id,
        document.document_kind,
        item.quantity,
        lower(item.product_name_snapshot) AS normalized_product_name,
        item.condition_type_snapshot,
        category.code AS category_code,
        category.device_family,
        category.counts_as_phone,
        category.counts_as_device,
        role_snapshot.state AS role_state,
        role_snapshot.policy_version AS role_policy,
        role_snapshot.outcome AS role_outcome,
        role_snapshot.role AS confirmed_role,
        CASE WHEN role_snapshot.item_id IS NOT NULL OR category.code IN
            ('OTHER_CASE','CASE_UNIVERSAL','GLASS_PHONE_UNRESOLVED','PROTECTIVE_FILM')
            THEN catalog_review_replaces_automatic(item.id) ELSE false END AS review_replaces_automatic,
        CASE WHEN role_snapshot.item_id IS NOT NULL OR category.code = 'PROTECTIVE_FILM'
            THEN catalog_role_pending_issue(item.id) END AS role_pending_issue
    FROM sales_documents document
    LEFT JOIN employees source_employee
      ON source_employee.connection_id = document.connection_id
     AND source_employee.source_system = 'LIVESKLAD'
     AND source_employee.external_id = document.attach_source_employee_external_id
    JOIN sales_document_items item ON item.sales_document_id = document.id
    LEFT JOIN sales_document_items original_item
      ON document.document_kind = 'RETURN' AND original_item.id = item.original_item_id
     AND original_item.product_id = item.product_id AND NOT original_item.is_deleted
    LEFT JOIN sales_documents original_document
      ON original_document.id = original_item.sales_document_id
     AND original_document.id = document.original_document_id
     AND original_document.document_kind = 'SALE' AND NOT original_document.is_deleted
     AND original_document.store_id = document.store_id
     AND original_document.connection_id = document.connection_id
     AND document.occurred_at >= original_document.occurred_at
    JOIN analytics_categories category ON category.id = item.analytics_category_id
    LEFT JOIN catalog_sale_role_snapshot_states role_snapshot ON role_snapshot.item_id = item.id
    WHERE NOT document.is_deleted
      AND NOT item.is_deleted
      AND category.code <> 'EXCLUDE'
      AND NOT EXISTS (
          SELECT 1 FROM sales_document_items original_item
          JOIN sales_documents original ON original.id = original_item.sales_document_id
          JOIN analytics_categories original_category ON original_category.id = original_item.analytics_category_id
          WHERE document.document_kind = 'RETURN' AND original.document_kind = 'SALE'
            AND original_item.id = item.original_item_id AND original_item.product_id = item.product_id
            AND NOT original_item.is_deleted AND NOT original.is_deleted
            AND catalog_review_replaces_automatic(original_item.id)
            AND original.store_id = document.store_id AND original.connection_id = document.connection_id
            AND document.original_document_id = original.id AND document.occurred_at >= original.occurred_at
      )
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
            WHEN source.category_code = 'HEADPHONES_APPLE'
                 AND source.normalized_product_name ~ 'airpods'
                THEN 'AIRPODS'
            WHEN source.category_code IN (
                'HEADPHONES_APPLE', 'HEADPHONES_SAMSUNG', 'HEADPHONES_OTHER'
            ) THEN 'HEADPHONES'
            WHEN source.category_code = 'TABLET_APPLE' THEN 'IPAD'
            WHEN source.category_code = 'LAPTOP_APPLE' THEN 'MACBOOK'
            WHEN source.category_code = 'WATCH_APPLE' THEN 'APPLE_WATCH'
            WHEN source.category_code = 'GAME_CONSOLES'
                 AND source.normalized_product_name ~ '(playstation|sony ps)' THEN 'PLAYSTATION'
            WHEN source.counts_as_device
                THEN 'OTHER_DEVICE'
        END AS device_role,
        CASE
            WHEN source.review_replaces_automatic THEN NULL
            WHEN catalog_attach_role_category(source.category_code)
                 AND source.role_state = 'CURRENT'
                 AND source.role_policy = 'catalog-accessory-roles-v1'
                 AND source.role_outcome = 'ASSIGNED' THEN source.confirmed_role
            WHEN catalog_attach_role_category(source.category_code)
                 AND source.role_state = 'CURRENT'
                 AND source.role_policy = 'catalog-accessory-roles-v1'
                 AND source.role_outcome = 'NO_CONTRIBUTION' THEN NULL
            WHEN catalog_attach_role_category(source.category_code)
                 AND (source.role_state = 'STALE'
                      OR source.role_policy <> 'catalog-accessory-roles-v1'
                      OR source.role_outcome IN ('REVIEW_PRODUCT','REVIEW_SALE')) THEN NULL
            WHEN attach_is_care(source.normalized_product_name)
                THEN 'PREMIUM_PROTECTION'
            WHEN catalog_charger_adapter_metric(source.category_code,
                    source.normalized_product_name, source.classification_occurred_at)
                THEN 'CHARGER_CABLE'
            WHEN source.category_code = 'SETUP_SERVICE'
                 AND source.normalized_product_name !~ '(ремонт|repair|замена|заменить)'
                 AND source.normalized_product_name ~
                     '(настрой|активац|office|калибров|очистк|чистка устройства|перенос|установ|уч[её]тн|обновление программ|перезагруз|подзаряд|сброс|защитного покрытия)'
                THEN 'SETUP_SERVICE'
            WHEN source.category_code = 'ACCESSORY_PODS_WATCH'
                 AND source.normalized_product_name !~ '(samsung|galaxy|buds|airtag)'
                THEN 'ACCESSORY_PODS_WATCH'
            WHEN source.category_code IN ('ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
                THEN source.category_code
            WHEN source.category_code = 'ACCESSORY_IPAD'
                THEN 'ACCESSORY_IPAD'
            WHEN source.category_code = 'ACCESSORY_IPAD_MAC'
                 AND source.normalized_product_name ~ '(ipad|планшет|apple pencil|pencil)'
                THEN 'ACCESSORY_IPAD'
            WHEN source.category_code IN (
                'CASE_APPLE_IPHONE',
                'CHARGER_CABLE', 'POWER_BANK',
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
    (CASE classified.device_role
        WHEN 'IPHONE_NEW_ASIS' THEN ARRAY[
            'CASE_APPLE_IPHONE', 'CHARGER_CABLE', 'POWER_BANK', 'GLASS_IPHONE',
            'GLASS_CAMERA_IPHONE', 'FILM_PHONE', 'SETUP_SERVICE',
            'PREMIUM_PROTECTION'
        ]
        WHEN 'IPHONE_USED' THEN ARRAY[
            'CASE_APPLE_IPHONE', 'CHARGER_CABLE', 'POWER_BANK', 'GLASS_IPHONE',
            'GLASS_CAMERA_IPHONE', 'FILM_PHONE', 'SETUP_SERVICE',
            'PREMIUM_PROTECTION'
        ]
        WHEN 'SAMSUNG_NEW' THEN ARRAY[
            'CHARGER_CABLE', 'POWER_BANK', 'FILM_PHONE', 'SETUP_SERVICE', 'CASE_SAMSUNG',
            'GLASS_SAMSUNG', 'GLASS_CAMERA_SAMSUNG',
            'PREMIUM_PROTECTION'
        ]
        WHEN 'SAMSUNG_USED' THEN ARRAY[
            'CHARGER_CABLE', 'POWER_BANK', 'FILM_PHONE', 'SETUP_SERVICE', 'CASE_SAMSUNG',
            'GLASS_SAMSUNG', 'GLASS_CAMERA_SAMSUNG',
            'PREMIUM_PROTECTION'
        ]
        WHEN 'OTHER_PHONE' THEN ARRAY[
            'CHARGER_CABLE', 'POWER_BANK', 'FILM_PHONE', 'SETUP_SERVICE'
        ]
        WHEN 'IPAD' THEN ARRAY['ACCESSORY_IPAD', 'PREMIUM_PROTECTION']
        WHEN 'MACBOOK' THEN ARRAY['SETUP_SERVICE', 'PREMIUM_PROTECTION']
        WHEN 'MAC_OTHER' THEN ARRAY['PREMIUM_PROTECTION']
        WHEN 'AIRPODS' THEN ARRAY['ACCESSORY_PODS_WATCH', 'PREMIUM_PROTECTION']
        WHEN 'APPLE_WATCH' THEN ARRAY['ACCESSORY_PODS_WATCH', 'PREMIUM_PROTECTION']
        WHEN 'HEADPHONES' THEN ARRAY['PREMIUM_PROTECTION']
        WHEN 'PLAYSTATION' THEN ARRAY['SETUP_SERVICE', 'PREMIUM_PROTECTION']
        ELSE ARRAY[]::text[]
    END || CASE
        WHEN classified.device_role = 'AIRPODS'
             AND classified.normalized_product_name ~ 'airpods' THEN ARRAY['ACCESSORY_AIRPODS']
        WHEN classified.device_role = 'APPLE_WATCH' THEN ARRAY['ACCESSORY_APPLE_WATCH']
        ELSE ARRAY[]::text[] END) AS denominator_metric_codes,
    CASE
        WHEN classified.role_pending_issue IS NOT NULL THEN classified.role_pending_issue
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
        WHEN classified.numerator_metric_code = 'ACCESSORY_PODS_WATCH'
             AND catalog_pods_watch_accessory_subtype(classified.category_code,
                 classified.normalized_product_name) IS NULL THEN 'PODS_WATCH_SUBTYPE_UNRESOLVED'
    END AS classification_issue_code,
    CASE
        WHEN classified.numerator_metric_code IN ('ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
            THEN ARRAY[classified.numerator_metric_code,'ACCESSORY_PODS_WATCH']
        WHEN classified.numerator_metric_code = 'ACCESSORY_PODS_WATCH'
            THEN array_remove(ARRAY['ACCESSORY_PODS_WATCH',
                catalog_pods_watch_accessory_subtype(classified.category_code,
                    classified.normalized_product_name)], NULL)
        ELSE array_remove(ARRAY[classified.numerator_metric_code], NULL)
    END AS numerator_metric_codes,
    classified.membership_document_id, classified.source_item_id,
    classified.membership_at, classified.document_kind AS membership_document_kind,
    'ORDINARY_OPERATION'::text AS membership_basis
FROM classified_items classified;

CREATE VIEW seller_attach_reviewed_facts_v1 AS
SELECT document.store_id, document.business_date,
       CASE WHEN document.document_kind = 'SALE' THEN document.employee_id
            ELSE source_employee.id END AS employee_id,
       CASE WHEN document.document_kind = 'SALE' THEN item.quantity
            ELSE -item.quantity END AS net_quantity,
       source.decision_target_code AS numerator_metric_code,
       NULL::text AS device_role,
       ARRAY[]::text[] AS denominator_metric_codes,
       NULL::text AS classification_issue_code,
       catalog_attach_numerator_metrics(source.decision_target_code) AS numerator_metric_codes,
       document.id AS membership_document_id, item.id AS source_item_id,
       document.occurred_at AS membership_at, document.document_kind AS membership_document_kind,
       'ORDINARY_OPERATION'::text AS membership_basis
FROM sales_documents document
JOIN sales_document_items item ON item.sales_document_id = document.id
JOIN analytics_categories item_category ON item_category.id = item.analytics_category_id
JOIN case_attach_review_items source
  ON source.source_item_id = CASE WHEN document.document_kind = 'SALE'
     THEN item.id ELSE item.original_item_id END
 AND source.store_id = document.store_id
JOIN sales_documents original_document ON original_document.id = source.document_id
 AND original_document.connection_id = document.connection_id
 AND (document.document_kind = 'SALE' OR document.original_document_id = original_document.id)
 AND document.occurred_at >= original_document.occurred_at
 AND item.product_id = source.product_id
LEFT JOIN employees source_employee
  ON source_employee.connection_id = document.connection_id
 AND source_employee.source_system = 'LIVESKLAD'
 AND source_employee.external_id = document.attach_source_employee_external_id
WHERE document.document_kind IN ('SALE', 'RETURN')
  AND item_category.code <> 'EXCLUDE'
  AND source.decision_current
  AND source.decision_target_code IN ('CASE_APPLE_IPHONE', 'CASE_SAMSUNG', 'GLASS_IPHONE', 'GLASS_SAMSUNG', 'FILM_PHONE',
      'CHARGER_CABLE','ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
  AND NOT document.is_deleted AND NOT item.is_deleted;

CREATE VIEW seller_attach_item_facts_v1 AS
SELECT * FROM seller_attach_ordinary_facts_v1
UNION ALL
SELECT * FROM seller_attach_reviewed_facts_v1
UNION ALL
SELECT d.store_id, d.business_date, source_employee.id, -i.quantity,
       NULL::text, NULL::text, ARRAY[]::text[], catalog_role_pending_issue(i.id), ARRAY[]::text[],
       d.id, i.id, d.occurred_at, d.document_kind, 'ORDINARY_OPERATION'::text
FROM sales_documents d
JOIN sales_document_items i ON i.sales_document_id = d.id
JOIN analytics_categories c ON c.id = i.analytics_category_id
LEFT JOIN employees source_employee ON source_employee.connection_id = d.connection_id
  AND source_employee.source_system = 'LIVESKLAD'
  AND source_employee.external_id = d.attach_source_employee_external_id
WHERE d.document_kind = 'RETURN' AND NOT d.is_deleted AND NOT i.is_deleted
  AND c.code <> 'EXCLUDE'
  AND catalog_review_replaces_automatic(catalog_exact_return_original(i.id))
  AND catalog_role_pending_issue(i.id) IS NOT NULL
UNION ALL
-- Preserve special warranty allocations exactly: the target SALE owns their base/time/author.
SELECT allocation.store_id, allocation.business_date, allocation.employee_id, allocation.net_quantity,
       'WARRANTY_GENERIC_' || allocation.device_type, NULL::text, ARRAY[]::text[], NULL::text,
       ARRAY['WARRANTY_GENERIC_' || allocation.device_type],
       target.id, allocation.source_item_id, target.occurred_at, target.document_kind,
       'WARRANTY_TARGET_SALE'::text
FROM warranty_attach_effective_allocations allocation
JOIN sales_documents target ON target.id = allocation.device_document_id
UNION ALL
SELECT device.store_id, device.business_date, device.employee_id,
       device.quantity - COALESCE((SELECT sum(returned.quantity)
           FROM warranty_attach_items returned
           WHERE returned.original_item_id = device.id AND returned.active
             AND returned.document_kind = 'RETURN'), 0),
       NULL::text, device.device_type, ARRAY['WARRANTY_GENERIC_' || device.device_type],
       NULL::text, ARRAY[]::text[],
       document.id, device.id, document.occurred_at, document.document_kind,
       'WARRANTY_TARGET_SALE'::text
FROM warranty_attach_items device
JOIN sales_documents document ON document.id = device.document_id
WHERE device.active AND device.document_kind = 'SALE' AND device.device_type IS NOT NULL;

COMMENT ON VIEW seller_attach_item_facts_v1 IS
    'Dormant temporal v4 facts with exact membership provenance. Ordinary returns use their own LiveSklad processor/time; warranty target allocations retain their existing rules. Current readers are unchanged.';
