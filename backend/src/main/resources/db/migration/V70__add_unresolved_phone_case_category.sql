-- Device compatibility is not established for these catalogue cards.
-- They remain ACCESSORY for payroll and financial reporting, but are not
-- silently assigned to either confirmed phone-case attach-rate numerator.
INSERT INTO analytics_categories (
    code, name, description, category_kind, device_family,
    counts_as_phone, counts_as_device, counts_as_additional_revenue,
    attach_denominator_code, requires_same_document_for_attach,
    payroll_category_code
) VALUES (
    'OTHER_CASE', 'Чехлы без установленной совместимости',
    'Чехлы телефонов, для которых не подтверждён целевой бренд устройства',
    'ACCESSORY', 'OTHER', false, false, true, NULL, false, 'ACCESSORY'
);

WITH approved AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
      AND product.code IN ('14', '30', '36', '38', '39', '40', '41', '588')
      AND product.name ~* '(чехол|case|magsafe)'
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target.id,
    condition_type = 'NOT_APPLICABLE',
    rule_version = 'customer-approved-2026-09-26-unresolved-cases-v1',
    change_reason = 'Phone-case compatibility is not established'
FROM approved, analytics_categories old, analytics_categories target
WHERE assignment.product_id = approved.id
  AND assignment.analytics_category_id = old.id
  AND old.code = 'OTHER_ACCESSORY_PRODUCT'
  AND target.code = 'OTHER_CASE';

WITH approved AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
      AND product.code IN ('14', '30', '36', '38', '39', '40', '41', '588')
      AND product.name ~* '(чехол|case|magsafe)'
)
UPDATE sales_document_items item
SET analytics_category_id = target.id,
    classification_version = 'customer-approved-2026-09-26-unresolved-cases-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved, analytics_categories old, analytics_categories target
WHERE item.product_id = approved.id
  AND item.analytics_category_id = old.id
  AND old.code = 'OTHER_ACCESSORY_PRODUCT'
  AND target.code = 'OTHER_CASE';
