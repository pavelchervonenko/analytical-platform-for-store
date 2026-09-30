-- Customer-approved Ray Ban smart-glasses category, 2026-09-25.
-- Correct all Ray Ban eyewear products in LiveSklad, including historical sale and
-- return category snapshots. Amounts, conditions, employees, and immutable
-- report revisions are unchanged. Payroll remains at the former device level.

INSERT INTO analytics_categories (
    code, name, description, category_kind, device_family,
    counts_as_phone, counts_as_device, counts_as_additional_revenue,
    attach_denominator_code, requires_same_document_for_attach,
    payroll_category_code
) VALUES (
    'SMART_GLASSES',
    'Умные очки',
    'Очки Ray Ban, включая модели Wayfarer и Starfire',
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
      AND product.name ~* 'ray[ -]?ban'
      AND product.name !~* '(чехол|case|ремешок|strap|зарядн|charging)'
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    rule_version = 'customer-approved-2026-09-25-smart-glasses-v1',
    change_reason = 'Customer-approved smart glasses analytics category'
FROM approved_products approved,
     analytics_categories old_category,
     analytics_categories target_category
WHERE assignment.product_id = approved.id
  AND assignment.analytics_category_id = old_category.id
  AND old_category.code <> 'SMART_GLASSES'
  AND target_category.code = 'SMART_GLASSES';

-- Include Ray Ban eyewear cards without a permanent assignment, including
-- the sold Starfire card observed in the audited export.
INSERT INTO product_category_assignments (
    product_id, analytics_category_id, condition_type, assignment_source,
    rule_version, valid_from, change_reason
)
SELECT product.id, target_category.id,
       CASE
           WHEN product.name ~* '(б/у|б у|бу | used)' THEN 'USED'
           WHEN product.name ~* '(asis|as is)' THEN 'ASIS'
           ELSE 'NEW'
       END, 'MANUAL',
       'customer-approved-2026-09-25-smart-glasses-v1',
       LEAST(
           product.created_at,
           COALESCE((
               SELECT MIN(document.occurred_at)
               FROM sales_document_items item
               JOIN sales_documents document ON document.id = item.sales_document_id
               WHERE item.product_id = product.id
           ), product.created_at)
       ),
       'Customer-approved smart glasses analytics category'
FROM products product
JOIN integration_connections connection
  ON connection.id = product.connection_id
JOIN analytics_categories target_category
  ON target_category.code = 'SMART_GLASSES'
WHERE connection.connection_key = 'livesklad-default'
  AND product.source_kind = 'PRODUCT'
  AND product.name ~* 'ray[ -]?ban'
  AND product.name !~* '(чехол|case|ремешок|strap|зарядн|charging)'
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
      AND product.name ~* 'ray[ -]?ban'
      AND product.name !~* '(чехол|case|ремешок|strap|зарядн|charging)'
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    classification_version = 'customer-approved-2026-09-25-smart-glasses-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved_products approved,
     analytics_categories old_category,
     analytics_categories target_category
WHERE item.product_id = approved.id
  AND item.analytics_category_id = old_category.id
  AND old_category.code <> 'SMART_GLASSES'
  AND target_category.code = 'SMART_GLASSES';
