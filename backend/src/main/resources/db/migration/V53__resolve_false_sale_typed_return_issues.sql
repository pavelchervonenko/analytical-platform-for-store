WITH latest_return_versions AS (
    SELECT DISTINCT ON (
        version.connection_id,
        version.store_id,
        version.external_id
    )
        version.connection_id,
        version.store_id,
        version.external_id,
        version.payload,
        version.normalization_status
    FROM raw_record_versions version
    WHERE version.source_system = 'LIVESKLAD'
      AND version.entity_type = 'RETURN_DOCUMENT'
    ORDER BY
        version.connection_id,
        version.store_id,
        version.external_id,
        version.first_seen_at DESC,
        version.id DESC
),
false_positive_issues AS (
    SELECT issue.id
    FROM data_quality_issues issue
    JOIN sales_documents document
      ON issue.store_id = document.store_id
     AND issue.entity_type = 'RETURN_DOCUMENT'
     AND issue.entity_id = document.connection_id::text || ':' || document.external_id
    JOIN latest_return_versions version
      ON version.connection_id = document.connection_id
     AND version.store_id = document.store_id
     AND version.external_id = document.external_id
    WHERE issue.issue_code = 'RETURN_ORIGINAL_DOCUMENT_MISSING'
      AND issue.status = 'OPEN'
      AND document.document_kind = 'SALE'
      AND NOT document.is_deleted
      AND version.normalization_status = 'SKIPPED'
      AND lower(version.payload #>> '{detail,type}') = 'sale'
)
UPDATE data_quality_issues issue
SET status = 'RESOLVED',
    resolved_at = GREATEST(clock_timestamp(), issue.detected_at),
    resolved_by = NULL
FROM false_positive_issues false_positive
WHERE issue.id = false_positive.id;
