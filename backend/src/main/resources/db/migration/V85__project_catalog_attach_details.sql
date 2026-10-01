-- Additive catalog detail projection. Existing v3/v4 views, payroll and sales stay unchanged.
-- One source row contains all applicable metric codes; summary is not a second source fact.
CREATE FUNCTION catalog_pods_watch_accessory_subtype(category_ text, name_ text)
RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN category_ IN ('ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH') THEN category_
        WHEN category_ = 'ACCESSORY_PODS_WATCH'
             AND lower(name_) !~ '(samsung|galaxy|buds|airtag)' THEN
            CASE
                WHEN lower(name_) ~ 'airpods' AND lower(name_) !~ '(apple watch|iwatch)'
                    THEN 'ACCESSORY_AIRPODS'
                WHEN lower(name_) ~ '(apple watch|iwatch)' AND lower(name_) !~ 'airpods'
                    THEN 'ACCESSORY_APPLE_WATCH'
            END
    END
$$;

CREATE VIEW attach_rate_metric_definitions_catalog AS
SELECT * FROM attach_rate_metric_definitions_v3
UNION ALL
SELECT * FROM (VALUES
    (16, 'ACCESSORY_AIRPODS', 'ACCESSORY_AIRPODS', 'AIRPODS'),
    (17, 'ACCESSORY_APPLE_WATCH', 'ACCESSORY_APPLE_WATCH', 'APPLE_WATCH')
) definition(sort_order,metric_code,numerator_category_code,denominator_code);

