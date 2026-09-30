-- Customer-approved setup services stored as LiveSklad products, 2026-09-25.
-- Correct analytics classification for codes 6278, 6151 and 5348 without
-- changing LiveSklad source kind, is_work, amounts, employees or report revisions.

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection
      ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'SERVICE')
      AND (
          product.code = '6278' AND product.name ~* 'парол'
          OR product.code = '6151' AND product.name ~* 'настройк.*apple[[:space:]]+watch'
          OR product.code = '5348' AND product.name ~* 'настройк.*модем'
      )
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    condition_type = 'NOT_APPLICABLE',
    rule_version = 'customer-approved-2026-09-25-setup-products-v1',
    change_reason = 'Customer-approved setup service analytics category'
FROM approved_products approved,
     analytics_categories old_category,
     analytics_categories target_category
WHERE assignment.product_id = approved.id
  AND assignment.analytics_category_id = old_category.id
  AND old_category.code <> 'SETUP_SERVICE'
  AND assignment.assignment_source <> 'MANUAL'
  AND target_category.code = 'SETUP_SERVICE';

-- All three audited cards lacked a permanent category.
INSERT INTO product_category_assignments (
    product_id, analytics_category_id, condition_type, assignment_source,
    rule_version, valid_from, change_reason
)
SELECT product.id, target_category.id, 'NOT_APPLICABLE', 'MANUAL',
       'customer-approved-2026-09-25-setup-products-v1',
       LEAST(
           product.created_at,
           COALESCE((
               SELECT MIN(document.occurred_at)
               FROM sales_document_items item
               JOIN sales_documents document ON document.id = item.sales_document_id
               WHERE item.product_id = product.id
           ), product.created_at)
       ),
       'Customer-approved setup service analytics category'
FROM products product
JOIN integration_connections connection
  ON connection.id = product.connection_id
JOIN analytics_categories target_category
  ON target_category.code = 'SETUP_SERVICE'
WHERE connection.connection_key = 'livesklad-default'
  AND product.source_kind IN ('PRODUCT', 'SERVICE')
  AND (
      product.code = '6278' AND product.name ~* 'парол'
      OR product.code = '6151' AND product.name ~* 'настройк.*apple[[:space:]]+watch'
      OR product.code = '5348' AND product.name ~* 'настройк.*модем'
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
      AND product.source_kind IN ('PRODUCT', 'SERVICE')
      AND (
          product.code = '6278' AND product.name ~* 'парол'
          OR product.code = '6151' AND product.name ~* 'настройк.*apple[[:space:]]+watch'
          OR product.code = '5348' AND product.name ~* 'настройк.*модем'
      )
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    condition_type_snapshot = 'NOT_APPLICABLE',
    classification_version = 'customer-approved-2026-09-25-setup-products-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved_products approved,
     analytics_categories old_category,
     analytics_categories target_category
WHERE item.product_id = approved.id
  AND item.analytics_category_id = old_category.id
  AND old_category.code <> 'SETUP_SERVICE'
  AND target_category.code = 'SETUP_SERVICE';
