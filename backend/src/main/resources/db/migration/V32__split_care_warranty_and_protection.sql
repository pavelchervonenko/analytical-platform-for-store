-- Customer clarification 2026-08-07:
-- Check Premium and Ultimate Care are protection products;
-- Elite Care and Privilege Care are warranties.
--
-- This is a correction of the original classification, not a new rule that starts
-- mid-period. Keep finalized report/LLM snapshots immutable, but repair normalized
-- source facts so dynamic reports and future snapshots use the corrected meaning.

UPDATE analytics_categories
SET name = 'Протекция',
    version = version + 1,
    updated_at = clock_timestamp()
WHERE code = 'PREMIUM_PROTECTION'
  AND name IS DISTINCT FROM 'Протекция';

WITH corrected_products AS (
    SELECT product.id
    FROM products product
    WHERE product.external_id IN ('4967', '4968', '3888', '3886')
       OR product.code IN ('4967', '4968', '3888', '3886')
),
corrected_assignments AS (
    UPDATE product_category_assignments assignment
    SET analytics_category_id = warranty.id,
        rule_version = 'customer-approved-2026-08-07-v2',
        change_reason = 'Customer clarification: Elite Care and Privilege Care are warranties'
    FROM corrected_products product
    CROSS JOIN analytics_categories warranty
    JOIN analytics_categories protection ON protection.code = 'PREMIUM_PROTECTION'
    WHERE assignment.product_id = product.id
      AND assignment.analytics_category_id = protection.id
      AND warranty.code = 'WARRANTY_GENERIC'
    RETURNING assignment.id
)
UPDATE sales_document_items item
SET analytics_category_id = warranty.id,
    classification_version = 'customer-approved-2026-08-07-v2',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM corrected_assignments assignment
CROSS JOIN analytics_categories warranty
WHERE item.category_assignment_id = assignment.id
  AND warranty.code = 'WARRANTY_GENERIC';
