-- Customer-approved analytics split of 25 LiveSklad headphone cards, 2026-09-26.
-- Payroll defaults are cloned from the former category, not revised.
-- Monetary amounts, source fields and immutable report revisions are unchanged.

INSERT INTO analytics_categories (
    code, name, description, category_kind, device_family,
    counts_as_phone, counts_as_device, counts_as_additional_revenue,
    attach_denominator_code, requires_same_document_for_attach,
    payroll_category_code
)
SELECT target.code, target.name, target.description,
       base.category_kind, base.device_family,
       base.counts_as_phone, base.counts_as_device,
       base.counts_as_additional_revenue, base.attach_denominator_code,
       base.requires_same_document_for_attach, base.payroll_category_code
FROM analytics_categories base
CROSS JOIN (VALUES
    ('HEADPHONES_APPLE', 'Наушники Apple', 'AirPods и EarPods'),
    ('HEADPHONES_SAMSUNG', 'Наушники Samsung', 'Samsung Galaxy Buds'),
    ('HEADPHONES_OTHER', 'Наушники других брендов',
     'Marshall, Sony, Яндекс и другие наушники')
) target(code, name, description)
WHERE base.code = 'PODS_WATCH_OTHER_DEVICE';

WITH approved_products AS (
    SELECT product.id, mapping.category_code
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    JOIN (VALUES
        ('1788', 'HEADPHONES_APPLE'),
        ('2061', 'HEADPHONES_APPLE'),
        ('4519', 'HEADPHONES_APPLE'),
        ('4166', 'HEADPHONES_APPLE'),
        ('3634', 'HEADPHONES_APPLE'),
        ('2359', 'HEADPHONES_APPLE'),
        ('2045', 'HEADPHONES_APPLE'),
        ('5537', 'HEADPHONES_APPLE'),
        ('3474', 'HEADPHONES_APPLE'),
        ('3571', 'HEADPHONES_SAMSUNG'),
        ('3572', 'HEADPHONES_SAMSUNG'),
        ('3783', 'HEADPHONES_SAMSUNG'),
        ('4162', 'HEADPHONES_SAMSUNG'),
        ('3636', 'HEADPHONES_OTHER'),
        ('3635', 'HEADPHONES_OTHER'),
        ('3827', 'HEADPHONES_OTHER'),
        ('4256', 'HEADPHONES_OTHER'),
        ('4409', 'HEADPHONES_OTHER'),
        ('4164', 'HEADPHONES_OTHER'),
        ('4163', 'HEADPHONES_OTHER'),
        ('4629', 'HEADPHONES_OTHER'),
        ('4630', 'HEADPHONES_OTHER'),
        ('701', 'HEADPHONES_OTHER'),
        ('1830', 'HEADPHONES_OTHER'),
        ('1883', 'HEADPHONES_OTHER')
    ) mapping(code, category_code) ON mapping.code = product.code
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind = 'PRODUCT'
      AND (
          mapping.category_code = 'HEADPHONES_APPLE'
              AND product.name ~* '(airpods|earpods)'
          OR mapping.category_code = 'HEADPHONES_SAMSUNG'
              AND product.name ~* 'galaxy[[:space:]]+buds'
          OR mapping.category_code = 'HEADPHONES_OTHER'
              AND product.name ~* '(marshall[[:space:]]+major|sony[[:space:]]+w[f-h]-|яндекс[[:space:]]+дропс|наушник)'
      )
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    rule_version = 'customer-approved-2026-09-26-headphones-v1',
    change_reason = 'Customer-approved headphone analytics category split'
FROM approved_products approved, analytics_categories old_category,
     analytics_categories target_category
WHERE assignment.product_id = approved.id
  AND assignment.analytics_category_id = old_category.id
  AND old_category.code IN ('PODS_WATCH_OTHER_DEVICE', 'SAMSUNG_NEW', 'UNMAPPED')
  AND target_category.code = approved.category_code;

WITH approved_products AS (
    SELECT product.id, mapping.category_code
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    JOIN (VALUES
        ('1788', 'HEADPHONES_APPLE'),
        ('2061', 'HEADPHONES_APPLE'),
        ('4519', 'HEADPHONES_APPLE'),
        ('4166', 'HEADPHONES_APPLE'),
        ('3634', 'HEADPHONES_APPLE'),
        ('2359', 'HEADPHONES_APPLE'),
        ('2045', 'HEADPHONES_APPLE'),
        ('5537', 'HEADPHONES_APPLE'),
        ('3474', 'HEADPHONES_APPLE'),
        ('3571', 'HEADPHONES_SAMSUNG'),
        ('3572', 'HEADPHONES_SAMSUNG'),
        ('3783', 'HEADPHONES_SAMSUNG'),
        ('4162', 'HEADPHONES_SAMSUNG'),
        ('3636', 'HEADPHONES_OTHER'),
        ('3635', 'HEADPHONES_OTHER'),
        ('3827', 'HEADPHONES_OTHER'),
        ('4256', 'HEADPHONES_OTHER'),
        ('4409', 'HEADPHONES_OTHER'),
        ('4164', 'HEADPHONES_OTHER'),
        ('4163', 'HEADPHONES_OTHER'),
        ('4629', 'HEADPHONES_OTHER'),
        ('4630', 'HEADPHONES_OTHER'),
        ('701', 'HEADPHONES_OTHER'),
        ('1830', 'HEADPHONES_OTHER'),
        ('1883', 'HEADPHONES_OTHER')
    ) mapping(code, category_code) ON mapping.code = product.code
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind = 'PRODUCT'
      AND (
          mapping.category_code = 'HEADPHONES_APPLE'
              AND product.name ~* '(airpods|earpods)'
          OR mapping.category_code = 'HEADPHONES_SAMSUNG'
              AND product.name ~* 'galaxy[[:space:]]+buds'
          OR mapping.category_code = 'HEADPHONES_OTHER'
              AND product.name ~* '(marshall[[:space:]]+major|sony[[:space:]]+w[f-h]-|яндекс[[:space:]]+дропс|наушник)'
      )
)
INSERT INTO product_category_assignments (
    product_id, analytics_category_id, condition_type, assignment_source,
    rule_version, valid_from, change_reason
)
SELECT product.id, target_category.id, 'NEW', 'MANUAL',
       'customer-approved-2026-09-26-headphones-v1',
       LEAST(product.created_at, COALESCE((
           SELECT MIN(document.occurred_at)
           FROM sales_document_items item
           JOIN sales_documents document ON document.id = item.sales_document_id
           WHERE item.product_id = product.id
       ), product.created_at)),
       'Customer-approved headphone analytics category split'
FROM approved_products approved
JOIN products product ON product.id = approved.id
JOIN analytics_categories target_category ON target_category.code = approved.category_code
WHERE NOT EXISTS (
    SELECT 1 FROM product_category_assignments assignment
    WHERE assignment.product_id = product.id
);

WITH approved_products AS (
    SELECT product.id, mapping.category_code
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    JOIN (VALUES
        ('1788', 'HEADPHONES_APPLE'),
        ('2061', 'HEADPHONES_APPLE'),
        ('4519', 'HEADPHONES_APPLE'),
        ('4166', 'HEADPHONES_APPLE'),
        ('3634', 'HEADPHONES_APPLE'),
        ('2359', 'HEADPHONES_APPLE'),
        ('2045', 'HEADPHONES_APPLE'),
        ('5537', 'HEADPHONES_APPLE'),
        ('3474', 'HEADPHONES_APPLE'),
        ('3571', 'HEADPHONES_SAMSUNG'),
        ('3572', 'HEADPHONES_SAMSUNG'),
        ('3783', 'HEADPHONES_SAMSUNG'),
        ('4162', 'HEADPHONES_SAMSUNG'),
        ('3636', 'HEADPHONES_OTHER'),
        ('3635', 'HEADPHONES_OTHER'),
        ('3827', 'HEADPHONES_OTHER'),
        ('4256', 'HEADPHONES_OTHER'),
        ('4409', 'HEADPHONES_OTHER'),
        ('4164', 'HEADPHONES_OTHER'),
        ('4163', 'HEADPHONES_OTHER'),
        ('4629', 'HEADPHONES_OTHER'),
        ('4630', 'HEADPHONES_OTHER'),
        ('701', 'HEADPHONES_OTHER'),
        ('1830', 'HEADPHONES_OTHER'),
        ('1883', 'HEADPHONES_OTHER')
    ) mapping(code, category_code) ON mapping.code = product.code
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind = 'PRODUCT'
      AND (
          mapping.category_code = 'HEADPHONES_APPLE'
              AND product.name ~* '(airpods|earpods)'
          OR mapping.category_code = 'HEADPHONES_SAMSUNG'
              AND product.name ~* 'galaxy[[:space:]]+buds'
          OR mapping.category_code = 'HEADPHONES_OTHER'
              AND product.name ~* '(marshall[[:space:]]+major|sony[[:space:]]+w[f-h]-|яндекс[[:space:]]+дропс|наушник)'
      )
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    classification_version = 'customer-approved-2026-09-26-headphones-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved_products approved, analytics_categories old_category,
     analytics_categories target_category
WHERE item.product_id = approved.id
  AND item.analytics_category_id = old_category.id
  AND old_category.code IN ('PODS_WATCH_OTHER_DEVICE', 'SAMSUNG_NEW', 'UNMAPPED')
  AND target_category.code = approved.category_code;

-- Preserve existing v3/v4 attach roles, including the pre-existing EarPods
-- distinction: v3 counts EarPods with AIRPODS; v4 counts them as HEADPHONES.
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
            WHEN source.category_code = 'ACCESSORY_IPAD_MAC'
                 AND source.normalized_product_name ~ '(ipad|планшет|apple pencil|pencil)'
                THEN 'ACCESSORY_IPAD'
            WHEN source.category_code IN (
                'CASE_APPLE_IPHONE',
                'CHARGER_CABLE',
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
            'CASE_APPLE_IPHONE', 'CHARGER_CABLE', 'GLASS_IPHONE',
            'GLASS_CAMERA_IPHONE', 'FILM_PHONE', 'SETUP_SERVICE',
            'WARRANTY_GENERIC_NEW', 'PREMIUM_PROTECTION'
        ]
        WHEN 'IPHONE_USED' THEN ARRAY[
            'CASE_APPLE_IPHONE', 'CHARGER_CABLE', 'GLASS_IPHONE',
            'GLASS_CAMERA_IPHONE', 'FILM_PHONE', 'SETUP_SERVICE',
            'WARRANTY_GENERIC_USED', 'PREMIUM_PROTECTION'
        ]
        WHEN 'SAMSUNG_NEW' THEN ARRAY[
            'CHARGER_CABLE', 'FILM_PHONE', 'SETUP_SERVICE', 'CASE_SAMSUNG',
            'GLASS_SAMSUNG', 'GLASS_CAMERA_SAMSUNG',
            'WARRANTY_GENERIC_NEW', 'PREMIUM_PROTECTION'
        ]
        WHEN 'SAMSUNG_USED' THEN ARRAY[
            'CHARGER_CABLE', 'FILM_PHONE', 'SETUP_SERVICE', 'CASE_SAMSUNG',
            'GLASS_SAMSUNG', 'GLASS_CAMERA_SAMSUNG',
            'WARRANTY_GENERIC_USED', 'PREMIUM_PROTECTION'
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
