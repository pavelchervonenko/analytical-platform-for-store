-- Extend the existing immutable per-sale review, without reclassifying financial facts.
ALTER TABLE case_attach_decisions DROP CONSTRAINT case_attach_decisions_target_code_check;
ALTER TABLE case_attach_decisions ADD CONSTRAINT case_attach_decisions_target_code_check
    CHECK (target_code IN ('CASE_APPLE_IPHONE','CASE_SAMSUNG','CASE_OTHER_DEVICE',
        'GLASS_IPHONE','GLASS_SAMSUNG','GLASS_OTHER','FILM_PHONE','FILM_NON_PHONE','DEFER'));

CREATE FUNCTION accessory_review_allowed_targets(category_ text)
RETURNS text[] LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN category_ IN ('OTHER_CASE','CASE_UNIVERSAL')
            THEN ARRAY['CASE_APPLE_IPHONE','CASE_SAMSUNG','CASE_OTHER_DEVICE','DEFER']
        WHEN category_ = 'GLASS_PHONE_UNRESOLVED'
            THEN ARRAY['GLASS_IPHONE','GLASS_SAMSUNG','GLASS_OTHER','DEFER']
        WHEN category_ = 'PROTECTIVE_FILM'
            THEN ARRAY['FILM_PHONE','FILM_NON_PHONE','DEFER']
        ELSE ARRAY[]::text[] END
$$;

CREATE FUNCTION accessory_review_target_allowed(category_ text, target_ text)
RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
    SELECT COALESCE(target_ = ANY(accessory_review_allowed_targets(category_)), false)
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
       md5(concat_ws('|', item.product_id::text, item.product_name_snapshot,
           item.quantity::text, item.analytics_category_id::text,
           document.id::text, document.store_id::text, document.business_date::text))
           AS source_fingerprint,
       CASE WHEN decision.store_id = document.store_id THEN decision.id END AS decision_id,
       CASE WHEN decision.store_id = document.store_id THEN decision.target_code END
           AS decision_target_code,
       decision.revision AS decision_revision,
       CASE WHEN decision.store_id = document.store_id THEN decision.reason END
           AS decision_reason,
       decision.store_id = document.store_id
           AND decision.source_fingerprint = md5(concat_ws('|', item.product_id::text,
           item.product_name_snapshot, item.quantity::text,
           item.analytics_category_id::text,
           document.id::text, document.store_id::text,
           document.business_date::text)) AS decision_current,
       category.code AS category_code,
       accessory_review_allowed_targets(category.code) AS allowed_targets
FROM sales_document_items item
JOIN sales_documents document ON document.id = item.sales_document_id
JOIN products product ON product.id = item.product_id
JOIN analytics_categories category ON category.id = item.analytics_category_id
LEFT JOIN phone_brands ON phone_brands.document_id = document.id
LEFT JOIN case_attach_current_decisions decision ON decision.source_item_id = item.id
WHERE category.code IN ('OTHER_CASE', 'CASE_UNIVERSAL', 'GLASS_PHONE_UNRESOLVED', 'PROTECTIVE_FILM')
  AND document.document_kind = 'SALE'
  AND NOT document.is_deleted AND NOT item.is_deleted;


CREATE OR REPLACE FUNCTION validate_case_attach_decision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source case_attach_review_items;
BEGIN
    -- Serialize decisions against both competing reviewers and source sync corrections.
    PERFORM 1 FROM sales_documents d JOIN sales_document_items i ON i.sales_document_id = d.id
        WHERE i.id = NEW.source_item_id FOR SHARE OF d;
    PERFORM 1 FROM sales_document_items WHERE id = NEW.source_item_id FOR UPDATE;
    SELECT * INTO source FROM case_attach_review_items WHERE source_item_id = NEW.source_item_id;
    IF NOT FOUND OR source.store_id <> NEW.store_id
       OR source.source_fingerprint <> NEW.source_fingerprint
       OR NEW.revision <> COALESCE(source.decision_revision, 0) + 1
       OR NOT accessory_review_target_allowed(source.category_code, NEW.target_code) THEN
        RAISE EXCEPTION 'Accessory source changed, target or decision revision is invalid'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE VIEW case_attach_confirmed_facts AS
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
  AND source.decision_target_code IN ('CASE_APPLE_IPHONE', 'CASE_SAMSUNG', 'GLASS_IPHONE', 'GLASS_SAMSUNG', 'FILM_PHONE')
  AND NOT document.is_deleted AND NOT item.is_deleted;


CREATE OR REPLACE VIEW case_attach_confirmed_facts_v3 AS
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
  AND source.decision_target_code IN ('CASE_APPLE_IPHONE', 'CASE_SAMSUNG', 'GLASS_IPHONE', 'GLASS_SAMSUNG', 'FILM_PHONE')
  AND NOT document.is_deleted AND NOT item.is_deleted;



-- A linked reviewed accessory return is owned by the review projection, even if
-- its saved category is inconsistent. Never also subtract an automatic numerator/base.
CREATE OR REPLACE VIEW attach_rate_item_facts_v3 AS
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
        category.counts_as_device
    FROM sales_documents document
    JOIN sales_document_items item ON item.sales_document_id = document.id
    JOIN analytics_categories category ON category.id = item.analytics_category_id
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
            AND cardinality(accessory_review_allowed_targets(original_category.code)) > 0
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
            WHEN source.counts_as_device
                THEN 'OTHER_DEVICE'
        END AS device_role,
        CASE
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
    CASE classified.device_role
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
    END AS denominator_metric_codes,
    CASE
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
    END AS classification_issue_code
FROM classified_items classified;

CREATE OR REPLACE VIEW attach_rate_ordinary_item_facts_v4 AS
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
        category.counts_as_device
    FROM sales_documents document
    LEFT JOIN employees source_employee
      ON source_employee.connection_id = document.connection_id
     AND source_employee.external_id = document.attach_source_employee_external_id
    JOIN sales_document_items item ON item.sales_document_id = document.id
    JOIN analytics_categories category ON category.id = item.analytics_category_id
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
            AND cardinality(accessory_review_allowed_targets(original_category.code)) > 0
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
            WHEN source.counts_as_device
                THEN 'OTHER_DEVICE'
        END AS device_role,
        CASE
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
    CASE classified.device_role
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
    END AS denominator_metric_codes,
    CASE
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
    END AS classification_issue_code
FROM classified_items classified;

-- Ordinary-return quality must include confirmed reviews as well as automatic facts.
-- Warranty return attribution intentionally remains outside this date/author projection.
CREATE VIEW attach_rate_ordinary_item_facts_v4_with_reviews AS
SELECT * FROM attach_rate_ordinary_item_facts_v4
UNION ALL
SELECT * FROM case_attach_confirmed_facts;

COMMENT ON VIEW case_attach_review_items IS
    'Per-sale compatibility review: unknown/universal cases, unknown phone glass and neutral films.';
COMMENT ON VIEW case_attach_confirmed_facts IS
    'Confirmed compatible accessory numerator only; return inherits its exact original sale. Money unchanged.';
