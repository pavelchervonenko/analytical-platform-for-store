CREATE FUNCTION resolve_default_payroll_category(
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
