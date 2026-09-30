-- Consume immutable, dated roles without rewriting money, payroll or role history.
-- A changed role is resolved by a new per-sale review revision, never by editing its snapshot.
CREATE FUNCTION catalog_attach_role_category(category_ text) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
    SELECT category_ IN ('CHARGER_CABLE','PROTECTIVE_FILM','ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
$$;

CREATE FUNCTION catalog_exact_return_original(item_ uuid) RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT original_item.id
    FROM sales_document_items item
    JOIN sales_documents document ON document.id = item.sales_document_id
    JOIN sales_document_items original_item ON original_item.id = item.original_item_id
    JOIN sales_documents original ON original.id = original_item.sales_document_id
    WHERE item.id = item_ AND document.document_kind = 'RETURN' AND original.document_kind = 'SALE'
      AND NOT item.is_deleted AND NOT document.is_deleted
      AND NOT original_item.is_deleted AND NOT original.is_deleted
      AND original_item.product_id = item.product_id
      AND document.original_document_id = original.id
      AND document.store_id = original.store_id AND document.connection_id = original.connection_id
      AND document.occurred_at >= original.occurred_at
$$;

CREATE FUNCTION catalog_has_current_auto_role(item_ uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM catalog_sale_role_snapshot_states s
        WHERE s.item_id = item_ AND s.state = 'CURRENT'
          AND s.policy_version = 'catalog-accessory-roles-v1'
          AND catalog_attach_role_category(s.monetary_category)
          AND s.outcome IN ('ASSIGNED','NO_CONTRIBUTION'))
$$;

CREATE FUNCTION catalog_role_review_required(item_ uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT COALESCE((SELECT
        s.state = 'STALE'
        OR s.policy_version <> 'catalog-accessory-roles-v1'
        OR s.outcome IN ('REVIEW_PRODUCT','REVIEW_SALE')
        OR (s.state = 'CURRENT' AND s.outcome IN ('ASSIGNED','NO_CONTRIBUTION')
            AND EXISTS (
                SELECT 1 FROM sales_document_items returned
                JOIN analytics_categories rc ON rc.id = returned.analytics_category_id
                WHERE returned.original_item_id = s.item_id AND rc.code <> 'EXCLUDE'
                  AND catalog_exact_return_original(returned.id) = s.item_id
                  AND NOT EXISTS (
                      SELECT 1 FROM catalog_sale_role_snapshot_states rs
                      WHERE rs.item_id = returned.id AND rs.state = 'CURRENT'
                        AND rs.origin = 'ORIGINAL_SALE' AND rs.original_snapshot_item_id = s.item_id)))
        FROM catalog_sale_role_snapshot_states s
        JOIN sales_document_items i ON i.id = s.item_id
        JOIN sales_documents d ON d.id = i.sales_document_id
        WHERE s.item_id = item_ AND s.state <> 'DELETED' AND d.document_kind = 'SALE'
          AND catalog_attach_role_category(s.monetary_category)), false)
$$;

CREATE FUNCTION catalog_accessory_review_required(item_ uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT COALESCE((SELECT CASE
        WHEN c.code IN ('OTHER_CASE','CASE_UNIVERSAL','GLASS_PHONE_UNRESOLVED') THEN true
        WHEN c.code = 'PROTECTIVE_FILM'
            THEN NOT catalog_has_current_auto_role(i.id) OR catalog_role_review_required(i.id)
        WHEN c.code IN ('CHARGER_CABLE','ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
            THEN catalog_role_review_required(i.id)
        ELSE false END OR (catalog_attach_role_category(c.code) AND EXISTS (
        SELECT 1 FROM case_attach_current_decisions decision WHERE decision.source_item_id = i.id))
    FROM sales_document_items i JOIN sales_documents d ON d.id = i.sales_document_id
    JOIN analytics_categories c ON c.id = i.analytics_category_id
    WHERE i.id = item_ AND d.document_kind = 'SALE' AND NOT i.is_deleted AND NOT d.is_deleted), false)
$$;

CREATE FUNCTION catalog_accessory_review_allowed_targets(category_ text) RETURNS text[]
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN category_ = 'CHARGER_CABLE'
            THEN ARRAY['CHARGER_CABLE','ACCESSORY_APPLE_WATCH','NO_ATTACH','DEFER']
        WHEN category_ = 'ACCESSORY_AIRPODS' THEN ARRAY['ACCESSORY_AIRPODS','NO_ATTACH','DEFER']
        WHEN category_ = 'ACCESSORY_APPLE_WATCH' THEN ARRAY['ACCESSORY_APPLE_WATCH','NO_ATTACH','DEFER']
        ELSE accessory_review_allowed_targets(category_) END
$$;

CREATE OR REPLACE FUNCTION accessory_review_target_allowed(category_ text, target_ text)
RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
    SELECT COALESCE(target_ = ANY(catalog_accessory_review_allowed_targets(category_)), false)
$$;

ALTER TABLE case_attach_decisions DROP CONSTRAINT case_attach_decisions_target_code_check;
ALTER TABLE case_attach_decisions ADD CONSTRAINT case_attach_decisions_target_code_check
    CHECK (target_code IN ('CASE_APPLE_IPHONE','CASE_SAMSUNG','CASE_OTHER_DEVICE',
        'GLASS_IPHONE','GLASS_SAMSUNG','GLASS_OTHER','FILM_PHONE','FILM_NON_PHONE','DEFER',
        'CHARGER_CABLE','ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH','NO_ATTACH'));

CREATE FUNCTION catalog_accessory_review_fingerprint(item_ uuid) RETURNS text
LANGUAGE sql STABLE AS $$
    SELECT md5(concat_ws('|', i.product_id::text,i.product_name_snapshot,i.quantity::text,
        i.analytics_category_id::text,d.id::text,d.store_id::text,d.business_date::text,
        CASE WHEN s.item_id IS NOT NULL THEN catalog_sale_role_fact(i.id)::text END))
    FROM sales_document_items i JOIN sales_documents d ON d.id = i.sales_document_id
    LEFT JOIN catalog_sale_role_snapshots s ON s.item_id = i.id
    WHERE i.id = item_
$$;

CREATE OR REPLACE VIEW case_attach_review_items AS
WITH phone_brands AS (
    SELECT document.id AS document_id,
           bool_or(category.code IN ('IPHONE_NEW_ASIS', 'IPHONE_USED')) AS has_iphone,
           bool_or(category.code IN ('SAMSUNG_NEW', 'SAMSUNG_USED')) AS has_samsung
    FROM sales_documents document
    JOIN sales_document_items item ON item.sales_document_id = document.id
      AND NOT item.is_deleted
    JOIN analytics_categories category ON category.id = item.analytics_category_id
    WHERE document.document_kind = 'SALE' AND NOT document.is_deleted
    GROUP BY document.id
)
SELECT item.id AS source_item_id, item.product_id, product.code AS product_code,
       item.product_name_snapshot AS product_name, document.store_id,
       document.id AS document_id, document.document_number,
       document.business_date, document.employee_id, document.document_kind,
       item.quantity,
       COALESCE(phone_brands.has_iphone, false) AS has_iphone,
       COALESCE(phone_brands.has_samsung, false) AS has_samsung,
       CASE
           WHEN phone_brands.has_iphone AND phone_brands.has_samsung THEN 'CONFLICT'
           WHEN phone_brands.has_iphone THEN 'IPHONE'
           WHEN phone_brands.has_samsung THEN 'SAMSUNG'
           ELSE 'NONE'
       END AS proposed_target,
       catalog_accessory_review_fingerprint(item.id) AS source_fingerprint,
       CASE WHEN decision.store_id = document.store_id THEN decision.id END AS decision_id,
       CASE WHEN decision.store_id = document.store_id THEN decision.target_code END
           AS decision_target_code,
       decision.revision AS decision_revision,
       CASE WHEN decision.store_id = document.store_id THEN decision.reason END
           AS decision_reason,
       decision.store_id = document.store_id
           AND decision.source_fingerprint = catalog_accessory_review_fingerprint(item.id) AS decision_current,
       category.code AS category_code,
       catalog_accessory_review_allowed_targets(category.code) AS allowed_targets
FROM sales_document_items item
JOIN sales_documents document ON document.id = item.sales_document_id
JOIN products product ON product.id = item.product_id
JOIN analytics_categories category ON category.id = item.analytics_category_id
LEFT JOIN phone_brands ON phone_brands.document_id = document.id
LEFT JOIN case_attach_current_decisions decision ON decision.source_item_id = item.id
WHERE category.code IN ('OTHER_CASE','CASE_UNIVERSAL','GLASS_PHONE_UNRESOLVED',
      'PROTECTIVE_FILM','CHARGER_CABLE','ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
  AND catalog_accessory_review_required(item.id)
  AND document.document_kind = 'SALE'
  AND NOT document.is_deleted AND NOT item.is_deleted;



-- A valid manual decision supersedes an automatic role, including an explicit DEFER.
-- Before review, a still-current automatic sale role and a LEGACY_RETURN retain their existing paths.
CREATE FUNCTION catalog_review_replaces_automatic(item_ uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM case_attach_review_items r WHERE r.source_item_id = item_
        AND (COALESCE(r.decision_current, false) OR NOT catalog_has_current_auto_role(item_)))
$$;

CREATE FUNCTION catalog_role_pending_issue(item_ uuid) RETURNS text
LANGUAGE sql STABLE AS $$
    SELECT CASE
        WHEN COALESCE(r.decision_current, false) AND r.decision_target_code <> 'DEFER' THEN NULL
        WHEN r.source_item_id IS NOT NULL AND catalog_attach_role_category(r.category_code)
            THEN 'CATALOG_ROLE_REVIEW_' || r.category_code
        WHEN s.item_id IS NOT NULL AND catalog_attach_role_category(s.monetary_category)
             AND (s.state = 'STALE' OR s.policy_version <> 'catalog-accessory-roles-v1'
                  OR s.outcome IN ('REVIEW_PRODUCT','REVIEW_SALE'))
            THEN 'CATALOG_ROLE_REVIEW_' || s.monetary_category
        END
    FROM sales_document_items i JOIN sales_documents d ON d.id = i.sales_document_id
    LEFT JOIN catalog_sale_role_snapshot_states s ON s.item_id = i.id
    LEFT JOIN case_attach_review_items r ON r.source_item_id =
        CASE WHEN d.document_kind = 'SALE' THEN i.id ELSE catalog_exact_return_original(i.id) END
    WHERE i.id = item_
$$;

CREATE FUNCTION catalog_attach_metric_uncertain(issue_ text, metric_ text) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
    SELECT COALESCE(CASE issue_
        WHEN 'CATALOG_ROLE_REVIEW_CHARGER_CABLE'
            THEN metric_ IN ('CHARGER_CABLE','ACCESSORY_APPLE_WATCH','ACCESSORY_PODS_WATCH')
        WHEN 'CATALOG_ROLE_REVIEW_PROTECTIVE_FILM' THEN metric_ = 'FILM_PHONE'
        WHEN 'CATALOG_ROLE_REVIEW_ACCESSORY_AIRPODS'
            THEN metric_ IN ('ACCESSORY_AIRPODS','ACCESSORY_PODS_WATCH')
        WHEN 'CATALOG_ROLE_REVIEW_ACCESSORY_APPLE_WATCH'
            THEN metric_ IN ('ACCESSORY_APPLE_WATCH','ACCESSORY_PODS_WATCH')
        ELSE false END, false)
$$;

CREATE FUNCTION catalog_attach_numerator_metrics(metric_ text) RETURNS text[]
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN metric_ IN ('ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
        THEN ARRAY[metric_,'ACCESSORY_PODS_WATCH']
        ELSE array_remove(ARRAY[metric_],NULL) END
$$;

CREATE VIEW case_attach_confirmed_facts_catalog AS
SELECT document.store_id, document.business_date,
       CASE WHEN document.document_kind = 'SALE' THEN document.employee_id
            ELSE source_employee.id END AS employee_id,
       CASE WHEN document.document_kind = 'SALE' THEN item.quantity
            ELSE -item.quantity END AS net_quantity,
       source.decision_target_code AS numerator_metric_code,
       NULL::text AS device_role,
       ARRAY[]::text[] AS denominator_metric_codes,
       NULL::text AS classification_issue_code
FROM sales_documents document
JOIN sales_document_items item ON item.sales_document_id = document.id
JOIN analytics_categories item_category ON item_category.id = item.analytics_category_id
JOIN case_attach_review_items source
  ON source.source_item_id = CASE WHEN document.document_kind = 'SALE'
     THEN item.id ELSE item.original_item_id END
 AND source.store_id = document.store_id
JOIN sales_documents original_document ON original_document.id = source.document_id
 AND original_document.connection_id = document.connection_id
 AND (document.document_kind = 'SALE' OR document.original_document_id = original_document.id)
 AND document.occurred_at >= original_document.occurred_at
 AND item.product_id = source.product_id
LEFT JOIN employees source_employee
  ON source_employee.connection_id = document.connection_id
 AND source_employee.external_id = document.attach_source_employee_external_id
WHERE document.document_kind IN ('SALE', 'RETURN')
  AND item_category.code <> 'EXCLUDE'
  AND source.decision_current
  AND source.decision_target_code IN ('CASE_APPLE_IPHONE', 'CASE_SAMSUNG', 'GLASS_IPHONE', 'GLASS_SAMSUNG', 'FILM_PHONE',
      'CHARGER_CABLE','ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
  AND NOT document.is_deleted AND NOT item.is_deleted;


CREATE VIEW case_attach_confirmed_facts_v3_catalog AS
SELECT document.store_id, document.business_date, document.employee_id,
       CASE WHEN document.document_kind = 'SALE' THEN item.quantity
            ELSE -item.quantity END AS net_quantity,
       source.decision_target_code AS numerator_metric_code,
       NULL::text AS device_role,
       ARRAY[]::text[] AS denominator_metric_codes,
       NULL::text AS classification_issue_code
FROM sales_documents document
JOIN sales_document_items item ON item.sales_document_id = document.id
JOIN analytics_categories item_category ON item_category.id = item.analytics_category_id
JOIN case_attach_review_items source
  ON source.source_item_id = CASE WHEN document.document_kind = 'SALE'
     THEN item.id ELSE item.original_item_id END
 AND source.store_id = document.store_id
JOIN sales_documents original_document ON original_document.id = source.document_id
 AND original_document.connection_id = document.connection_id
 AND (document.document_kind = 'SALE' OR document.original_document_id = original_document.id)
 AND document.occurred_at >= original_document.occurred_at
 AND item.product_id = source.product_id
WHERE document.document_kind IN ('SALE', 'RETURN')
  AND item_category.code <> 'EXCLUDE'
  AND source.decision_current
  AND source.decision_target_code IN ('CASE_APPLE_IPHONE', 'CASE_SAMSUNG', 'GLASS_IPHONE', 'GLASS_SAMSUNG', 'FILM_PHONE',
      'CHARGER_CABLE','ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
  AND NOT document.is_deleted AND NOT item.is_deleted;



-- A linked reviewed accessory return is owned by the review projection, even if
-- its saved category is inconsistent. Never also subtract an automatic numerator/base.
CREATE OR REPLACE VIEW attach_rate_automatic_item_facts_v3_catalog AS
WITH source_items AS (
    SELECT
        document.store_id,
        document.business_date,
        document.employee_id,
        document.document_kind,
        item.quantity,
        lower(item.product_name_snapshot) AS normalized_product_name,
        item.condition_type_snapshot,
        category.code AS category_code,
        category.device_family,
        category.counts_as_phone,
        category.counts_as_device,
        role_snapshot.state AS role_state,
        role_snapshot.policy_version AS role_policy,
        role_snapshot.outcome AS role_outcome,
        role_snapshot.role AS confirmed_role,
        CASE WHEN role_snapshot.item_id IS NOT NULL OR category.code IN
            ('OTHER_CASE','CASE_UNIVERSAL','GLASS_PHONE_UNRESOLVED','PROTECTIVE_FILM')
            THEN catalog_review_replaces_automatic(item.id) ELSE false END AS review_replaces_automatic,
        CASE WHEN role_snapshot.item_id IS NOT NULL OR category.code = 'PROTECTIVE_FILM'
            THEN catalog_role_pending_issue(item.id) END AS role_pending_issue
    FROM sales_documents document
    JOIN sales_document_items item ON item.sales_document_id = document.id
    JOIN analytics_categories category ON category.id = item.analytics_category_id
    LEFT JOIN catalog_sale_role_snapshot_states role_snapshot ON role_snapshot.item_id = item.id
    WHERE NOT document.is_deleted
      AND NOT item.is_deleted
      AND category.code <> 'EXCLUDE'
      AND NOT EXISTS (
          SELECT 1 FROM sales_document_items original_item
          JOIN sales_documents original ON original.id = original_item.sales_document_id
          JOIN analytics_categories original_category ON original_category.id = original_item.analytics_category_id
          WHERE document.document_kind = 'RETURN' AND original.document_kind = 'SALE'
            AND original_item.id = item.original_item_id AND original_item.product_id = item.product_id
            AND NOT original_item.is_deleted AND NOT original.is_deleted
            AND catalog_review_replaces_automatic(original_item.id)
            AND original.store_id = document.store_id AND original.connection_id = document.connection_id
            AND document.original_document_id = original.id AND document.occurred_at >= original.occurred_at
      )
), classified_items AS (
    SELECT
        source.*,
        CASE
            WHEN source.category_code = 'IPHONE_NEW_ASIS'
                 AND source.condition_type_snapshot IN ('NEW', 'ASIS')
                THEN 'IPHONE_NEW_ASIS'
            WHEN source.category_code = 'IPHONE_USED'
                 AND source.condition_type_snapshot = 'USED'
                THEN 'IPHONE_USED'
            WHEN source.category_code = 'SAMSUNG_NEW'
                 AND source.condition_type_snapshot = 'NEW'
                THEN 'SAMSUNG_NEW'
            WHEN source.category_code = 'SAMSUNG_USED'
                 AND source.condition_type_snapshot = 'USED'
                THEN 'SAMSUNG_USED'
            WHEN source.category_code IN (
                'IPHONE_NEW_ASIS', 'IPHONE_USED', 'SAMSUNG_NEW', 'SAMSUNG_USED'
            ) THEN NULL
            WHEN source.counts_as_phone
                THEN 'OTHER_PHONE'
            WHEN source.category_code = 'IPAD_MAC'
                 AND source.normalized_product_name ~ '(ipad|планшет)'
                THEN 'IPAD'
            WHEN source.category_code = 'IPAD_MAC'
                 AND source.normalized_product_name ~ '(macbook|макбук)'
                THEN 'MACBOOK'
            WHEN source.category_code = 'IPAD_MAC'
                 AND source.normalized_product_name ~ '(imac|mac mini)'
                THEN 'MAC_OTHER'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~ '(airpods|earpods)'
                THEN 'AIRPODS'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~ '(apple watch|iwatch)'
                THEN 'APPLE_WATCH'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~ '(playstation|sony ps)'
                THEN 'PLAYSTATION'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~
                     '(наушник|headphone|earphone|galaxy buds|sony wf-|sony wh-|marshall major)'
                THEN 'HEADPHONES'
            WHEN source.category_code = 'HEADPHONES_APPLE'
                THEN 'AIRPODS'
            WHEN source.category_code IN ('HEADPHONES_SAMSUNG', 'HEADPHONES_OTHER')
                THEN 'HEADPHONES'
            WHEN source.category_code = 'TABLET_APPLE' THEN 'IPAD'
            WHEN source.category_code = 'LAPTOP_APPLE' THEN 'MACBOOK'
            WHEN source.category_code = 'WATCH_APPLE' THEN 'APPLE_WATCH'
            WHEN source.category_code = 'GAME_CONSOLES'
                 AND source.normalized_product_name ~ '(playstation|sony ps)' THEN 'PLAYSTATION'
            WHEN source.counts_as_device
                THEN 'OTHER_DEVICE'
        END AS device_role,
        CASE
            WHEN source.review_replaces_automatic THEN NULL
            WHEN catalog_attach_role_category(source.category_code)
                 AND source.role_state = 'CURRENT'
                 AND source.role_policy = 'catalog-accessory-roles-v1'
                 AND source.role_outcome = 'ASSIGNED' THEN source.confirmed_role
            WHEN catalog_attach_role_category(source.category_code)
                 AND source.role_state = 'CURRENT'
                 AND source.role_policy = 'catalog-accessory-roles-v1'
                 AND source.role_outcome = 'NO_CONTRIBUTION' THEN NULL
            WHEN catalog_attach_role_category(source.category_code)
                 AND (source.role_state = 'STALE'
                      OR source.role_policy <> 'catalog-accessory-roles-v1'
                      OR source.role_outcome IN ('REVIEW_PRODUCT','REVIEW_SALE')) THEN NULL
            -- These three Care products are one Premium-service / Protection metric.
            WHEN source.normalized_product_name ~
                 '(privilege care|ultimate care|elite care)'
                THEN 'PREMIUM_PROTECTION'
            -- The catalogue has no explicit target-condition field for warranties.
            -- Check Discount variants are the semantically identifiable used-phone
            -- warranty family; the remaining Check variants are new-phone warranty.
            WHEN source.normalized_product_name ~ 'check[[:space:]]+dis(k|c)ount'
                THEN 'WARRANTY_GENERIC_USED'
            WHEN source.normalized_product_name ~ 'check'
                 AND source.category_code IN ('WARRANTY_GENERIC', 'PREMIUM_PROTECTION')
                THEN 'WARRANTY_GENERIC_NEW'
            WHEN source.category_code = 'ACCESSORY_PODS_WATCH'
                 AND source.normalized_product_name !~ '(samsung|galaxy|buds|airtag)'
                THEN 'ACCESSORY_PODS_WATCH'
            WHEN source.category_code IN ('ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
                THEN source.category_code
            WHEN source.category_code = 'ACCESSORY_IPAD'
                THEN 'ACCESSORY_IPAD'
            WHEN source.category_code = 'ACCESSORY_IPAD_MAC'
                 AND source.normalized_product_name ~ '(ipad|планшет|apple pencil|pencil)'
                THEN 'ACCESSORY_IPAD'
            WHEN source.category_code IN (
                'CASE_APPLE_IPHONE',
                'CHARGER_CABLE', 'POWER_BANK',
                'GLASS_IPHONE',
                'GLASS_CAMERA_IPHONE',
                'FILM_PHONE',
                'SETUP_SERVICE',
                'CASE_SAMSUNG',
                'GLASS_SAMSUNG',
                'GLASS_CAMERA_SAMSUNG'
            ) THEN source.category_code
        END AS numerator_metric_code
    FROM source_items source
)
SELECT
    classified.store_id,
    classified.business_date,
    classified.employee_id,
    CASE classified.document_kind
        WHEN 'SALE' THEN classified.quantity
        ELSE -classified.quantity
    END AS net_quantity,
    classified.numerator_metric_code,
    classified.device_role,
    (CASE classified.device_role
        WHEN 'IPHONE_NEW_ASIS' THEN ARRAY[
            'CASE_APPLE_IPHONE', 'CHARGER_CABLE', 'POWER_BANK', 'GLASS_IPHONE',
            'GLASS_CAMERA_IPHONE', 'FILM_PHONE', 'SETUP_SERVICE',
            'WARRANTY_GENERIC_NEW', 'PREMIUM_PROTECTION'
        ]
        WHEN 'IPHONE_USED' THEN ARRAY[
            'CASE_APPLE_IPHONE', 'CHARGER_CABLE', 'POWER_BANK', 'GLASS_IPHONE',
            'GLASS_CAMERA_IPHONE', 'FILM_PHONE', 'SETUP_SERVICE',
            'WARRANTY_GENERIC_USED', 'PREMIUM_PROTECTION'
        ]
        WHEN 'SAMSUNG_NEW' THEN ARRAY[
            'CHARGER_CABLE', 'POWER_BANK', 'FILM_PHONE', 'SETUP_SERVICE', 'CASE_SAMSUNG',
            'GLASS_SAMSUNG', 'GLASS_CAMERA_SAMSUNG',
            'WARRANTY_GENERIC_NEW', 'PREMIUM_PROTECTION'
        ]
        WHEN 'SAMSUNG_USED' THEN ARRAY[
            'CHARGER_CABLE', 'POWER_BANK', 'FILM_PHONE', 'SETUP_SERVICE', 'CASE_SAMSUNG',
            'GLASS_SAMSUNG', 'GLASS_CAMERA_SAMSUNG',
            'WARRANTY_GENERIC_USED', 'PREMIUM_PROTECTION'
        ]
        WHEN 'OTHER_PHONE' THEN ARRAY[
            'CHARGER_CABLE', 'POWER_BANK', 'FILM_PHONE', 'SETUP_SERVICE'
        ]
        WHEN 'IPAD' THEN ARRAY['ACCESSORY_IPAD', 'PREMIUM_PROTECTION']
        WHEN 'MACBOOK' THEN ARRAY['SETUP_SERVICE', 'PREMIUM_PROTECTION']
        WHEN 'MAC_OTHER' THEN ARRAY['PREMIUM_PROTECTION']
        WHEN 'AIRPODS' THEN ARRAY['ACCESSORY_PODS_WATCH', 'PREMIUM_PROTECTION']
        WHEN 'APPLE_WATCH' THEN ARRAY['ACCESSORY_PODS_WATCH', 'PREMIUM_PROTECTION']
        WHEN 'HEADPHONES' THEN ARRAY['PREMIUM_PROTECTION']
        WHEN 'PLAYSTATION' THEN ARRAY['SETUP_SERVICE', 'PREMIUM_PROTECTION']
        ELSE ARRAY[]::text[]
    END || CASE
        WHEN classified.device_role = 'AIRPODS'
             AND classified.normalized_product_name ~ 'airpods' THEN ARRAY['ACCESSORY_AIRPODS']
        WHEN classified.device_role = 'APPLE_WATCH' THEN ARRAY['ACCESSORY_APPLE_WATCH']
        ELSE ARRAY[]::text[] END) AS denominator_metric_codes,
    CASE
        WHEN classified.role_pending_issue IS NOT NULL THEN classified.role_pending_issue
        WHEN classified.category_code IN ('WARRANTY_GENERIC', 'PREMIUM_PROTECTION')
             AND classified.numerator_metric_code IS NULL
            THEN 'WARRANTY_TARGET_UNRESOLVED'
        WHEN classified.category_code = 'ACCESSORY_IPAD_MAC'
             AND classified.numerator_metric_code IS NULL
            THEN 'IPAD_ACCESSORY_TARGET_UNRESOLVED'
        WHEN (
            classified.category_code = 'IPHONE_NEW_ASIS'
            AND classified.condition_type_snapshot NOT IN ('NEW', 'ASIS')
        ) OR (
            classified.category_code = 'IPHONE_USED'
            AND classified.condition_type_snapshot <> 'USED'
        ) OR (
            classified.category_code = 'SAMSUNG_NEW'
            AND classified.condition_type_snapshot <> 'NEW'
        ) OR (
            classified.category_code = 'SAMSUNG_USED'
            AND classified.condition_type_snapshot <> 'USED'
        ) THEN 'DEVICE_CONDITION_UNKNOWN'
        WHEN classified.numerator_metric_code = 'ACCESSORY_PODS_WATCH'
             AND catalog_pods_watch_accessory_subtype(classified.category_code,
                 classified.normalized_product_name) IS NULL THEN 'PODS_WATCH_SUBTYPE_UNRESOLVED'
    END AS classification_issue_code,
    CASE
        WHEN classified.numerator_metric_code IN ('ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
            THEN ARRAY[classified.numerator_metric_code,'ACCESSORY_PODS_WATCH']
        WHEN classified.numerator_metric_code = 'ACCESSORY_PODS_WATCH'
            THEN array_remove(ARRAY['ACCESSORY_PODS_WATCH',
                catalog_pods_watch_accessory_subtype(classified.category_code,
                    classified.normalized_product_name)], NULL)
        ELSE array_remove(ARRAY[classified.numerator_metric_code], NULL)
    END AS numerator_metric_codes
FROM classified_items classified;


CREATE OR REPLACE VIEW attach_rate_ordinary_item_facts_v4_catalog AS
WITH source_items AS (
    SELECT
        document.store_id,
        document.business_date,
        CASE WHEN document.document_kind = 'SALE' THEN document.employee_id
             ELSE source_employee.id END AS employee_id,
        document.document_kind,
        item.quantity,
        lower(item.product_name_snapshot) AS normalized_product_name,
        item.condition_type_snapshot,
        category.code AS category_code,
        category.device_family,
        category.counts_as_phone,
        category.counts_as_device,
        role_snapshot.state AS role_state,
        role_snapshot.policy_version AS role_policy,
        role_snapshot.outcome AS role_outcome,
        role_snapshot.role AS confirmed_role,
        CASE WHEN role_snapshot.item_id IS NOT NULL OR category.code IN
            ('OTHER_CASE','CASE_UNIVERSAL','GLASS_PHONE_UNRESOLVED','PROTECTIVE_FILM')
            THEN catalog_review_replaces_automatic(item.id) ELSE false END AS review_replaces_automatic,
        CASE WHEN role_snapshot.item_id IS NOT NULL OR category.code = 'PROTECTIVE_FILM'
            THEN catalog_role_pending_issue(item.id) END AS role_pending_issue
    FROM sales_documents document
    LEFT JOIN employees source_employee
      ON source_employee.connection_id = document.connection_id
     AND source_employee.external_id = document.attach_source_employee_external_id
    JOIN sales_document_items item ON item.sales_document_id = document.id
    JOIN analytics_categories category ON category.id = item.analytics_category_id
    LEFT JOIN catalog_sale_role_snapshot_states role_snapshot ON role_snapshot.item_id = item.id
    WHERE NOT document.is_deleted
      AND NOT item.is_deleted
      AND category.code <> 'EXCLUDE'
      AND NOT EXISTS (
          SELECT 1 FROM sales_document_items original_item
          JOIN sales_documents original ON original.id = original_item.sales_document_id
          JOIN analytics_categories original_category ON original_category.id = original_item.analytics_category_id
          WHERE document.document_kind = 'RETURN' AND original.document_kind = 'SALE'
            AND original_item.id = item.original_item_id AND original_item.product_id = item.product_id
            AND NOT original_item.is_deleted AND NOT original.is_deleted
            AND catalog_review_replaces_automatic(original_item.id)
            AND original.store_id = document.store_id AND original.connection_id = document.connection_id
            AND document.original_document_id = original.id AND document.occurred_at >= original.occurred_at
      )
), classified_items AS (
    SELECT
        source.*,
        CASE
            WHEN source.category_code = 'IPHONE_NEW_ASIS'
                 AND source.condition_type_snapshot IN ('NEW', 'ASIS')
                THEN 'IPHONE_NEW_ASIS'
            WHEN source.category_code = 'IPHONE_USED'
                 AND source.condition_type_snapshot = 'USED'
                THEN 'IPHONE_USED'
            WHEN source.category_code = 'SAMSUNG_NEW'
                 AND source.condition_type_snapshot = 'NEW'
                THEN 'SAMSUNG_NEW'
            WHEN source.category_code = 'SAMSUNG_USED'
                 AND source.condition_type_snapshot = 'USED'
                THEN 'SAMSUNG_USED'
            WHEN source.category_code IN (
                'IPHONE_NEW_ASIS', 'IPHONE_USED', 'SAMSUNG_NEW', 'SAMSUNG_USED'
            ) THEN NULL
            WHEN source.counts_as_phone
                THEN 'OTHER_PHONE'
            WHEN source.category_code = 'IPAD_MAC'
                 AND source.normalized_product_name ~ '(ipad|планшет)'
                THEN 'IPAD'
            WHEN source.category_code = 'IPAD_MAC'
                 AND source.normalized_product_name ~ '(macbook|макбук)'
                THEN 'MACBOOK'
            WHEN source.category_code = 'IPAD_MAC'
                 AND source.normalized_product_name ~ '(imac|mac mini)'
                THEN 'MAC_OTHER'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~ 'airpods'
                THEN 'AIRPODS'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~ '(apple watch|iwatch)'
                THEN 'APPLE_WATCH'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~ '(playstation|sony ps)'
                THEN 'PLAYSTATION'
            WHEN source.category_code = 'PODS_WATCH_OTHER_DEVICE'
                 AND source.normalized_product_name ~
                     '(earpods|наушник|headphone|earphone|galaxy buds|sony wf-|sony wh-|marshall major)'
                THEN 'HEADPHONES'
            WHEN source.category_code = 'HEADPHONES_APPLE'
                 AND source.normalized_product_name ~ 'airpods'
                THEN 'AIRPODS'
            WHEN source.category_code IN (
                'HEADPHONES_APPLE', 'HEADPHONES_SAMSUNG', 'HEADPHONES_OTHER'
            ) THEN 'HEADPHONES'
            WHEN source.category_code = 'TABLET_APPLE' THEN 'IPAD'
            WHEN source.category_code = 'LAPTOP_APPLE' THEN 'MACBOOK'
            WHEN source.category_code = 'WATCH_APPLE' THEN 'APPLE_WATCH'
            WHEN source.category_code = 'GAME_CONSOLES'
                 AND source.normalized_product_name ~ '(playstation|sony ps)' THEN 'PLAYSTATION'
            WHEN source.counts_as_device
                THEN 'OTHER_DEVICE'
        END AS device_role,
        CASE
            WHEN source.review_replaces_automatic THEN NULL
            WHEN catalog_attach_role_category(source.category_code)
                 AND source.role_state = 'CURRENT'
                 AND source.role_policy = 'catalog-accessory-roles-v1'
                 AND source.role_outcome = 'ASSIGNED' THEN source.confirmed_role
            WHEN catalog_attach_role_category(source.category_code)
                 AND source.role_state = 'CURRENT'
                 AND source.role_policy = 'catalog-accessory-roles-v1'
                 AND source.role_outcome = 'NO_CONTRIBUTION' THEN NULL
            WHEN catalog_attach_role_category(source.category_code)
                 AND (source.role_state = 'STALE'
                      OR source.role_policy <> 'catalog-accessory-roles-v1'
                      OR source.role_outcome IN ('REVIEW_PRODUCT','REVIEW_SALE')) THEN NULL
            WHEN attach_is_care(source.normalized_product_name)
                THEN 'PREMIUM_PROTECTION'
            WHEN source.category_code = 'OTHER_ACCESSORY_PRODUCT'
                 AND source.normalized_product_name ~ '(переходник|адаптер)'
                 AND source.normalized_product_name ~ '(заряд|питан|power[[:space:]]+adapter|wall[[:space:]]+charger|сзу|азу|бзу)'
                THEN 'CHARGER_CABLE'
            WHEN source.category_code = 'SETUP_SERVICE'
                 AND source.normalized_product_name !~ '(ремонт|repair|замена|заменить)'
                 AND source.normalized_product_name ~
                     '(настрой|активац|office|калибров|очистк|чистка устройства|перенос|установ|уч[её]тн|обновление программ|перезагруз|подзаряд|сброс|защитного покрытия)'
                THEN 'SETUP_SERVICE'
            WHEN source.category_code = 'ACCESSORY_PODS_WATCH'
                 AND source.normalized_product_name !~ '(samsung|galaxy|buds|airtag)'
                THEN 'ACCESSORY_PODS_WATCH'
            WHEN source.category_code IN ('ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
                THEN source.category_code
            WHEN source.category_code = 'ACCESSORY_IPAD'
                THEN 'ACCESSORY_IPAD'
            WHEN source.category_code = 'ACCESSORY_IPAD_MAC'
                 AND source.normalized_product_name ~ '(ipad|планшет|apple pencil|pencil)'
                THEN 'ACCESSORY_IPAD'
            WHEN source.category_code IN (
                'CASE_APPLE_IPHONE',
                'CHARGER_CABLE', 'POWER_BANK',
                'GLASS_IPHONE',
                'GLASS_CAMERA_IPHONE',
                'FILM_PHONE',
                'CASE_SAMSUNG',
                'GLASS_SAMSUNG',
                'GLASS_CAMERA_SAMSUNG'
            ) THEN source.category_code
        END AS numerator_metric_code
    FROM source_items source
)
SELECT
    classified.store_id,
    classified.business_date,
    classified.employee_id,
    CASE classified.document_kind
        WHEN 'SALE' THEN classified.quantity
        ELSE -classified.quantity
    END AS net_quantity,
    classified.numerator_metric_code,
    classified.device_role,
    (CASE classified.device_role
        WHEN 'IPHONE_NEW_ASIS' THEN ARRAY[
            'CASE_APPLE_IPHONE', 'CHARGER_CABLE', 'POWER_BANK', 'GLASS_IPHONE',
            'GLASS_CAMERA_IPHONE', 'FILM_PHONE', 'SETUP_SERVICE',
            'PREMIUM_PROTECTION'
        ]
        WHEN 'IPHONE_USED' THEN ARRAY[
            'CASE_APPLE_IPHONE', 'CHARGER_CABLE', 'POWER_BANK', 'GLASS_IPHONE',
            'GLASS_CAMERA_IPHONE', 'FILM_PHONE', 'SETUP_SERVICE',
            'PREMIUM_PROTECTION'
        ]
        WHEN 'SAMSUNG_NEW' THEN ARRAY[
            'CHARGER_CABLE', 'POWER_BANK', 'FILM_PHONE', 'SETUP_SERVICE', 'CASE_SAMSUNG',
            'GLASS_SAMSUNG', 'GLASS_CAMERA_SAMSUNG',
            'PREMIUM_PROTECTION'
        ]
        WHEN 'SAMSUNG_USED' THEN ARRAY[
            'CHARGER_CABLE', 'POWER_BANK', 'FILM_PHONE', 'SETUP_SERVICE', 'CASE_SAMSUNG',
            'GLASS_SAMSUNG', 'GLASS_CAMERA_SAMSUNG',
            'PREMIUM_PROTECTION'
        ]
        WHEN 'OTHER_PHONE' THEN ARRAY[
            'CHARGER_CABLE', 'POWER_BANK', 'FILM_PHONE', 'SETUP_SERVICE'
        ]
        WHEN 'IPAD' THEN ARRAY['ACCESSORY_IPAD', 'PREMIUM_PROTECTION']
        WHEN 'MACBOOK' THEN ARRAY['SETUP_SERVICE', 'PREMIUM_PROTECTION']
        WHEN 'MAC_OTHER' THEN ARRAY['PREMIUM_PROTECTION']
        WHEN 'AIRPODS' THEN ARRAY['ACCESSORY_PODS_WATCH', 'PREMIUM_PROTECTION']
        WHEN 'APPLE_WATCH' THEN ARRAY['ACCESSORY_PODS_WATCH', 'PREMIUM_PROTECTION']
        WHEN 'HEADPHONES' THEN ARRAY['PREMIUM_PROTECTION']
        WHEN 'PLAYSTATION' THEN ARRAY['SETUP_SERVICE', 'PREMIUM_PROTECTION']
        ELSE ARRAY[]::text[]
    END || CASE
        WHEN classified.device_role = 'AIRPODS'
             AND classified.normalized_product_name ~ 'airpods' THEN ARRAY['ACCESSORY_AIRPODS']
        WHEN classified.device_role = 'APPLE_WATCH' THEN ARRAY['ACCESSORY_APPLE_WATCH']
        ELSE ARRAY[]::text[] END) AS denominator_metric_codes,
    CASE
        WHEN classified.role_pending_issue IS NOT NULL THEN classified.role_pending_issue
        WHEN classified.category_code = 'ACCESSORY_IPAD_MAC'
             AND classified.numerator_metric_code IS NULL
            THEN 'IPAD_ACCESSORY_TARGET_UNRESOLVED'
        WHEN (
            classified.category_code = 'IPHONE_NEW_ASIS'
            AND classified.condition_type_snapshot NOT IN ('NEW', 'ASIS')
        ) OR (
            classified.category_code = 'IPHONE_USED'
            AND classified.condition_type_snapshot <> 'USED'
        ) OR (
            classified.category_code = 'SAMSUNG_NEW'
            AND classified.condition_type_snapshot <> 'NEW'
        ) OR (
            classified.category_code = 'SAMSUNG_USED'
            AND classified.condition_type_snapshot <> 'USED'
        ) THEN 'DEVICE_CONDITION_UNKNOWN'
        WHEN classified.numerator_metric_code = 'ACCESSORY_PODS_WATCH'
             AND catalog_pods_watch_accessory_subtype(classified.category_code,
                 classified.normalized_product_name) IS NULL THEN 'PODS_WATCH_SUBTYPE_UNRESOLVED'
    END AS classification_issue_code,
    CASE
        WHEN classified.numerator_metric_code IN ('ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
            THEN ARRAY[classified.numerator_metric_code,'ACCESSORY_PODS_WATCH']
        WHEN classified.numerator_metric_code = 'ACCESSORY_PODS_WATCH'
            THEN array_remove(ARRAY['ACCESSORY_PODS_WATCH',
                catalog_pods_watch_accessory_subtype(classified.category_code,
                    classified.normalized_product_name)], NULL)
        ELSE array_remove(ARRAY[classified.numerator_metric_code], NULL)
    END AS numerator_metric_codes
FROM classified_items classified;


CREATE VIEW catalog_pending_role_returns AS
SELECT d.store_id,d.business_date,d.employee_id AS employee_v3,
       source_employee.id AS employee_v4,-i.quantity AS net_quantity,
       catalog_role_pending_issue(i.id) AS classification_issue_code
FROM sales_documents d
JOIN sales_document_items i ON i.sales_document_id = d.id
JOIN analytics_categories c ON c.id = i.analytics_category_id
LEFT JOIN employees source_employee ON source_employee.connection_id = d.connection_id
  AND source_employee.external_id = d.attach_source_employee_external_id
WHERE d.document_kind = 'RETURN' AND NOT d.is_deleted AND NOT i.is_deleted
  AND c.code <> 'EXCLUDE'
  AND catalog_review_replaces_automatic(catalog_exact_return_original(i.id))
  AND catalog_role_pending_issue(i.id) IS NOT NULL;

CREATE OR REPLACE VIEW attach_rate_item_facts_v3_catalog AS
SELECT * FROM attach_rate_automatic_item_facts_v3_catalog
UNION ALL
SELECT f.*, catalog_attach_numerator_metrics(f.numerator_metric_code)
FROM case_attach_confirmed_facts_v3_catalog f
UNION ALL
SELECT store_id,business_date,employee_v3,net_quantity,NULL,NULL,ARRAY[]::text[],classification_issue_code,ARRAY[]::text[]
FROM catalog_pending_role_returns;

CREATE OR REPLACE VIEW attach_rate_ordinary_item_facts_v4_catalog_with_reviews AS
SELECT * FROM attach_rate_ordinary_item_facts_v4_catalog
UNION ALL
SELECT f.*, catalog_attach_numerator_metrics(f.numerator_metric_code)
FROM case_attach_confirmed_facts_catalog f
UNION ALL
SELECT store_id,business_date,employee_v4,net_quantity,NULL,NULL,ARRAY[]::text[],classification_issue_code,ARRAY[]::text[]
FROM catalog_pending_role_returns;

CREATE OR REPLACE VIEW attach_rate_item_facts_v4_catalog AS
SELECT * FROM attach_rate_ordinary_item_facts_v4_catalog_with_reviews
UNION ALL
SELECT store_id,business_date,employee_id,net_quantity,
       'WARRANTY_GENERIC_' || device_type,NULL,ARRAY[]::text[],NULL,
       ARRAY['WARRANTY_GENERIC_' || device_type]
FROM warranty_attach_effective_allocations
UNION ALL
SELECT device.store_id,device.business_date,device.employee_id,
       device.quantity - COALESCE((SELECT sum(returned.quantity)
           FROM warranty_attach_items returned
           WHERE returned.original_item_id = device.id AND returned.active
             AND returned.document_kind = 'RETURN'),0),
       NULL,device.device_type,ARRAY['WARRANTY_GENERIC_' || device.device_type],NULL,ARRAY[]::text[]
FROM warranty_attach_items device
WHERE device.active AND device.document_kind = 'SALE' AND device.device_type IS NOT NULL;

COMMENT ON VIEW attach_rate_item_facts_v4_catalog IS
    'Catalog details on v4 attribution. AirPods/Watch leaves share one source row with their summary; unresolved legacy remains explicit.';

COMMENT ON TABLE catalog_sale_role_snapshots IS
    'Immutable dated accessory roles consumed by catalog projections; current decisions apply prospectively, with no historical backfill.';
