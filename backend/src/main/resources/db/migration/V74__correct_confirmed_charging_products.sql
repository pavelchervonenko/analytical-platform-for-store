-- Customer-approved charging blocks, USB-C cable and kits from the LiveSklad export.
-- Exactly fifteen product codes in the default connection; name guards prevent code reuse.
-- Sale and return amounts, employees, source kinds and payroll assignments are unchanged.

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    JOIN (VALUES
        ('4767', 'apple[[:space:]]+power[[:space:]]+adapter[[:space:]]+30w'),
        ('4768', 'cable[[:space:]]+usb-c[[:space:]]+to[[:space:]]+usb-c'),
        ('4769', 'samsung[[:space:]]+power[[:space:]]+adapter[[:space:]]+25w'),
        ('324', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('1941', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('3241', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('3494', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('3493', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('47', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('48', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('64', '^комплект[[:space:]]+baseus[[:space:]]+20w.*type-c'),
        ('65', '^комплект[[:space:]]+baseus[[:space:]]+20w.*lightning'),
        ('66', '^комплект[[:space:]]+baseus[[:space:]]+20w.*type-c'),
        ('67', '^комплект[[:space:]]+baseus[[:space:]]+20w.*lightning'),
        ('690', '^комплект[[:space:]]+baseus[[:space:]]+gan5[[:space:]]+30w.*type-c')
    ) approved(code, name_pattern)
      ON approved.code = product.code
     AND product.name ~* approved.name_pattern
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    condition_type = 'NOT_APPLICABLE',
    rule_version = 'customer-approved-2026-09-27-charger-cable-v1',
    change_reason = 'Customer-approved charging block or USB-C cable'
FROM approved_products approved,
     analytics_categories target_category
WHERE assignment.product_id = approved.id
  AND (assignment.analytics_category_id <> target_category.id
       OR assignment.condition_type <> 'NOT_APPLICABLE')
  AND target_category.code = 'CHARGER_CABLE';

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    JOIN (VALUES
        ('4767', 'apple[[:space:]]+power[[:space:]]+adapter[[:space:]]+30w'),
        ('4768', 'cable[[:space:]]+usb-c[[:space:]]+to[[:space:]]+usb-c'),
        ('4769', 'samsung[[:space:]]+power[[:space:]]+adapter[[:space:]]+25w'),
        ('324', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('1941', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('3241', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('3494', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('3493', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('47', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('48', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('64', '^комплект[[:space:]]+baseus[[:space:]]+20w.*type-c'),
        ('65', '^комплект[[:space:]]+baseus[[:space:]]+20w.*lightning'),
        ('66', '^комплект[[:space:]]+baseus[[:space:]]+20w.*type-c'),
        ('67', '^комплект[[:space:]]+baseus[[:space:]]+20w.*lightning'),
        ('690', '^комплект[[:space:]]+baseus[[:space:]]+gan5[[:space:]]+30w.*type-c')
    ) approved(code, name_pattern)
      ON approved.code = product.code
     AND product.name ~* approved.name_pattern
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
)
INSERT INTO product_category_assignments (
    product_id, analytics_category_id, condition_type, assignment_source,
    rule_version, valid_from, change_reason
)
SELECT product.id, target_category.id, 'NOT_APPLICABLE', 'MANUAL',
       'customer-approved-2026-09-27-charger-cable-v1',
       LEAST(product.created_at, COALESCE((
           SELECT MIN(document.occurred_at)
           FROM sales_document_items item
           JOIN sales_documents document ON document.id = item.sales_document_id
           WHERE item.product_id = product.id
       ), product.created_at)),
       'Customer-approved charging block or USB-C cable'
FROM approved_products approved
JOIN products product ON product.id = approved.id
JOIN analytics_categories target_category ON target_category.code = 'CHARGER_CABLE'
WHERE NOT EXISTS (
    SELECT 1 FROM product_category_assignments assignment
    WHERE assignment.product_id = product.id
);

WITH approved_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    JOIN (VALUES
        ('4767', 'apple[[:space:]]+power[[:space:]]+adapter[[:space:]]+30w'),
        ('4768', 'cable[[:space:]]+usb-c[[:space:]]+to[[:space:]]+usb-c'),
        ('4769', 'samsung[[:space:]]+power[[:space:]]+adapter[[:space:]]+25w'),
        ('324', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('1941', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('3241', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('3494', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('3493', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('47', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('48', '^блок[[:space:]]+baseus.*[0-9]+w'),
        ('64', '^комплект[[:space:]]+baseus[[:space:]]+20w.*type-c'),
        ('65', '^комплект[[:space:]]+baseus[[:space:]]+20w.*lightning'),
        ('66', '^комплект[[:space:]]+baseus[[:space:]]+20w.*type-c'),
        ('67', '^комплект[[:space:]]+baseus[[:space:]]+20w.*lightning'),
        ('690', '^комплект[[:space:]]+baseus[[:space:]]+gan5[[:space:]]+30w.*type-c')
    ) approved(code, name_pattern)
      ON approved.code = product.code
     AND product.name ~* approved.name_pattern
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    condition_type_snapshot = 'NOT_APPLICABLE',
    classification_version = 'customer-approved-2026-09-27-charger-cable-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved_products approved,
     analytics_categories target_category
WHERE item.product_id = approved.id
  AND (item.analytics_category_id <> target_category.id
       OR item.condition_type_snapshot <> 'NOT_APPLICABLE')
  AND target_category.code = 'CHARGER_CABLE';
