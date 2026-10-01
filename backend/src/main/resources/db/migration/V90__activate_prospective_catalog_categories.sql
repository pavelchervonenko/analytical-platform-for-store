-- Activate approved analytical leaves for effective-dated product assignments.
-- No existing product, sale, analytical assignment or payroll assignment is rewritten.
UPDATE analytics_categories
SET is_active = true
WHERE code IN (
    'PHONE_OTHER', 'TABLET_APPLE', 'TABLET_OTHER', 'LAPTOP_APPLE', 'LAPTOP_OTHER',
    'WATCH_APPLE', 'WATCH_SAMSUNG', 'WATCH_OTHER', 'GAME_CONSOLES',
    'MICROPHONES', 'GAMING_ACCESSORIES', 'ACCESSORY_AIRPODS',
    'ACCESSORY_APPLE_WATCH', 'CASE_UNIVERSAL', 'GLASS_OTHER',
    'GLASS_PHONE_UNRESOLVED', 'PROTECTIVE_FILM', 'PACKAGING'
);

-- PS5 remains tier 1 after an effective-dated GAME_CONSOLES assignment;
-- other consoles retain that category's tier 2 default. Rates and payroll formulas are unchanged.
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
        WHEN analytics_category_code IN ('PODS_WATCH_OTHER_DEVICE', 'GAME_CONSOLES')
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
