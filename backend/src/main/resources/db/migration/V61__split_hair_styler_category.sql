-- Unpublished draft revised after owner confirmation on 2026-10-01.
-- Schema/taxonomy only: no historical sale, analytical assignment or payroll assignment rewrite.
-- Approved product decisions are applied separately with an explicit prospective boundary.

INSERT INTO analytics_categories (
    code, name, description, category_kind, device_family,
    counts_as_phone, counts_as_device, counts_as_additional_revenue,
    attach_denominator_code, requires_same_document_for_attach,
    payroll_category_code
) VALUES (
    'HAIR_STYLERS', 'Стайлеры для волос',
    'Подтверждённые стайлеры Dyson HS08 и Airwrap',
    'DEVICE', 'OTHER', false, true, false,
    NULL, false, 'TECH_TIER_1'
);
