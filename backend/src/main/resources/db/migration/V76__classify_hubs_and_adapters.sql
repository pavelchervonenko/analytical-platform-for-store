-- Customer-confirmed generic hubs and non-charging adapters stay in OTHER_ACCESSORY_PRODUCT.
-- The 20W power adapter (4775) is a charger. Exact codes and name guards bound
-- historical product and sale/return corrections to the approved LiveSklad export.
-- Amounts, employees, source kinds and explicit payroll assignments are untouched.

WITH approved_products AS (
    SELECT product.id, approved.target_code
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    JOIN (VALUES
        ('6057', '^адаптер[[:space:]]+vlp.*usb-c[[:space:]]+hub', 'OTHER_ACCESSORY_PRODUCT'),
        ('3242', '^переходник[[:space:]]+baseus.*7-port[[:space:]]+hub', 'OTHER_ACCESSORY_PRODUCT'),
        ('3301', '^евро-переходник$', 'OTHER_ACCESSORY_PRODUCT'),
        ('4973', '^переходник[[:space:]]+keephone[[:space:]]+universal[[:space:]]+travel$', 'OTHER_ACCESSORY_PRODUCT'),
        ('4779', '^сетевой[[:space:]]+переходник[[:space:]]+merkan$', 'OTHER_ACCESSORY_PRODUCT'),
        ('44', '^lightning[[:space:]]+3.5[[:space:]]+aux[[:space:]]+audio$', 'OTHER_ACCESSORY_PRODUCT'),
        ('4775', '^переходник[[:space:]]+сзу.*20w.*power[[:space:]]+adapter', 'CHARGER_CABLE')
    ) approved(code, name_pattern, target_code)
      ON approved.code = product.code
     AND product.name ~* approved.name_pattern
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    condition_type = 'NOT_APPLICABLE',
    rule_version = 'customer-approved-2026-09-27-adapters-v1',
    change_reason = 'Customer-confirmed generic adapter or charging adapter'
FROM approved_products approved,
     analytics_categories target_category
WHERE assignment.product_id = approved.id
  AND target_category.code = approved.target_code
  AND (assignment.analytics_category_id <> target_category.id
       OR assignment.condition_type <> 'NOT_APPLICABLE');

WITH approved_products AS (
    SELECT product.id, approved.target_code
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    JOIN (VALUES
        ('6057', '^адаптер[[:space:]]+vlp.*usb-c[[:space:]]+hub', 'OTHER_ACCESSORY_PRODUCT'),
        ('3242', '^переходник[[:space:]]+baseus.*7-port[[:space:]]+hub', 'OTHER_ACCESSORY_PRODUCT'),
        ('3301', '^евро-переходник$', 'OTHER_ACCESSORY_PRODUCT'),
        ('4973', '^переходник[[:space:]]+keephone[[:space:]]+universal[[:space:]]+travel$', 'OTHER_ACCESSORY_PRODUCT'),
        ('4779', '^сетевой[[:space:]]+переходник[[:space:]]+merkan$', 'OTHER_ACCESSORY_PRODUCT'),
        ('44', '^lightning[[:space:]]+3.5[[:space:]]+aux[[:space:]]+audio$', 'OTHER_ACCESSORY_PRODUCT'),
        ('4775', '^переходник[[:space:]]+сзу.*20w.*power[[:space:]]+adapter', 'CHARGER_CABLE')
    ) approved(code, name_pattern, target_code)
      ON approved.code = product.code
     AND product.name ~* approved.name_pattern
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
)
INSERT INTO product_category_assignments (
    product_id, analytics_category_id, condition_type, assignment_source,
    rule_version, valid_from, change_reason
)
SELECT product.id, target_category.id, 'NOT_APPLICABLE', 'MANUAL',
       'customer-approved-2026-09-27-adapters-v1',
       LEAST(product.created_at, COALESCE((
           SELECT MIN(document.occurred_at)
           FROM sales_document_items item
           JOIN sales_documents document ON document.id = item.sales_document_id
           WHERE item.product_id = product.id
       ), product.created_at)),
       'Customer-confirmed generic adapter or charging adapter'
FROM approved_products approved
JOIN products product ON product.id = approved.id
JOIN analytics_categories target_category ON target_category.code = approved.target_code
WHERE NOT EXISTS (
    SELECT 1 FROM product_category_assignments assignment
    WHERE assignment.product_id = product.id
);

WITH approved_products AS (
    SELECT product.id, approved.target_code
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    JOIN (VALUES
        ('6057', '^адаптер[[:space:]]+vlp.*usb-c[[:space:]]+hub', 'OTHER_ACCESSORY_PRODUCT'),
        ('3242', '^переходник[[:space:]]+baseus.*7-port[[:space:]]+hub', 'OTHER_ACCESSORY_PRODUCT'),
        ('3301', '^евро-переходник$', 'OTHER_ACCESSORY_PRODUCT'),
        ('4973', '^переходник[[:space:]]+keephone[[:space:]]+universal[[:space:]]+travel$', 'OTHER_ACCESSORY_PRODUCT'),
        ('4779', '^сетевой[[:space:]]+переходник[[:space:]]+merkan$', 'OTHER_ACCESSORY_PRODUCT'),
        ('44', '^lightning[[:space:]]+3.5[[:space:]]+aux[[:space:]]+audio$', 'OTHER_ACCESSORY_PRODUCT'),
        ('4775', '^переходник[[:space:]]+сзу.*20w.*power[[:space:]]+adapter', 'CHARGER_CABLE')
    ) approved(code, name_pattern, target_code)
      ON approved.code = product.code
     AND product.name ~* approved.name_pattern
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    condition_type_snapshot = 'NOT_APPLICABLE',
    classification_version = 'customer-approved-2026-09-27-adapters-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved_products approved,
     analytics_categories target_category
WHERE item.product_id = approved.id
  AND target_category.code = approved.target_code
  AND (item.analytics_category_id <> target_category.id
       OR item.condition_type_snapshot <> 'NOT_APPLICABLE');

-- v4 previously counted any OTHER_ACCESSORY_PRODUCT adapter mentioning USB,
-- Type-C, Lightning or HDMI as a charger. Those are connectivity signals, not
-- charging evidence. Keep the fallback only for explicit charging language.
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
                 AND source.normalized_product_name ~ '(заряд|питан|power[[:space:]]+adapter|wall[[:space:]]+charger|сзу|азу|бзу)'
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
