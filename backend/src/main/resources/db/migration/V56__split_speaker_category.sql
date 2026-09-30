-- Customer-approved speaker category, 2026-09-25.
-- Ordinary speakers and Yandex smart stations are devices, but not phones.
-- Preserve the former PODS_WATCH_OTHER_DEVICE payroll default until payroll
-- categories are reviewed separately. Monetary amounts and condition snapshots
-- are unchanged; only normalized analytics classification is corrected.

INSERT INTO analytics_categories (
    code, name, description, category_kind, device_family,
    counts_as_phone, counts_as_device, counts_as_additional_revenue,
    attach_denominator_code, requires_same_document_for_attach,
    payroll_category_code
) VALUES (
    'SPEAKERS',
    'Колонки и умные станции',
    'JBL, Harman Kardon и Яндекс Станции',
    'DEVICE',
    'OTHER',
    false,
    true,
    false,
    NULL,
    false,
    'TECH_TIER_2'
);

WITH speaker_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection
      ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.code IN (
          '4230', '4298', '4300', '4301', '5103', '5253', '5256',
          '5312', '5313', '5314', '5315', '5556', '5579', '5580',
          '5581', '5753', '6082'
      )
      AND (
          product.name ILIKE '%колонк%'
          OR product.name ILIKE '%яндекс станци%'
      )
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    rule_version = 'customer-approved-2026-09-25-speakers-v1',
    change_reason = 'Customer-approved speaker analytics category'
FROM speaker_products speaker,
     analytics_categories old_category,
     analytics_categories target_category
WHERE assignment.product_id = speaker.id
  AND assignment.analytics_category_id = old_category.id
  AND old_category.code = 'PODS_WATCH_OTHER_DEVICE'
  AND target_category.code = 'SPEAKERS';

WITH speaker_products AS (
    SELECT product.id
    FROM products product
    JOIN integration_connections connection
      ON connection.id = product.connection_id
    WHERE connection.connection_key = 'livesklad-default'
      AND product.code IN (
          '4230', '4298', '4300', '4301', '5103', '5253', '5256',
          '5312', '5313', '5314', '5315', '5556', '5579', '5580',
          '5581', '5753', '6082'
      )
      AND (
          product.name ILIKE '%колонк%'
          OR product.name ILIKE '%яндекс станци%'
      )
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    classification_version = 'customer-approved-2026-09-25-speakers-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM speaker_products speaker,
     analytics_categories old_category,
     analytics_categories target_category
WHERE item.product_id = speaker.id
  AND item.analytics_category_id = old_category.id
  AND old_category.code = 'PODS_WATCH_OTHER_DEVICE'
  AND target_category.code = 'SPEAKERS';
