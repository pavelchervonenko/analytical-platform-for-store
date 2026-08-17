-- Customer monetary KPI clarification 2026-08-13:
-- Apple Pencil, Magic Mouse, Magic Keyboard and PlayStation DualSense are
-- full devices for accessory/service revenue reporting. Magsafe Battery Pack
-- is a power bank and therefore an accessory.
--
-- This corrects the original classification from the reporting start, rather
-- than introducing a new mid-period rule. Finalized snapshots remain immutable;
-- normalized source facts and effective assignments are repaired in place so
-- dynamic KPI and future snapshots use the customer-confirmed meaning.

WITH monetary_classification_corrections (
    external_product_id,
    expected_category_code,
    target_category_code,
    target_condition_type
) AS (VALUES
    ('2579', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('2591', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('2972', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('2973', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('3325', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('3784', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('3901', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('2716', 'OTHER_ACCESSORY_PRODUCT', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
    ('4302', 'OTHER_ACCESSORY_PRODUCT', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
    ('4305', 'OTHER_ACCESSORY_PRODUCT', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
    ('4575', 'OTHER_ACCESSORY_PRODUCT', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
    ('4660', 'OTHER_ACCESSORY_PRODUCT', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
    ('4661', 'OTHER_ACCESSORY_PRODUCT', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
    ('3527', 'IPAD_MAC', 'CHARGER_CABLE', 'NOT_APPLICABLE')
), resolved_corrections AS (
    SELECT DISTINCT
        product.id AS product_id,
        correction.expected_category_code,
        correction.target_category_code,
        correction.target_condition_type
    FROM products product
    JOIN monetary_classification_corrections correction
      ON correction.external_product_id = product.external_id
      OR correction.external_product_id = product.code
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    condition_type = correction.target_condition_type,
    rule_version = 'customer-approved-2026-08-14-v3',
    change_reason = 'Customer clarification: monetary accessory classification'
FROM resolved_corrections correction,
     analytics_categories expected_category,
     analytics_categories target_category
WHERE assignment.product_id = correction.product_id
  AND assignment.analytics_category_id = expected_category.id
  AND expected_category.code = correction.expected_category_code
  AND target_category.code = correction.target_category_code;

WITH monetary_classification_corrections (
    external_product_id,
    expected_category_code,
    target_category_code,
    target_condition_type
) AS (VALUES
    ('2579', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('2591', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('2972', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('2973', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('3325', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('3784', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('3901', 'ACCESSORY_IPAD_MAC', 'IPAD_MAC', 'NEW'),
    ('2716', 'OTHER_ACCESSORY_PRODUCT', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
    ('4302', 'OTHER_ACCESSORY_PRODUCT', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
    ('4305', 'OTHER_ACCESSORY_PRODUCT', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
    ('4575', 'OTHER_ACCESSORY_PRODUCT', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
    ('4660', 'OTHER_ACCESSORY_PRODUCT', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
    ('4661', 'OTHER_ACCESSORY_PRODUCT', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
    ('3527', 'IPAD_MAC', 'CHARGER_CABLE', 'NOT_APPLICABLE')
), resolved_corrections AS (
    SELECT DISTINCT
        product.id AS product_id,
        correction.expected_category_code,
        correction.target_category_code,
        correction.target_condition_type
    FROM products product
    JOIN monetary_classification_corrections correction
      ON correction.external_product_id = product.external_id
      OR correction.external_product_id = product.code
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    classification_version = 'customer-approved-2026-08-14-v3',
    condition_type_snapshot = correction.target_condition_type,
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM resolved_corrections correction,
     analytics_categories expected_category,
     analytics_categories target_category
WHERE item.product_id = correction.product_id
  AND item.analytics_category_id = expected_category.id
  AND expected_category.code = correction.expected_category_code
  AND target_category.code = correction.target_category_code;
