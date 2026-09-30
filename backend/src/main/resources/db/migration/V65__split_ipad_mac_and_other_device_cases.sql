-- Customer-approved iPad/Mac accessory split and explicit other-device cases.
-- Keep CASE_APPLE_IPHONE, CASE_SAMSUNG, ACCESSORY_PODS_WATCH metric codes unchanged.
-- Bounded corrections from the 2026-09-24 catalogue audit; financial/payroll data unchanged.

INSERT INTO analytics_categories (
    code, name, description, category_kind, device_family,
    counts_as_phone, counts_as_device, counts_as_additional_revenue,
    attach_denominator_code, requires_same_document_for_attach,
    payroll_category_code
) VALUES
    ('ACCESSORY_IPAD', 'Аксессуары iPad',
     'Аксессуары с явной совместимостью iPad или Apple Pencil',
     'ACCESSORY', 'IPAD_MAC', false, false, true, 'IPAD_MAC', true, 'ACCESSORY'),
    ('ACCESSORY_MAC', 'Аксессуары Mac',
     'Аксессуары с явной совместимостью MacBook или Mac',
     'ACCESSORY', 'IPAD_MAC', false, false, true, NULL, false, 'ACCESSORY'),
    ('CASE_OTHER_DEVICE', 'Чехлы других устройств',
     'Чехлы явно указанных устройств, кроме iPhone, Samsung, AirPods, iPad и Mac',
     'ACCESSORY', 'OTHER', false, false, true, NULL, false, 'ACCESSORY');

-- The code and name guard together prevent a cross-store or reused-code match.
-- Generic tablet accessories are not asserted to fit an iPad.
WITH approved_products AS (
    SELECT product.id,
           CASE
               WHEN product.code IN (
                   '2467', '2468', '2470', '4121', '4127', '4791', '4878',
                   '5040', '5309', '5842', '5844', '6061', '6064', '6175',
                   '2579', '3325', '3901', '3784'
               ) AND product.name ~* '(ipad|apple[[:space:]]+pencil)'
                   THEN 'ACCESSORY_IPAD'
               WHEN product.code IN ('4972', '2591', '2972', '2973')
                    AND product.name ~* '(macbook|макбук|magic[[:space:]]+mouse)'
                   THEN 'ACCESSORY_MAC'
               WHEN product.code IN ('5051', '5053', '5054')
                    AND product.name ~* 'чехол.*ноутбук'
                   THEN 'CASE_OTHER_DEVICE'
               WHEN product.code IN ('3628', '3629', '4792', '4793')
                    AND product.name ~* 'планшет'
                    AND product.name !~* 'ipad'
                   THEN 'OTHER_ACCESSORY_PRODUCT'
           END AS target_code
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind = 'PRODUCT'
      AND product.code IN (
          '2467', '2468', '2470', '4121', '4127', '4791', '4878',
          '5040', '5309', '5842', '5844', '6061', '6064', '6175',
                   '2579', '3325', '3901', '3784',
          '4972', '2591', '2972', '2973', '5051', '5053', '5054', '3628', '3629', '4792', '4793'
      )
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    condition_type = 'NOT_APPLICABLE',
    rule_version = 'customer-approved-2026-09-26-ipad-mac-cases-v1',
    change_reason = 'Customer-approved accessory target split'
FROM approved_products approved, analytics_categories old_category,
     analytics_categories target_category
WHERE assignment.product_id = approved.id
  AND approved.target_code IS NOT NULL
  AND assignment.analytics_category_id = old_category.id
  AND assignment.assignment_source <> 'MANUAL'
  AND old_category.code IN (
      'ACCESSORY_IPAD_MAC', 'OTHER_ACCESSORY_PRODUCT', 'IPAD_MAC'
  )
  AND target_category.code = approved.target_code
  AND assignment.analytics_category_id <> target_category.id;

WITH approved_products AS (
    SELECT product.id,
           CASE
               WHEN product.code IN (
                   '2467', '2468', '2470', '4121', '4127', '4791', '4878',
                   '5040', '5309', '5842', '5844', '6061', '6064', '6175',
                   '2579', '3325', '3901', '3784'
               ) AND product.name ~* '(ipad|apple[[:space:]]+pencil)'
                   THEN 'ACCESSORY_IPAD'
               WHEN product.code IN ('4972', '2591', '2972', '2973')
                    AND product.name ~* '(macbook|макбук|magic[[:space:]]+mouse)'
                   THEN 'ACCESSORY_MAC'
               WHEN product.code IN ('5051', '5053', '5054')
                    AND product.name ~* 'чехол.*ноутбук'
                   THEN 'CASE_OTHER_DEVICE'
           END AS target_code
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind = 'PRODUCT'
      AND product.code IN (
          '2467', '2468', '2470', '4121', '4127', '4791', '4878',
          '5040', '5309', '5842', '5844', '6061', '6064', '6175',
                   '2579', '3325', '3901', '3784',
          '4972', '2591', '2972', '2973', '5051', '5053', '5054'
      )
)
INSERT INTO product_category_assignments (
    product_id, analytics_category_id, condition_type, assignment_source,
    rule_version, valid_from, change_reason
)
SELECT product.id, target_category.id, 'NOT_APPLICABLE', 'MANUAL',
       'customer-approved-2026-09-26-ipad-mac-cases-v1',
       LEAST(product.created_at, COALESCE((
           SELECT MIN(document.occurred_at)
           FROM sales_document_items item
           JOIN sales_documents document ON document.id = item.sales_document_id
           WHERE item.product_id = product.id
       ), product.created_at)),
       'Customer-approved accessory target split'
FROM approved_products approved
JOIN products product ON product.id = approved.id
JOIN analytics_categories target_category ON target_category.code = approved.target_code
WHERE approved.target_code IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM product_category_assignments assignment
      WHERE assignment.product_id = product.id
  );

