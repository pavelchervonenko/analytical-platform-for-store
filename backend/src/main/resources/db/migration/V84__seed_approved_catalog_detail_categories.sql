-- Add approved analytical leaves without changing existing products, sale facts,
-- historical reports or the effective payroll policy. Assignments are a separate rollout.
-- New leaves remain inactive until effective payroll and metric preflight pass.
-- PACKAGING keeps the current accessory payroll base until a separate payroll decision.
-- GAME_CONSOLES does not reclassify any PS5 sale or alter the payroll fallback;
-- assigning PS5 products requires a separate effective-payroll comparison.
INSERT INTO analytics_categories (
    code, name, category_kind, device_family, counts_as_phone, counts_as_device,
    counts_as_additional_revenue, payroll_category_code, is_active
) VALUES
    ('PHONE_OTHER', 'Телефоны других брендов', 'DEVICE', 'OTHER', true, true, false, 'TECH_TIER_1', false),
    ('TABLET_APPLE', 'Планшеты Apple', 'DEVICE', 'IPAD_MAC', false, true, false, 'TECH_TIER_2', false),
    ('TABLET_OTHER', 'Другие планшеты', 'DEVICE', 'OTHER', false, true, false, 'TECH_TIER_2', false),
    ('LAPTOP_APPLE', 'Ноутбуки Apple', 'DEVICE', 'IPAD_MAC', false, true, false, 'TECH_TIER_1', false),
    ('LAPTOP_OTHER', 'Другие ноутбуки', 'DEVICE', 'OTHER', false, true, false, 'TECH_TIER_2', false),
    ('WATCH_APPLE', 'Часы Apple', 'DEVICE', 'PODS_WATCH', false, true, false, 'TECH_TIER_2', false),
    ('WATCH_SAMSUNG', 'Часы Samsung', 'DEVICE', 'OTHER', false, true, false, 'TECH_TIER_2', false),
    ('WATCH_OTHER', 'Другие умные часы', 'DEVICE', 'OTHER', false, true, false, 'TECH_TIER_2', false),
    ('GAME_CONSOLES', 'Игровые консоли', 'DEVICE', 'OTHER', false, true, false, 'TECH_TIER_2', false),
    ('MICROPHONES', 'Микрофоны', 'DEVICE', 'OTHER', false, true, false, 'TECH_TIER_2', false),
    ('GAMING_ACCESSORIES', 'Игровые аксессуары', 'ACCESSORY', 'OTHER', false, false, true, 'ACCESSORY', false),
    ('ACCESSORY_AIRPODS', 'Аксессуары AirPods', 'ACCESSORY', 'PODS_WATCH', false, false, true, 'ACCESSORY', false),
    ('ACCESSORY_APPLE_WATCH', 'Аксессуары Apple Watch', 'ACCESSORY', 'PODS_WATCH', false, false, true, 'ACCESSORY', false),
    ('CASE_UNIVERSAL', 'Универсальные чехлы', 'ACCESSORY', 'NONE', false, false, true, 'ACCESSORY', false),
    ('GLASS_OTHER', 'Защита дисплея других телефонов', 'ACCESSORY', 'OTHER', false, false, true, 'ACCESSORY', false),
    ('GLASS_PHONE_UNRESOLVED', 'Стёкла с неизвестной совместимостью', 'ACCESSORY', 'NONE', false, false, true, 'ACCESSORY', false),
    ('PROTECTIVE_FILM', 'Защитные плёнки', 'ACCESSORY', 'NONE', false, false, true, 'ACCESSORY', false),
    ('PACKAGING', 'Упаковка', 'OTHER', 'NONE', false, false, false, 'ACCESSORY', false)
ON CONFLICT (code) DO NOTHING;
