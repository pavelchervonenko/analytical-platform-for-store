-- Customer-approved fitness-watch and tracker category, 2026-09-25.
-- Correct historical normalized category snapshots without changing amounts,
-- conditions, employees, or immutable report revisions. The payroll default
-- remains the former PODS_WATCH_OTHER_DEVICE level until payroll review.

INSERT INTO analytics_categories (
    code, name, description, category_kind, device_family,
    counts_as_phone, counts_as_device, counts_as_additional_revenue,
    attach_denominator_code, requires_same_document_for_attach,
    payroll_category_code
) VALUES (
    'FITNESS_WEARABLE',
    'Фитнес-часы и браслеты',
    'Garmin Forerunner/Vivoactive, Google Fitbit Air и Whoop 5.0',
    'DEVICE', 'OTHER',
    false, true, false,
    NULL, false, 'TECH_TIER_2'
);

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection
      ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind = 'PRODUCT'
      AND product.code IN (
          '6014', '6013', '5504', '5245', '5019',
          '5020', '5873', '5183', '5021'
      )
      AND (
          product.name ILIKE '%garmin%'
          OR product.name ILIKE '%fitbit%'
          OR product.name ILIKE '%whoop%'
      )
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    rule_version = 'customer-approved-2026-09-25-fitness-v1',
    change_reason = 'Customer-approved fitness wearable analytics category'
FROM approved_products approved,
     analytics_categories old_category,
     analytics_categories target_category
WHERE assignment.product_id = approved.id
  AND assignment.analytics_category_id = old_category.id
  AND old_category.code = 'PODS_WATCH_OTHER_DEVICE'
  AND assignment.assignment_source <> 'MANUAL'
  AND target_category.code = 'FITNESS_WEARABLE';

-- One sold Whoop card had no permanent assignment in the audited export.
-- Add a bounded assignment only for approved cards still without any assignment.
INSERT INTO product_category_assignments (
    product_id, analytics_category_id, condition_type, assignment_source,
    rule_version, valid_from, change_reason
)
SELECT product.id, target_category.id, 'NEW', 'MANUAL',
       'customer-approved-2026-09-25-fitness-v1',
       LEAST(
           product.created_at,
           COALESCE((
               SELECT MIN(document.occurred_at)
               FROM sales_document_items item
               JOIN sales_documents document ON document.id = item.sales_document_id
               WHERE item.product_id = product.id
           ), product.created_at)
       ),
       'Customer-approved fitness wearable analytics category'
FROM products product
JOIN integration_connections connection
  ON connection.id = product.connection_id
JOIN analytics_categories target_category
  ON target_category.code = 'FITNESS_WEARABLE'
WHERE connection.connection_key = 'livesklad-default'
  AND product.source_kind = 'PRODUCT'
  AND product.code IN (
      '6014', '6013', '5504', '5245', '5019',
      '5020', '5873', '5183', '5021'
  )
  AND (
      product.name ILIKE '%garmin%'
      OR product.name ILIKE '%fitbit%'
      OR product.name ILIKE '%whoop%'
  )
  AND NOT EXISTS (
      SELECT 1 FROM product_category_assignments assignment
      WHERE assignment.product_id = product.id
  );

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection
      ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind = 'PRODUCT'
      AND product.code IN (
          '6014', '6013', '5504', '5245', '5019',
          '5020', '5873', '5183', '5021'
      )
      AND (
          product.name ILIKE '%garmin%'
          OR product.name ILIKE '%fitbit%'
          OR product.name ILIKE '%whoop%'
      )
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    classification_version = 'customer-approved-2026-09-25-fitness-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved_products approved,
     analytics_categories old_category,
     analytics_categories target_category
WHERE item.product_id = approved.id
  AND item.analytics_category_id = old_category.id
  AND old_category.code IN ('PODS_WATCH_OTHER_DEVICE', 'UNMAPPED')
  AND target_category.code = 'FITNESS_WEARABLE';
