package com.storeanalytics.metrics.repository;

/** Read-only analytics author projection. Never changes the stored payroll/reconciliation author. */
public final class AnalyticalDocumentSql {

    public static final String PERIOD_DOCUMENTS_CTE = """
            WITH analytical_documents AS (
                SELECT document.*,
                       CASE WHEN document.document_kind = 'SALE' THEN document.employee_id
                            ELSE return_employee.id END AS analytical_employee_id
                FROM sales_documents document
                LEFT JOIN employees return_employee
                  ON return_employee.connection_id = document.connection_id
                 AND return_employee.source_system = 'LIVESKLAD'
                 AND return_employee.external_id = document.attach_source_employee_external_id
                WHERE document.store_id = :storeId
                  AND document.business_date BETWEEN :periodStart AND :periodEnd
                  AND NOT document.is_deleted
            )
            """;

    private AnalyticalDocumentSql() {
    }

    /** Selected IDs came from the document-level temporal reader in the same RR transaction. */
    static String selectedDocuments(String query, boolean empty) {
        if (!query.startsWith(PERIOD_DOCUMENTS_CTE)) {
            throw new IllegalArgumentException("Expected analytical document projection");
        }
        String selected = PERIOD_DOCUMENTS_CTE.replace("WITH analytical_documents AS",
                "WITH candidate_documents AS") + """
                , analytical_documents AS (
                    SELECT * FROM candidate_documents WHERE %s
                )
                """.formatted(empty ? "false" : "id IN (:documentIds)");
        return selected + query.substring(PERIOD_DOCUMENTS_CTE.length());
    }
}
