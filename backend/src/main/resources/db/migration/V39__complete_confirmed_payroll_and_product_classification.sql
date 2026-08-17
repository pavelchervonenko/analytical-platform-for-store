-- Customer-confirmed classification follow-up 2026-08-17.
--
-- V36 introduced dedicated screen-glass categories after V5 had populated
-- payroll defaults. Because the new rows omitted payroll_category_code, the
-- column default left both categories UNMAPPED and blocked payroll readiness.
-- Screen glass is an accessory in both monetary and payroll calculations.

UPDATE analytics_categories
SET payroll_category_code = 'ACCESSORY',
    version = version + 1,
    updated_at = clock_timestamp()
WHERE code IN ('GLASS_IPHONE', 'GLASS_SAMSUNG')
  AND payroll_category_code IS DISTINCT FROM 'ACCESSORY';

-- Apple Pencil, Magic Mouse and Magic Keyboard were confirmed as standalone
-- devices rather than accessories. The payroll scheme places other standalone
-- technology in TECH_TIER_2. Keep the rule constrained by the already-approved
-- IPAD_MAC analytics category so similarly named accessories are not promoted.

CREATE OR REPLACE FUNCTION resolve_default_payroll_category(
    analytics_category_code text,
    product_name text,
    base_payroll_category text
)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN analytics_category_code = 'IPAD_MAC'
             AND lower(COALESCE(product_name, '')) LIKE '%macbook%'
            THEN 'TECH_TIER_1'
        WHEN analytics_category_code = 'IPAD_MAC'
             AND lower(COALESCE(product_name, '')) LIKE '%ipad%'
            THEN 'TECH_TIER_2'
        WHEN analytics_category_code = 'IPAD_MAC'
             AND (
                 lower(COALESCE(product_name, '')) LIKE '%apple pencil%'
                 OR lower(COALESCE(product_name, '')) LIKE '%magic mouse%'
                 OR lower(COALESCE(product_name, '')) LIKE '%magic keyboard%'
             )
            THEN 'TECH_TIER_2'
        WHEN analytics_category_code IN ('IPAD_MAC', 'PODS_WATCH_OTHER_DEVICE')
             AND lower(COALESCE(product_name, '')) LIKE '%dyson%'
            THEN 'TECH_TIER_1'
        WHEN analytics_category_code = 'PODS_WATCH_OTHER_DEVICE'
             AND (
                 lower(COALESCE(product_name, ''))
                     ~ 'playstation[[:space:]]*5'
                 OR lower(COALESCE(product_name, ''))
                     ~ '(^|[^[:alnum:]])ps[[:space:]]*5([^[:alnum:]]|$)'
             )
            THEN 'TECH_TIER_1'
        ELSE base_payroll_category
    END
$$;

COMMENT ON FUNCTION resolve_default_payroll_category(text, text, text) IS
    'Confirmed deterministic payroll defaults; explicit effective-dated product overrides win.';
