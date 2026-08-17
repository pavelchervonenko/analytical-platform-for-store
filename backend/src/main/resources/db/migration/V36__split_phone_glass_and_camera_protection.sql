-- Customer clarification 2026-08-14:
-- phone screen glass and camera/lens protection are separate KPI and attach-rate
-- categories for both iPhone and Samsung.
--
-- Existing GLASS_CAMERA_* codes are retained for camera protection to preserve
-- API compatibility. New GLASS_* categories hold ordinary screen glass.
-- Finalized report snapshots remain immutable; normalized source facts and
-- effective assignments are repaired in place.

UPDATE analytics_categories
SET name = CASE code
        WHEN 'GLASS_CAMERA_IPHONE' THEN 'Защита камеры iPhone'
        WHEN 'GLASS_CAMERA_SAMSUNG' THEN 'Защита камеры Samsung'
    END,
    version = version + 1,
    updated_at = clock_timestamp()
WHERE code IN ('GLASS_CAMERA_IPHONE', 'GLASS_CAMERA_SAMSUNG')
  AND name IS DISTINCT FROM CASE code
        WHEN 'GLASS_CAMERA_IPHONE' THEN 'Защита камеры iPhone'
        WHEN 'GLASS_CAMERA_SAMSUNG' THEN 'Защита камеры Samsung'
    END;

INSERT INTO analytics_categories (
    code,
    name,
    category_kind,
    device_family,
    counts_as_phone,
    counts_as_device,
    counts_as_additional_revenue,
    attach_denominator_code,
    requires_same_document_for_attach
) VALUES
    (
        'GLASS_IPHONE',
        'Защитное стекло iPhone',
        'ACCESSORY',
        'IPHONE',
        false,
        false,
        true,
        'IPHONE',
        true
    ),
    (
        'GLASS_SAMSUNG',
        'Защитное стекло Samsung',
        'ACCESSORY',
        'SAMSUNG',
        false,
        false,
        true,
        'SAMSUNG',
        true
    );

WITH ordinary_glass_assignments AS (
    SELECT
        assignment.id AS assignment_id,
        CASE category.code
            WHEN 'GLASS_CAMERA_IPHONE' THEN 'GLASS_IPHONE'
            WHEN 'GLASS_CAMERA_SAMSUNG' THEN 'GLASS_SAMSUNG'
        END AS target_category_code
    FROM product_category_assignments assignment
    JOIN products product ON product.id = assignment.product_id
    JOIN analytics_categories category
      ON category.id = assignment.analytics_category_id
    WHERE category.code IN (
        'GLASS_CAMERA_IPHONE',
        'GLASS_CAMERA_SAMSUNG'
    )
      AND lower(product.name) !~ '(камер|kамер|kaмер|camera|линз|lens)'
)
UPDATE product_category_assignments assignment
SET analytics_category_id = target_category.id,
    rule_version = 'customer-approved-2026-08-14-glass-split-v1',
    change_reason = 'Customer clarification: screen glass is separate from camera protection'
FROM ordinary_glass_assignments split
JOIN analytics_categories target_category
  ON target_category.code = split.target_category_code
WHERE assignment.id = split.assignment_id;

WITH ordinary_glass_items AS (
    SELECT
        item.id AS item_id,
        CASE category.code
            WHEN 'GLASS_CAMERA_IPHONE' THEN 'GLASS_IPHONE'
            WHEN 'GLASS_CAMERA_SAMSUNG' THEN 'GLASS_SAMSUNG'
        END AS target_category_code
    FROM sales_document_items item
    JOIN products product ON product.id = item.product_id
    JOIN analytics_categories category
      ON category.id = item.analytics_category_id
    WHERE category.code IN (
        'GLASS_CAMERA_IPHONE',
        'GLASS_CAMERA_SAMSUNG'
    )
      AND lower(product.name) !~ '(камер|kамер|kaмер|camera|линз|lens)'
)
UPDATE sales_document_items item
SET analytics_category_id = target_category.id,
    classification_version = 'customer-approved-2026-08-14-glass-split-v1',
    version = item.version + 1,
    updated_at = clock_timestamp()
FROM ordinary_glass_items split
JOIN analytics_categories target_category
  ON target_category.code = split.target_category_code
WHERE item.id = split.item_id;
