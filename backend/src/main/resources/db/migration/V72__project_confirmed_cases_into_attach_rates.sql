-- Keep both existing calculation versions intact; confirmed case decisions
-- are additional numerator-only facts. Receipt suggestions are not included.
-- v3 retains the legacy financial document employee on returns.
CREATE VIEW case_attach_confirmed_facts_v3 AS
SELECT document.store_id, document.business_date, document.employee_id,
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
WHERE document.document_kind IN ('SALE', 'RETURN')
  AND source.decision_current
  AND source.decision_target_code IN ('CASE_APPLE_IPHONE', 'CASE_SAMSUNG')
  AND NOT document.is_deleted AND NOT item.is_deleted;

CREATE VIEW attach_rate_item_facts_v3_with_cases AS
SELECT * FROM attach_rate_item_facts_v3
UNION ALL
SELECT * FROM case_attach_confirmed_facts_v3;

CREATE VIEW attach_rate_item_facts_v4_with_cases AS
SELECT * FROM attach_rate_item_facts_v4
UNION ALL
SELECT * FROM case_attach_confirmed_facts;
