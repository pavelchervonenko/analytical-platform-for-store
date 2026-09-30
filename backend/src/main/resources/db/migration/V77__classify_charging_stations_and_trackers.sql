-- Customer-confirmed Ugreen chargers, USB-C/Lightning cable, wireless charger,
-- standing 3-in-1 charger and Keephone Taggy trackers from the LiveSklad export.
-- Code and name guards confine historical corrections to these eight products.
-- Financial amounts, employees, source kinds and explicit payroll assignments stay intact.

WITH approved_products AS (
    SELECT product.id, approved.target_code
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    JOIN (VALUES
        ('3481', '^[cс]зу[[:space:]]+ugreen[[:space:]]+x512[[:space:]]+type-c[[:space:]]+20w[[:space:]]+белый$', 'CHARGER_CABLE'),
        ('3480', '^[cс]зу[[:space:]]+ugreen[[:space:]]+x512[[:space:]]+type-c[[:space:]]+20w[[:space:]]+черный$', 'CHARGER_CABLE'),
        ('4013', '^[cс]зу[[:space:]]+ugreen[[:space:]]+x513[[:space:]]+type-c[[:space:]]+30w[[:space:]]+белый$', 'CHARGER_CABLE'),
        ('3390', '^taggy[[:space:]]+keephone[[:space:]]+белый$', 'OTHER_ACCESSORY_PRODUCT'),
        ('3391', '^taggy[[:space:]]+keephone[[:space:]]+черный$', 'OTHER_ACCESSORY_PRODUCT'),
        ('6108', '^usb-c[[:space:]]*-[[:space:]]*lightning[[:space:]]+no[[:space:]]+box$', 'CHARGER_CABLE'),
        ('4350', '^беспроводное[[:space:]]+зар[.][[:space:]]+устройство[[:space:]]+vlp[[:space:]]+lite[[:space:]]+power[[:space:]]+snap[[:space:]]+qi2[[:space:]]+apple[[:space:]]+watch$', 'CHARGER_CABLE'),
        ('71', '^станция[[:space:]]+3[[:space:]]+в[[:space:]]+1[[:space:]]+[(]стоячая[)]$', 'CHARGER_CABLE')
    ) approved(code, name_pattern, target_code)
      ON approved.code = product.code
     AND product.name ~* approved.name_pattern
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    condition_type = 'NOT_APPLICABLE',
    rule_version = 'customer-approved-2026-09-27-charging-station-v1',
    change_reason = 'Customer-confirmed charger, charging station or tracker'
FROM approved_products approved,
     analytics_categories target_category
WHERE assignment.product_id = approved.id
  AND target_category.code = approved.target_code
  AND (assignment.analytics_category_id <> target_category.id
       OR assignment.condition_type <> 'NOT_APPLICABLE');

WITH approved_products AS (
    SELECT product.id, approved.target_code
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    JOIN (VALUES
        ('3481', '^[cс]зу[[:space:]]+ugreen[[:space:]]+x512[[:space:]]+type-c[[:space:]]+20w[[:space:]]+белый$', 'CHARGER_CABLE'),
        ('3480', '^[cс]зу[[:space:]]+ugreen[[:space:]]+x512[[:space:]]+type-c[[:space:]]+20w[[:space:]]+черный$', 'CHARGER_CABLE'),
        ('4013', '^[cс]зу[[:space:]]+ugreen[[:space:]]+x513[[:space:]]+type-c[[:space:]]+30w[[:space:]]+белый$', 'CHARGER_CABLE'),
        ('3390', '^taggy[[:space:]]+keephone[[:space:]]+белый$', 'OTHER_ACCESSORY_PRODUCT'),
        ('3391', '^taggy[[:space:]]+keephone[[:space:]]+черный$', 'OTHER_ACCESSORY_PRODUCT'),
        ('6108', '^usb-c[[:space:]]*-[[:space:]]*lightning[[:space:]]+no[[:space:]]+box$', 'CHARGER_CABLE'),
        ('4350', '^беспроводное[[:space:]]+зар[.][[:space:]]+устройство[[:space:]]+vlp[[:space:]]+lite[[:space:]]+power[[:space:]]+snap[[:space:]]+qi2[[:space:]]+apple[[:space:]]+watch$', 'CHARGER_CABLE'),
        ('71', '^станция[[:space:]]+3[[:space:]]+в[[:space:]]+1[[:space:]]+[(]стоячая[)]$', 'CHARGER_CABLE')
    ) approved(code, name_pattern, target_code)
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
       'customer-approved-2026-09-27-charging-station-v1',
       LEAST(product.created_at, COALESCE((
           SELECT MIN(document.occurred_at)
           FROM sales_document_items item
           JOIN sales_documents document ON document.id = item.sales_document_id
           WHERE item.product_id = product.id
       ), product.created_at)),
       'Customer-confirmed charger, charging station or tracker'
FROM approved_products approved
JOIN products product ON product.id = approved.id
JOIN analytics_categories target_category ON target_category.code = approved.target_code
WHERE NOT EXISTS (
    SELECT 1 FROM product_category_assignments assignment
    WHERE assignment.product_id = product.id
);

WITH approved_products AS (
    SELECT product.id, approved.target_code
    FROM products product
    JOIN integration_connections connection ON connection.id = product.connection_id
    JOIN (VALUES
        ('3481', '^[cс]зу[[:space:]]+ugreen[[:space:]]+x512[[:space:]]+type-c[[:space:]]+20w[[:space:]]+белый$', 'CHARGER_CABLE'),
        ('3480', '^[cс]зу[[:space:]]+ugreen[[:space:]]+x512[[:space:]]+type-c[[:space:]]+20w[[:space:]]+черный$', 'CHARGER_CABLE'),
        ('4013', '^[cс]зу[[:space:]]+ugreen[[:space:]]+x513[[:space:]]+type-c[[:space:]]+30w[[:space:]]+белый$', 'CHARGER_CABLE'),
        ('3390', '^taggy[[:space:]]+keephone[[:space:]]+белый$', 'OTHER_ACCESSORY_PRODUCT'),
        ('3391', '^taggy[[:space:]]+keephone[[:space:]]+черный$', 'OTHER_ACCESSORY_PRODUCT'),
        ('6108', '^usb-c[[:space:]]*-[[:space:]]*lightning[[:space:]]+no[[:space:]]+box$', 'CHARGER_CABLE'),
        ('4350', '^беспроводное[[:space:]]+зар[.][[:space:]]+устройство[[:space:]]+vlp[[:space:]]+lite[[:space:]]+power[[:space:]]+snap[[:space:]]+qi2[[:space:]]+apple[[:space:]]+watch$', 'CHARGER_CABLE'),
        ('71', '^станция[[:space:]]+3[[:space:]]+в[[:space:]]+1[[:space:]]+[(]стоячая[)]$', 'CHARGER_CABLE')
    ) approved(code, name_pattern, target_code)
      ON approved.code = product.code
     AND product.name ~* approved.name_pattern
    WHERE connection.connection_key = 'livesklad-default'
      AND product.source_kind IN ('PRODUCT', 'UNKNOWN')
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    condition_type_snapshot = 'NOT_APPLICABLE',
    classification_version = 'customer-approved-2026-09-27-charging-station-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM approved_products approved,
     analytics_categories target_category
WHERE item.product_id = approved.id
  AND target_category.code = approved.target_code
  AND (item.analytics_category_id <> target_category.id
       OR item.condition_type_snapshot <> 'NOT_APPLICABLE');
