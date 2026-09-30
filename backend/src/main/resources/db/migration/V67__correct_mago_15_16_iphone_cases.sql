-- Customer-approved Keephone Mago iPhone 15 Pro/Pro Max and 16 Pro Max cases.
-- Eight exact LiveSklad codes; generic Mago and other-device cases excluded.
-- Sale amounts, employees, conditions and payroll categories remain unchanged.

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind = 'PRODUCT'
      AND product.code IN ('3423', '3424', '3293', '3294', '3295', '2870', '2871', '2872')
      AND product.name ~* 'чехол[[:space:]]+keephone[[:space:]]+mago[[:space:]]+pro([[:space:]]+matte[[:space:]]+magsafe)?[[:space:]]+(15[[:space:]]+pro([[:space:]]+max)?|16[[:space:]]+pro[[:space:]]+max)([[:space:]]|$)'
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    condition_type = 'NOT_APPLICABLE',
    rule_version = 'customer-approved-2026-09-26-mago-15-16-iphone-cases-v1',
    change_reason = 'Customer-approved model-specific iPhone case'
FROM approved_products approved, analytics_categories old_category,
     analytics_categories target_category
WHERE assignment.product_id = approved.id
  AND assignment.analytics_category_id = old_category.id
  AND old_category.code = 'OTHER_ACCESSORY_PRODUCT'
  AND target_category.code = 'CASE_APPLE_IPHONE';

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind = 'PRODUCT'
      AND product.code IN ('3423', '3424', '3293', '3294', '3295', '2870', '2871', '2872')
      AND product.name ~* 'чехол[[:space:]]+keephone[[:space:]]+mago[[:space:]]+pro([[:space:]]+matte[[:space:]]+magsafe)?[[:space:]]+(15[[:space:]]+pro([[:space:]]+max)?|16[[:space:]]+pro[[:space:]]+max)([[:space:]]|$)'
)
INSERT INTO product_category_assignments (
    product_id, analytics_category_id, condition_type, assignment_source,
    rule_version, valid_from, change_reason
)
SELECT product.id, target_category.id, 'NOT_APPLICABLE', 'MANUAL',
       'customer-approved-2026-09-26-mago-15-16-iphone-cases-v1',
       LEAST(product.created_at, COALESCE((
           SELECT MIN(document.occurred_at)
           FROM sales_document_items item
           JOIN sales_documents document ON document.id = item.sales_document_id
           WHERE item.product_id = product.id
       ), product.created_at)),
       'Customer-approved model-specific iPhone case'
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
      AND product.source_kind = 'PRODUCT'
      AND product.code IN ('3423', '3424', '3293', '3294', '3295', '2870', '2871', '2872')
      AND product.name ~* 'чехол[[:space:]]+keephone[[:space:]]+mago[[:space:]]+pro([[:space:]]+matte[[:space:]]+magsafe)?[[:space:]]+(15[[:space:]]+pro([[:space:]]+max)?|16[[:space:]]+pro[[:space:]]+max)([[:space:]]|$)'
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    classification_version = 'customer-approved-2026-09-26-mago-15-16-iphone-cases-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved_products approved, analytics_categories old_category,
     analytics_categories target_category
WHERE item.product_id = approved.id
  AND item.analytics_category_id = old_category.id
  AND old_category.code = 'OTHER_ACCESSORY_PRODUCT'
  AND target_category.code = 'CASE_APPLE_IPHONE';
