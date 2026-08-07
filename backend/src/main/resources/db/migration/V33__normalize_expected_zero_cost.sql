-- LiveSklad does not consistently mark services and care products as work.
-- The approved analytics category is the authoritative signal for whether a
-- zero cost is expected.
UPDATE sales_document_items item
SET cost_quality = 'ZERO_SERVICE'
FROM analytics_categories category
WHERE category.id = item.analytics_category_id
  AND category.category_kind IN ('SERVICE', 'WARRANTY', 'PROTECTION')
  AND item.cost_amount = 0
  AND item.cost_quality = 'ZERO_UNEXPECTED';

UPDATE data_quality_issues issue
SET status = 'RESOLVED',
    resolved_at = GREATEST(CURRENT_TIMESTAMP, issue.detected_at),
    resolved_by = NULL
WHERE issue.status = 'OPEN'
  AND (
      (
          issue.entity_type = 'SALE_ITEM'
          AND issue.issue_code = 'ZERO_UNEXPECTED_COST'
      )
      OR (
          issue.entity_type = 'RETURN_ITEM'
          AND issue.issue_code = 'RETURN_ZERO_UNEXPECTED_COST'
      )
  )
  AND EXISTS (
      SELECT 1
      FROM sales_document_items item
      JOIN sales_documents document ON document.id = item.sales_document_id
      JOIN analytics_categories category ON category.id = item.analytics_category_id
      WHERE issue.entity_id = document.connection_id::text || ':' || item.external_id
        AND item.cost_amount = 0
        AND item.cost_quality = 'ZERO_SERVICE'
        AND category.category_kind IN ('SERVICE', 'WARRANTY', 'PROTECTION')
        AND (
            (issue.entity_type = 'SALE_ITEM' AND document.document_kind = 'SALE')
            OR (issue.entity_type = 'RETURN_ITEM' AND document.document_kind = 'RETURN')
        )
  );
