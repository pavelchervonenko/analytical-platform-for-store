-- Extend the accepted enum without rewriting category, document or assignment rows.
ALTER TABLE analytics_categories
    DROP CONSTRAINT analytics_categories_attach_denominator_code_check;

ALTER TABLE analytics_categories
    ADD CONSTRAINT analytics_categories_attach_denominator_code_check
    CHECK (attach_denominator_code IN (
        'IPHONE', 'SAMSUNG', 'PHONE', 'PODS_WATCH', 'AIRPODS', 'APPLE_WATCH',
        'IPAD_MAC', 'NEW_DEVICE', 'USED_DEVICE', 'MATCH_DEVICE_CONDITION'
    ));
