-- Customer-approved iPhone case rule, independent of brand.
-- Case names must say iPhone/айфон or show an unambiguous iPhone model suffix.
-- Mixed case+glass bundles and names naming another device are not included.
-- Manual assignments to a different category are respected for review.

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
      AND product.name ~* '^чехол[[:space:]]'
      AND product.name !~* '(samsung|galaxy|ipad|macbook|ноутбук|планшет|airpods|watch|xiaomi|redmi|poco|huawei|honor|pixel|oneplus|oppo|realme|vivo|tecno|infinix|motorola|nokia|nothing|xperia|lenovo|asus|nintendo|steam[[:space:]]+deck|playstation|ps5)'
      AND (
          product.name ~* '(iphone|айфон)'
          OR product.name ~* '(^|[[:space:]])1[3-9](e|[[:space:]]+(pro([[:space:]]+max)?|plus|mini|air))([[:space:]]|$)'
      )
      AND NOT EXISTS (
          SELECT 1
          FROM product_category_assignments assignment
          JOIN analytics_categories category ON category.id = assignment.analytics_category_id
          WHERE assignment.product_id = product.id
            AND category.code NOT IN ('OTHER_ACCESSORY_PRODUCT', 'CASE_APPLE_IPHONE')
      )
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    condition_type = 'NOT_APPLICABLE',
    rule_version = 'customer-approved-2026-09-26-explicit-iphone-cases-v1',
    change_reason = 'Customer-approved iPhone case compatibility'
FROM approved_products approved, analytics_categories old_category,
     analytics_categories target_category
WHERE assignment.product_id = approved.id
  AND assignment.analytics_category_id = old_category.id
  AND assignment.assignment_source <> 'MANUAL'
  AND old_category.code = 'OTHER_ACCESSORY_PRODUCT'
  AND target_category.code = 'CASE_APPLE_IPHONE';

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
      AND product.name ~* '^чехол[[:space:]]'
      AND product.name !~* '(samsung|galaxy|ipad|macbook|ноутбук|планшет|airpods|watch|xiaomi|redmi|poco|huawei|honor|pixel|oneplus|oppo|realme|vivo|tecno|infinix|motorola|nokia|nothing|xperia|lenovo|asus|nintendo|steam[[:space:]]+deck|playstation|ps5)'
      AND (
          product.name ~* '(iphone|айфон)'
          OR product.name ~* '(^|[[:space:]])1[3-9](e|[[:space:]]+(pro([[:space:]]+max)?|plus|mini|air))([[:space:]]|$)'
      )
      AND NOT EXISTS (
          SELECT 1
          FROM product_category_assignments assignment
          JOIN analytics_categories category ON category.id = assignment.analytics_category_id
          WHERE assignment.product_id = product.id
            AND category.code NOT IN ('OTHER_ACCESSORY_PRODUCT', 'CASE_APPLE_IPHONE')
      )
)
INSERT INTO product_category_assignments (
    product_id, analytics_category_id, condition_type, assignment_source,
    rule_version, valid_from, change_reason
)
SELECT product.id, target_category.id, 'NOT_APPLICABLE', 'MANUAL',
       'customer-approved-2026-09-26-explicit-iphone-cases-v1',
       LEAST(product.created_at, COALESCE((
           SELECT MIN(document.occurred_at)
           FROM sales_document_items item
           JOIN sales_documents document ON document.id = item.sales_document_id
           WHERE item.product_id = product.id
       ), product.created_at)),
       'Customer-approved iPhone case compatibility'
FROM approved_products approved
JOIN products product ON product.id = approved.id
JOIN analytics_categories target_category ON target_category.code = 'CASE_APPLE_IPHONE'
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
      AND product.name ~* '^чехол[[:space:]]'
      AND product.name !~* '(samsung|galaxy|ipad|macbook|ноутбук|планшет|airpods|watch|xiaomi|redmi|poco|huawei|honor|pixel|oneplus|oppo|realme|vivo|tecno|infinix|motorola|nokia|nothing|xperia|lenovo|asus|nintendo|steam[[:space:]]+deck|playstation|ps5)'
      AND (
          product.name ~* '(iphone|айфон)'
          OR product.name ~* '(^|[[:space:]])1[3-9](e|[[:space:]]+(pro([[:space:]]+max)?|plus|mini|air))([[:space:]]|$)'
      )
      AND NOT EXISTS (
          SELECT 1
          FROM product_category_assignments assignment
          JOIN analytics_categories category ON category.id = assignment.analytics_category_id
          WHERE assignment.product_id = product.id
            AND category.code NOT IN ('OTHER_ACCESSORY_PRODUCT', 'CASE_APPLE_IPHONE')
      )
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    classification_version = 'customer-approved-2026-09-26-explicit-iphone-cases-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved_products approved, analytics_categories old_category,
     analytics_categories target_category
WHERE item.product_id = approved.id
  AND NOT EXISTS (
      SELECT 1 FROM product_category_assignments manual_assignment
      WHERE manual_assignment.product_id = approved.id
        AND manual_assignment.assignment_source = 'MANUAL'
        AND manual_assignment.analytics_category_id = old_category.id
  )
  AND item.analytics_category_id = old_category.id
  AND old_category.code = 'OTHER_ACCESSORY_PRODUCT'
  AND target_category.code = 'CASE_APPLE_IPHONE';
