-- Preserve the pending-role predicates and exact-original return semantics.
-- Without a correlated optimization barrier PostgreSQL can evaluate the entire review queue
-- for each item, including nested role/fingerprint checks for unrelated sales.
-- OFFSET 0 preserves every row while keeping the review lookup bounded to this item/original.
CREATE OR REPLACE FUNCTION catalog_role_pending_issue(item_ uuid) RETURNS text
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
    LEFT JOIN LATERAL (
        SELECT review.* FROM case_attach_review_items review
        WHERE review.source_item_id =
            CASE WHEN d.document_kind = 'SALE' THEN i.id ELSE catalog_exact_return_original(i.id) END
        OFFSET 0
    ) r ON true
    WHERE i.id = item_
$$;
