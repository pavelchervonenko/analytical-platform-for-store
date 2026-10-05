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
}
