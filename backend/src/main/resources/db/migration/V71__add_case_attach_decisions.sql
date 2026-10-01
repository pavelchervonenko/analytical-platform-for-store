-- Decisions are an attach-rate projection, not financial/payroll reclassification.
-- An old decision becomes ineffective if the source sale line changes on sync.
CREATE TABLE case_attach_decisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_item_id uuid NOT NULL REFERENCES sales_document_items(id),
    store_id uuid NOT NULL REFERENCES stores(id),
    target_code text NOT NULL CHECK (target_code IN (
        'CASE_APPLE_IPHONE', 'CASE_SAMSUNG', 'CASE_OTHER_DEVICE', 'DEFER'
    )),
    source_fingerprint text NOT NULL,
    reason text NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 1000),
    revision integer NOT NULL CHECK (revision > 0),
    actor_id uuid REFERENCES app_users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_item_id, revision)
);
CREATE INDEX ix_case_attach_decisions_source_latest
    ON case_attach_decisions (source_item_id, revision DESC);
CREATE INDEX ix_case_attach_decisions_store
    ON case_attach_decisions (store_id, created_at DESC);
CREATE FUNCTION deny_case_attach_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Case attribution history is immutable' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER case_attach_decisions_immutable BEFORE UPDATE OR DELETE ON case_attach_decisions
    FOR EACH ROW EXECUTE FUNCTION deny_case_attach_history_change();


CREATE TRIGGER case_attach_changed AFTER INSERT ON case_attach_decisions
    FOR EACH ROW EXECUTE FUNCTION mark_warranty_attribution_changed();

CREATE VIEW case_attach_current_decisions AS
SELECT DISTINCT ON (source_item_id) *
FROM case_attach_decisions
ORDER BY source_item_id, revision DESC;

-- All cases stay visible for review, including those with no telephone in the
-- same receipt.  Co-occurrence is only a suggestion, never a confirmed fact.
CREATE VIEW case_attach_review_items AS
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
           document.business_date::text)) AS decision_current
FROM sales_document_items item
JOIN sales_documents document ON document.id = item.sales_document_id
JOIN products product ON product.id = item.product_id
JOIN analytics_categories category ON category.id = item.analytics_category_id
LEFT JOIN phone_brands ON phone_brands.document_id = document.id
LEFT JOIN case_attach_current_decisions decision ON decision.source_item_id = item.id
WHERE category.code = 'OTHER_CASE'
  AND document.document_kind = 'SALE'
  AND NOT document.is_deleted AND NOT item.is_deleted;

-- Confirmed individual sales and their source-linked returns feed the existing
-- v3/v4 numerators. A return without an original item cannot be guessed.
CREATE VIEW case_attach_confirmed_facts AS
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
JOIN case_attach_review_items source
  ON source.source_item_id = CASE WHEN document.document_kind = 'SALE'
     THEN item.id ELSE item.original_item_id END
 AND source.store_id = document.store_id
LEFT JOIN employees source_employee
  ON source_employee.connection_id = document.connection_id
 AND source_employee.external_id = document.attach_source_employee_external_id
WHERE document.document_kind IN ('SALE', 'RETURN')
  AND source.decision_current
  AND source.decision_target_code IN ('CASE_APPLE_IPHONE', 'CASE_SAMSUNG')
  AND NOT document.is_deleted AND NOT item.is_deleted;

COMMENT ON VIEW case_attach_confirmed_facts IS
    'Confirmed case attach units only; receipt suggestions never enter official attach-rate.';

-- The database also rejects cross-store, stale or skipped-revision decisions.
CREATE FUNCTION validate_case_attach_decision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source case_attach_review_items;
BEGIN
    SELECT * INTO source FROM case_attach_review_items
    WHERE source_item_id = NEW.source_item_id;
    IF NOT FOUND OR source.store_id <> NEW.store_id
       OR source.source_fingerprint <> NEW.source_fingerprint
       OR NEW.revision <> COALESCE(source.decision_revision, 0) + 1 THEN
        RAISE EXCEPTION 'Case source changed or decision revision is invalid'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER case_attach_decision_valid BEFORE INSERT ON case_attach_decisions
    FOR EACH ROW EXECUTE FUNCTION validate_case_attach_decision();
