-- Customer-approved split of portable batteries from charging blocks and cables.
-- Thirty-seven exact LiveSklad codes from the 2026-09-24 product export, including
-- iPhone Air MagSafe Battery Pack (3527), with a name guard against reused codes.
-- Financial amounts, sale employees, and payroll assignments remain unchanged.

INSERT INTO analytics_categories (
    code, name, description, category_kind, device_family,
    counts_as_phone, counts_as_device, counts_as_additional_revenue,
    attach_denominator_code, requires_same_document_for_attach,
    payroll_category_code
) VALUES (
    'POWER_BANK', 'Пауэрбанки',
    'Портативные и внешние аккумуляторы, включая MagSafe Battery Pack',
    'ACCESSORY', 'NONE', false, false, true, 'PHONE', true, 'ACCESSORY'
);

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
      AND product.code IN (
            '3527', '32532', '4543', '69', '1936', '4125', '4126',
            '3603', '3605', '4971', '5282', '5280', '2893', '2894',
            '3227', '3226', '2891', '2892', '680', '1634', '4776',
            '4777', '4778', '1632', '2890', '2612', '2610', '591',
            '2607', '2609', '2608', '4124', '4123', '3439', '3442',
            '3443', '3441'
      )
      AND product.name ~* '(power[[:space:]]*bank|пауэрбанк|повербанк|magsafe[[:space:]]+battery[[:space:]]+pack|(портативн|внешн)[^[:space:]]*[[:space:]]+аккумулятор)'
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    condition_type = 'NOT_APPLICABLE',
    rule_version = 'customer-approved-2026-09-27-power-bank-v1',
    change_reason = 'Customer-approved portable battery category'
FROM approved_products approved,
     analytics_categories target_category
WHERE assignment.product_id = approved.id
  AND (assignment.analytics_category_id <> target_category.id
       OR assignment.condition_type <> 'NOT_APPLICABLE')
  AND target_category.code = 'POWER_BANK';

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
      AND product.code IN (
            '3527', '32532', '4543', '69', '1936', '4125', '4126',
            '3603', '3605', '4971', '5282', '5280', '2893', '2894',
            '3227', '3226', '2891', '2892', '680', '1634', '4776',
            '4777', '4778', '1632', '2890', '2612', '2610', '591',
            '2607', '2609', '2608', '4124', '4123', '3439', '3442',
            '3443', '3441'
      )
      AND product.name ~* '(power[[:space:]]*bank|пауэрбанк|повербанк|magsafe[[:space:]]+battery[[:space:]]+pack|(портативн|внешн)[^[:space:]]*[[:space:]]+аккумулятор)'
)
INSERT INTO product_category_assignments (
    product_id, analytics_category_id, condition_type, assignment_source,
    rule_version, valid_from, change_reason
)
SELECT product.id, target_category.id, 'NOT_APPLICABLE', 'MANUAL',
       'customer-approved-2026-09-27-power-bank-v1',
       LEAST(product.created_at, COALESCE((
           SELECT MIN(document.occurred_at)
           FROM sales_document_items item
           JOIN sales_documents document ON document.id = item.sales_document_id
           WHERE item.product_id = product.id
       ), product.created_at)),
       'Customer-approved portable battery category'
FROM approved_products approved
JOIN products product ON product.id = approved.id
JOIN analytics_categories target_category ON target_category.code = 'POWER_BANK'
WHERE NOT EXISTS (
    SELECT 1 FROM product_category_assignments assignment
    WHERE assignment.product_id = product.id
);

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
      AND product.code IN (
            '3527', '32532', '4543', '69', '1936', '4125', '4126',
            '3603', '3605', '4971', '5282', '5280', '2893', '2894',
            '3227', '3226', '2891', '2892', '680', '1634', '4776',
            '4777', '4778', '1632', '2890', '2612', '2610', '591',
            '2607', '2609', '2608', '4124', '4123', '3439', '3442',
            '3443', '3441'
      )
      AND product.name ~* '(power[[:space:]]*bank|пауэрбанк|повербанк|magsafe[[:space:]]+battery[[:space:]]+pack|(портативн|внешн)[^[:space:]]*[[:space:]]+аккумулятор)'
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    condition_type_snapshot = 'NOT_APPLICABLE',
    classification_version = 'customer-approved-2026-09-27-power-bank-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved_products approved,
     analytics_categories target_category
WHERE item.product_id = approved.id
  AND (item.analytics_category_id <> target_category.id
       OR item.condition_type_snapshot <> 'NOT_APPLICABLE')
  AND target_category.code = 'POWER_BANK';

-- Power banks have their own phone-based rate; chargers retain their own numerator.
CREATE OR REPLACE VIEW attach_rate_metric_definitions_v3 AS
SELECT *
FROM (VALUES
    (1,  'CASE_APPLE_IPHONE',       'CASE_APPLE_IPHONE',       'IPHONE'),
    (2,  'CHARGER_CABLE',           'CHARGER_CABLE',           'PHONE'),
    (3,  'GLASS_IPHONE',            'GLASS_IPHONE',            'IPHONE'),
    (4,  'GLASS_CAMERA_IPHONE',     'GLASS_CAMERA_IPHONE',     'IPHONE'),
    (5,  'FILM_PHONE',              'FILM_PHONE',              'PHONE'),
    (6,  'SETUP_SERVICE',           'SETUP_SERVICE',           'PHONE'),
    (7,  'CASE_SAMSUNG',            'CASE_SAMSUNG',            'SAMSUNG'),
    (8,  'GLASS_SAMSUNG',           'GLASS_SAMSUNG',           'SAMSUNG'),
    (9,  'GLASS_CAMERA_SAMSUNG',    'GLASS_CAMERA_SAMSUNG',    'SAMSUNG'),
    (10, 'ACCESSORY_PODS_WATCH',    'ACCESSORY_PODS_WATCH',    'PODS_WATCH'),
    (11, 'ACCESSORY_IPAD',          'ACCESSORY_IPAD',          'IPAD_MAC'),
    (12, 'WARRANTY_GENERIC_USED',   'WARRANTY_GENERIC_USED',   'USED_DEVICE'),
    (13, 'WARRANTY_GENERIC_NEW',    'WARRANTY_GENERIC_NEW',    'NEW_DEVICE'),
    (14, 'PREMIUM_PROTECTION',      'PREMIUM_PROTECTION',      'NEW_DEVICE'),
    (15, 'POWER_BANK',              'POWER_BANK',              'PHONE')
) definition(sort_order, metric_code, numerator_category_code, denominator_code);

-- Include the separate power-bank numerator in both ordinary and all-item
-- projections, while leaving the legacy charger-specific adapter fallback intact.
CREATE OR REPLACE VIEW attach_rate_item_facts_v3 AS
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
    CASE classified.device_role
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
    END AS denominator_metric_codes,
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
    END AS classification_issue_code
FROM classified_items classified;

CREATE OR REPLACE VIEW attach_rate_ordinary_item_facts_v4 AS
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
            WHEN source.category_code = 'HEADPHONES_APPLE'
                 AND source.normalized_product_name ~ 'airpods'
                THEN 'AIRPODS'
            WHEN source.category_code IN (
                'HEADPHONES_APPLE', 'HEADPHONES_SAMSUNG', 'HEADPHONES_OTHER'
            ) THEN 'HEADPHONES'
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
    CASE classified.device_role
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