WITH approved_products AS (
    SELECT product.id,
           CASE
               WHEN product.code IN (
                   '2467', '2468', '2470', '4121', '4127', '4791', '4878',
                   '5040', '5309', '5842', '5844', '6061', '6064', '6175',
                   '2579', '3325', '3901', '3784'
               ) AND product.name ~* '(ipad|apple[[:space:]]+pencil)'
                   THEN 'ACCESSORY_IPAD'
               WHEN product.code IN ('4972', '2591', '2972', '2973')
                    AND product.name ~* '(macbook|макбук|magic[[:space:]]+mouse)'
                   THEN 'ACCESSORY_MAC'
               WHEN product.code IN ('5051', '5053', '5054')
                    AND product.name ~* 'чехол.*ноутбук'
                   THEN 'CASE_OTHER_DEVICE'
               WHEN product.code IN ('3628', '3629', '4792', '4793')
                    AND product.name ~* 'планшет'
                    AND product.name !~* 'ipad'
                   THEN 'OTHER_ACCESSORY_PRODUCT'
           END AS target_code
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind = 'PRODUCT'
      AND product.code IN (
          '2467', '2468', '2470', '4121', '4127', '4791', '4878',
          '5040', '5309', '5842', '5844', '6061', '6064', '6175',
                   '2579', '3325', '3901', '3784',
          '4972', '2591', '2972', '2973', '5051', '5053', '5054', '3628', '3629', '4792', '4793'
      )
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    condition_type_snapshot = 'NOT_APPLICABLE',
    classification_version = 'customer-approved-2026-09-26-ipad-mac-cases-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved_products approved, analytics_categories old_category,
     analytics_categories target_category
WHERE item.product_id = approved.id
  AND approved.target_code IS NOT NULL
  AND item.analytics_category_id = old_category.id
  AND old_category.code IN (
      'ACCESSORY_IPAD_MAC', 'OTHER_ACCESSORY_PRODUCT', 'IPAD_MAC', 'UNMAPPED'
  )
  AND target_category.code = approved.target_code
  AND item.analytics_category_id <> target_category.id
  AND NOT EXISTS (
      SELECT 1 FROM product_category_assignments manual
      WHERE manual.product_id = approved.id
        AND manual.assignment_source = 'MANUAL'
        AND manual.analytics_category_id <> target_category.id
  );

-- The eight previously device-classified Pencil, Mouse and Keyboard cards were
-- paid as TECH_TIER_2. Preserve
-- that payroll level until the separate customer-approved payroll review.
INSERT INTO product_payroll_category_assignments (
    product_id, payroll_category_code, valid_from, change_reason
)
SELECT product.id, 'TECH_TIER_2',
       LEAST(product.created_at::date, COALESCE((
           SELECT MIN(document.business_date)
           FROM sales_document_items item
           JOIN sales_documents document ON document.id = item.sales_document_id
           WHERE item.product_id = product.id
       ), product.created_at::date)),
       'Preserve prior peripheral payroll level during analytics split'
FROM products product
JOIN integration_connections connection ON connection.id = product.connection_id
WHERE connection.connection_key = 'livesklad-default'
  AND product.source_kind = 'PRODUCT'
  AND product.code IN ('6175', '2579', '3325', '3901',
                       '3784', '2591', '2972', '2973')
  AND product.name ~* '(apple[[:space:]]+pencil|magic[[:space:]]+(mouse|keyboard))'
  AND NOT EXISTS (
      SELECT 1 FROM product_payroll_category_assignments assignment
      WHERE assignment.product_id = product.id
  );


-- Preserve the existing metric codes and denominator methodology in both views.
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
            WHEN source.category_code = 'ACCESSORY_IPAD'
                THEN 'ACCESSORY_IPAD'
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
