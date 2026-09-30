-- Customer-approved Dyson HS08/Airwrap classification, 2026-09-26.
-- Only four audited LiveSklad product codes are backfilled; code 3183 was
-- previously assigned IPAD_MAC. Amounts and immutable reports are unchanged.

INSERT INTO analytics_categories (
    code, name, description, category_kind, device_family,
    counts_as_phone, counts_as_device, counts_as_additional_revenue,
    attach_denominator_code, requires_same_document_for_attach,
    payroll_category_code
) VALUES (
    'HAIR_STYLERS', 'Стайлеры для волос',
    'Подтверждённые стайлеры Dyson HS08 и Airwrap',
    'DEVICE', 'OTHER', false, true, false,
    NULL, false, 'TECH_TIER_1'
);

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind = 'PRODUCT'
      AND product.code IN ('3105', '3183', '4282', '5201')
      AND product.name ~* '(dyson[[:space:]]+hs08|dyson[[:space:]]+airwrap)'
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    rule_version = 'customer-approved-2026-09-26-hair-stylers-v1',
    change_reason = 'Customer-approved Dyson hair styler analytics category'
FROM approved_products approved, analytics_categories old_category,
     analytics_categories target_category
WHERE assignment.product_id = approved.id
  AND assignment.analytics_category_id = old_category.id
  AND old_category.code IN ('IPAD_MAC', 'PODS_WATCH_OTHER_DEVICE')
  AND target_category.code = 'HAIR_STYLERS';

-- The audited card 5201 had no permanent assignment.
INSERT INTO product_category_assignments (
    product_id, analytics_category_id, condition_type, assignment_source,
    rule_version, valid_from, change_reason
)
SELECT product.id, target_category.id, 'NEW', 'MANUAL',
       'customer-approved-2026-09-26-hair-stylers-v1',
       LEAST(product.created_at, COALESCE((
           SELECT MIN(document.occurred_at)
           FROM sales_document_items item
           JOIN sales_documents document ON document.id = item.sales_document_id
           WHERE item.product_id = product.id
       ), product.created_at)),
       'Customer-approved Dyson hair styler analytics category'
FROM products product
JOIN integration_connections connection ON connection.id = product.connection_id
JOIN analytics_categories target_category ON target_category.code = 'HAIR_STYLERS'
WHERE connection.connection_key = 'livesklad-default'
  AND product.source_kind = 'PRODUCT'
  AND product.code IN ('3105', '3183', '4282', '5201')
  AND product.name ~* '(dyson[[:space:]]+hs08|dyson[[:space:]]+airwrap)'
  AND NOT EXISTS (
      SELECT 1 FROM product_category_assignments assignment
      WHERE assignment.product_id = product.id
  );

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind = 'PRODUCT'
      AND product.code IN ('3105', '3183', '4282', '5201')
      AND product.name ~* '(dyson[[:space:]]+hs08|dyson[[:space:]]+airwrap)'
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    classification_version = 'customer-approved-2026-09-26-hair-stylers-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved_products approved, analytics_categories old_category,
     analytics_categories target_category
WHERE item.product_id = approved.id
  AND item.analytics_category_id = old_category.id
  AND old_category.code IN ('IPAD_MAC', 'PODS_WATCH_OTHER_DEVICE', 'UNMAPPED')
  AND target_category.code = 'HAIR_STYLERS';
