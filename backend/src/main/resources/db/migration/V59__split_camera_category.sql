-- Customer-approved Instax Mini 13 camera category, 2026-09-25.
-- Bounded to LiveSklad codes 6031 and 6032; amounts and immutable report
-- revisions stay unchanged. Payroll remains unclassified until its review.

INSERT INTO analytics_categories (
    code, name, description, category_kind, device_family,
    counts_as_phone, counts_as_device, counts_as_additional_revenue,
    attach_denominator_code, requires_same_document_for_attach,
    payroll_category_code
) VALUES (
    'CAMERAS',
    'Фотоаппараты',
    'Подтверждённые фотоаппараты Instax Mini 13',
    'DEVICE', 'OTHER',
    false, true, false,
    NULL, false, 'UNMAPPED'
);

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection
      ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind = 'PRODUCT'
      AND product.code IN ('6031', '6032')
      AND product.name ~* '^instax[[:space:]]+mini[[:space:]]+13([[:space:]]|$)'
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    rule_version = 'customer-approved-2026-09-25-cameras-v1',
    change_reason = 'Customer-approved Instax camera analytics category'
FROM approved_products approved,
     analytics_categories old_category,
     analytics_categories target_category
WHERE assignment.product_id = approved.id
  AND assignment.analytics_category_id = old_category.id
  AND old_category.code IN ('UNMAPPED', 'PODS_WATCH_OTHER_DEVICE')
  AND assignment.assignment_source <> 'MANUAL'
  AND target_category.code = 'CAMERAS';

-- Code 6031 had no assignment and one UNMAPPED sale in the audited export.
-- Code 6032 is inserted here only if its card exists when migration runs.
INSERT INTO product_category_assignments (
    product_id, analytics_category_id, condition_type, assignment_source,
    rule_version, valid_from, change_reason
)
SELECT product.id, target_category.id, 'NEW', 'MANUAL',
       'customer-approved-2026-09-25-cameras-v1',
       LEAST(
           product.created_at,
           COALESCE((
               SELECT MIN(document.occurred_at)
               FROM sales_document_items item
               JOIN sales_documents document ON document.id = item.sales_document_id
               WHERE item.product_id = product.id
           ), product.created_at)
       ),
       'Customer-approved Instax camera analytics category'
FROM products product
JOIN integration_connections connection
  ON connection.id = product.connection_id
JOIN analytics_categories target_category
  ON target_category.code = 'CAMERAS'
WHERE connection.connection_key = 'livesklad-default'
  AND product.source_kind = 'PRODUCT'
  AND product.code IN ('6031', '6032')
  AND product.name ~* '^instax[[:space:]]+mini[[:space:]]+13([[:space:]]|$)'
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
      AND product.code IN ('6031', '6032')
      AND product.name ~* '^instax[[:space:]]+mini[[:space:]]+13([[:space:]]|$)'
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    classification_version = 'customer-approved-2026-09-25-cameras-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved_products approved,
     analytics_categories old_category,
     analytics_categories target_category
WHERE item.product_id = approved.id
  AND item.analytics_category_id = old_category.id
  AND old_category.code IN ('UNMAPPED', 'PODS_WATCH_OTHER_DEVICE')
  AND target_category.code = 'CAMERAS';
