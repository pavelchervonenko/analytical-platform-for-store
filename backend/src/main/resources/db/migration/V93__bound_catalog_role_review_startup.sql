-- Avoid expanding the snapshot-state graph for items without an immutable snapshot.
-- Preserve the original review predicate, including stale roles and uncaptured returns.
CREATE OR REPLACE FUNCTION catalog_role_review_required(item_ uuid) RETURNS boolean
LANGUAGE plpgsql STABLE AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM catalog_sale_role_snapshots WHERE item_id = item_) THEN
        RETURN false;
    END IF;
    RETURN COALESCE((SELECT
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
        JOIN analytics_categories current_category ON current_category.id = i.analytics_category_id
        JOIN sales_documents d ON d.id = i.sales_document_id
        WHERE s.item_id = item_ AND s.state <> 'DELETED' AND d.document_kind = 'SALE'
          AND (catalog_attach_role_category(s.monetary_category)
               OR catalog_attach_role_category(current_category.code))), false);
END;
$$;

-- Filter to the exact item before the review predicate is applied. Without this
-- barrier the planner may evaluate accessory eligibility across the review queue
-- for each return's original item, including unrelated stores and documents.
CREATE OR REPLACE FUNCTION catalog_accessory_review_required(item_ uuid) RETURNS boolean
LANGUAGE plpgsql STABLE AS $$
BEGIN
    RETURN COALESCE((SELECT CASE
        WHEN c.code IN ('OTHER_CASE','CASE_UNIVERSAL','GLASS_PHONE_UNRESOLVED') THEN true
        WHEN c.code = 'PROTECTIVE_FILM'
            THEN NOT catalog_has_current_auto_role(i.id) OR catalog_role_review_required(i.id)
        WHEN c.code IN ('CHARGER_CABLE','ACCESSORY_AIRPODS','ACCESSORY_APPLE_WATCH')
            THEN catalog_role_review_required(i.id)
        ELSE false END OR (catalog_attach_role_category(c.code) AND EXISTS (
        SELECT 1 FROM case_attach_current_decisions decision WHERE decision.source_item_id = i.id))
    FROM sales_document_items i JOIN sales_documents d ON d.id = i.sales_document_id
    JOIN analytics_categories c ON c.id = i.analytics_category_id
    WHERE i.id = item_ AND d.document_kind = 'SALE' AND NOT i.is_deleted AND NOT d.is_deleted), false);
END;
$$;

CREATE OR REPLACE FUNCTION catalog_review_replaces_automatic(item_ uuid) RETURNS boolean
LANGUAGE plpgsql STABLE AS $$
BEGIN
    -- This is a necessary predicate of case_attach_review_items itself. Checking
    -- it before preparing the view avoids expanding that graph for ordinary items.
    IF NOT catalog_accessory_review_required(item_) THEN
        RETURN false;
    END IF;
    RETURN EXISTS (
        SELECT 1 FROM (
            SELECT r.decision_current
            FROM case_attach_review_items r
            WHERE r.source_item_id = item_
            OFFSET 0
        ) exact_review
        WHERE COALESCE(exact_review.decision_current, false)
           OR NOT catalog_has_current_auto_role(item_)
    );
END;
$$;
