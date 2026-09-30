-- Preserve V55 fingerprint bytes and validity predicates, but aggregate only the relevant document.
-- A global document-context GROUP BY inside each correlated allocation check scales quadratically.
CREATE OR REPLACE VIEW warranty_attach_sources AS
SELECT source.*, latest.id AS decision_id, COALESCE(latest.revision, 0) AS revision,
       latest.action, latest.reason AS decision_reason,
       COALESCE(source.original_item_id, latest.original_warranty_item_id) AS warranty_original_id,
       md5(concat_ws(':', source.fingerprint, context.fingerprint,
           original.fingerprint, original_decision.id)) AS source_fingerprint,
       context.device_count, context.eligible_device_count,
       context.type_count, context.device_type AS document_device_type,
       CASE WHEN latest.id IS NULL THEN false ELSE
       latest.source_fingerprint = md5(concat_ws(':', source.fingerprint, context.fingerprint,
           original.fingerprint, original_decision.id))
       AND NOT EXISTS (
           SELECT 1 FROM warranty_attach_allocations a
           LEFT JOIN warranty_attach_items target ON target.id = a.device_item_id
           LEFT JOIN LATERAL (
               SELECT md5(string_agg(item.fingerprint, ',' ORDER BY item.id)) AS fingerprint
               FROM warranty_attach_items item WHERE item.document_id = target.document_id
           ) tc ON true
           WHERE a.decision_id = latest.id AND (
               target.id IS NULL OR NOT target.active OR target.device_type IS NULL
               OR target.document_kind <> 'SALE' OR target.store_id <> source.store_id
               OR target.connection_id IS DISTINCT FROM source.connection_id
               OR a.target_fingerprint <> md5(concat_ws(':', target.fingerprint, tc.fingerprint))
           )
       ) END AS decision_valid
FROM warranty_attach_items source
JOIN LATERAL (
    SELECT md5(string_agg(item.fingerprint, ',' ORDER BY item.id)) AS fingerprint,
           count(*) FILTER (WHERE item.active AND item.is_device) AS device_count,
           count(*) FILTER (WHERE item.active AND item.device_type IS NOT NULL) AS eligible_device_count,
           count(DISTINCT item.device_type) FILTER (WHERE item.active) AS type_count,
           min(item.device_type) FILTER (WHERE item.active) AS device_type
    FROM warranty_attach_items item WHERE item.document_id = source.document_id
) context ON true
LEFT JOIN warranty_attach_latest_decisions latest ON latest.source_item_id = source.id
LEFT JOIN warranty_attach_items original
    ON original.id = COALESCE(source.original_item_id, latest.original_warranty_item_id)
LEFT JOIN warranty_attach_latest_decisions original_decision ON original_decision.source_item_id = original.id
WHERE source.is_warranty AND source.active;