CREATE VIEW attach_rate_automatic_item_facts_v3_catalog AS
WITH source_items AS (
    SELECT
        document.store_id,
        document.business_date,
        document.employee_id,
        document.document_kind,
        item.quantity,
        lower(item.product_name_snapshot) AS normalized_product_name,
        item.condition_type_snapshot,
        category.code AS category_code,
        category.device_family,
        category.counts_as_phone,
        category.counts_as_device
    FROM sales_documents document
    JOIN sales_document_items item ON item.sales_document_id = document.id
    JOIN analytics_categories category ON category.id = item.analytics_category_id
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
            AND cardinality(accessory_review_allowed_targets(original_category.code)) > 0
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
                 AND source.normalized_product_name ~ '(airpods|earpods)'
                THEN 'AIRPODS'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~ '(apple watch|iwatch)'
                THEN 'APPLE_WATCH'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~ '(playstation|sony ps)'
                THEN 'PLAYSTATION'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~
                     '(наушник|headphone|earphone|galaxy buds|sony wf-|sony wh-|marshall major)'
                THEN 'HEADPHONES'
            WHEN source.category_code = 'HEADPHONES_APPLE'
                THEN 'AIRPODS'
            WHEN source.category_code IN ('HEADPHONES_SAMSUNG', 'HEADPHONES_OTHER')
                THEN 'HEADPHONES'
            WHEN source.category_code = 'TABLET_APPLE' THEN 'IPAD'
            WHEN source.category_code = 'LAPTOP_APPLE' THEN 'MACBOOK'
            WHEN source.category_code = 'WATCH_APPLE' THEN 'APPLE_WATCH'
            WHEN source.category_code = 'GAME_CONSOLES'
                 AND source.normalized_product_name ~ '(playstation|sony ps)' THEN 'PLAYSTATION'
            WHEN source.counts_as_device
                THEN 'OTHER_DEVICE'
        END AS device_role,
        CASE
            -- These three Care products are one Premium-service / Protection metric.
            WHEN source.normalized_product_name ~
                 '(privilege care|ultimate care|elite care)'
                THEN 'PREMIUM_PROTECTION'
            -- The catalogue has no explicit target-condition field for warranties.
            -- Check Discount variants are the semantically identifiable used-phone
            -- warranty family; the remaining Check variants are new-phone warranty.
            WHEN source.normalized_product_name ~ 'check[[:space:]]+dis(k|c)ount'
                THEN 'WARRANTY_GENERIC_USED'
            WHEN source.normalized_product_name ~ 'check'
                 AND source.category_code IN ('WARRANTY_GENERIC', 'PREMIUM_PROTECTION')
                THEN 'WARRANTY_GENERIC_NEW'
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
                'SETUP_SERVICE',
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
            'WARRANTY_GENERIC_NEW', 'PREMIUM_PROTECTION'
        ]
        WHEN 'IPHONE_USED' THEN ARRAY[
            'CASE_APPLE_IPHONE', 'CHARGER_CABLE', 'POWER_BANK', 'GLASS_IPHONE',
            'GLASS_CAMERA_IPHONE', 'FILM_PHONE', 'SETUP_SERVICE',
            'WARRANTY_GENERIC_USED', 'PREMIUM_PROTECTION'
        ]
        WHEN 'SAMSUNG_NEW' THEN ARRAY[
            'CHARGER_CABLE', 'POWER_BANK', 'FILM_PHONE', 'SETUP_SERVICE', 'CASE_SAMSUNG',
            'GLASS_SAMSUNG', 'GLASS_CAMERA_SAMSUNG',
            'WARRANTY_GENERIC_NEW', 'PREMIUM_PROTECTION'
        ]
        WHEN 'SAMSUNG_USED' THEN ARRAY[
            'CHARGER_CABLE', 'POWER_BANK', 'FILM_PHONE', 'SETUP_SERVICE', 'CASE_SAMSUNG',
            'GLASS_SAMSUNG', 'GLASS_CAMERA_SAMSUNG',
            'WARRANTY_GENERIC_USED', 'PREMIUM_PROTECTION'
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
        WHEN classified.category_code IN ('WARRANTY_GENERIC', 'PREMIUM_PROTECTION')
             AND classified.numerator_metric_code IS NULL
            THEN 'WARRANTY_TARGET_UNRESOLVED'
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
    END AS numerator_metric_codes
FROM classified_items classified;


CREATE VIEW attach_rate_ordinary_item_facts_v4_catalog AS
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
      AND NOT EXISTS (
          SELECT 1 FROM sales_document_items original_item
          JOIN sales_documents original ON original.id = original_item.sales_document_id
          JOIN analytics_categories original_category ON original_category.id = original_item.analytics_category_id
          WHERE document.document_kind = 'RETURN' AND original.document_kind = 'SALE'
            AND original_item.id = item.original_item_id AND original_item.product_id = item.product_id
            AND NOT original_item.is_deleted AND NOT original.is_deleted
            AND cardinality(accessory_review_allowed_targets(original_category.code)) > 0
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
            WHEN attach_is_care(source.normalized_product_name)
                THEN 'PREMIUM_PROTECTION'
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
    END AS numerator_metric_codes
FROM classified_items classified;


CREATE VIEW attach_rate_item_facts_v3_catalog AS
SELECT * FROM attach_rate_automatic_item_facts_v3_catalog
UNION ALL
SELECT f.*, array_remove(ARRAY[f.numerator_metric_code],NULL)
FROM case_attach_confirmed_facts_v3 f;

CREATE VIEW attach_rate_ordinary_item_facts_v4_catalog_with_reviews AS
SELECT * FROM attach_rate_ordinary_item_facts_v4_catalog
UNION ALL
SELECT f.*, array_remove(ARRAY[f.numerator_metric_code],NULL)
FROM case_attach_confirmed_facts f;

CREATE VIEW attach_rate_item_facts_v4_catalog AS
SELECT * FROM attach_rate_ordinary_item_facts_v4_catalog_with_reviews
UNION ALL
SELECT store_id,business_date,employee_id,net_quantity,
       'WARRANTY_GENERIC_' || device_type,NULL,ARRAY[]::text[],NULL,
       ARRAY['WARRANTY_GENERIC_' || device_type]
FROM warranty_attach_effective_allocations
UNION ALL
SELECT device.store_id,device.business_date,device.employee_id,
       device.quantity - COALESCE((SELECT sum(returned.quantity)
           FROM warranty_attach_items returned
           WHERE returned.original_item_id = device.id AND returned.active
             AND returned.document_kind = 'RETURN'),0),
       NULL,device.device_type,ARRAY['WARRANTY_GENERIC_' || device.device_type],NULL,ARRAY[]::text[]
FROM warranty_attach_items device
WHERE device.active AND device.document_kind = 'SALE' AND device.device_type IS NOT NULL;

COMMENT ON VIEW attach_rate_item_facts_v4_catalog IS
    'Catalog details on v4 attribution. AirPods/Watch leaves share one source row with their summary; unresolved legacy remains explicit.';
