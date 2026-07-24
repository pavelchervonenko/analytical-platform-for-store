package com.storeanalytics.salary.repository;

import com.storeanalytics.salary.model.PayrollCategoryCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PayrollReadinessRepository {

    private static final String SOURCE_CTE = """
            WITH source_items AS (
                SELECT
                    document.business_date AS payroll_date,
                    document.id AS document_id,
                    document.external_id AS document_external_id,
                    document.document_kind,
                    item.product_id,
                    product.name AS product_name,
                    category.code AS analytics_category_code,
                    CASE document.document_kind WHEN 'SALE' THEN 1 ELSE -1 END AS sign,
                    item.quantity,
                    item.net_amount,
                    item.cost_amount,
                    COALESCE(override.payroll_category_code,
                             category.payroll_category_code) AS payroll_category_code
                FROM sales_documents document
                JOIN sales_document_items item
                  ON item.sales_document_id = document.id
                 AND NOT item.is_deleted
                JOIN products product ON product.id = item.product_id
                JOIN analytics_categories category
                  ON category.id = item.analytics_category_id
                LEFT JOIN sales_documents original_document
                  ON original_document.id = document.original_document_id
                LEFT JOIN LATERAL (
                    SELECT assignment.payroll_category_code
                    FROM product_payroll_category_assignments assignment
                    WHERE assignment.product_id = item.product_id
                      AND assignment.valid_from <= CASE document.document_kind
                            WHEN 'SALE' THEN document.business_date
                            ELSE original_document.business_date
                          END
                      AND (assignment.valid_to IS NULL
                           OR assignment.valid_to > CASE document.document_kind
                                WHEN 'SALE' THEN document.business_date
                                ELSE original_document.business_date
                              END)
                    ORDER BY assignment.valid_from DESC
                    LIMIT 1
                ) override ON true
                WHERE document.store_id = :storeId
                  AND NOT document.is_deleted
                  AND category.code <> 'EXCLUDE'
                  AND document.business_date BETWEEN :periodStart AND :periodEnd
            )
            """;

    private static final String UNMAPPED_QUERY = SOURCE_CTE + """
            SELECT
                product_id,
                product_name,
                analytics_category_code,
                MIN(payroll_date) AS first_sale_date,
                MAX(payroll_date) AS last_sale_date,
                COUNT(*) FILTER (WHERE document_kind = 'SALE') AS sale_item_count,
                COUNT(*) FILTER (WHERE document_kind = 'RETURN') AS return_item_count,
                COALESCE(SUM(sign * quantity), 0) AS net_quantity,
                COALESCE(SUM(sign * net_amount), 0) AS net_revenue
            FROM source_items
            WHERE payroll_category_code = 'UNMAPPED'
            GROUP BY product_id, product_name, analytics_category_code
            ORDER BY ABS(SUM(sign * net_amount)) DESC, product_name
            """;

    private static final String MISSING_COST_QUERY = SOURCE_CTE + """
            SELECT
                payroll_date,
                document_id,
                document_external_id,
                document_kind = 'RETURN' AS return_document,
                product_id,
                product_name,
                payroll_category_code,
                quantity,
                net_amount
            FROM source_items
            WHERE payroll_category_code IN ('PLAYSTATION_SUBSCRIPTION', 'PAID_REPAIR')
              AND cost_amount IS NULL
            ORDER BY payroll_date, product_name, document_external_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PayrollReadinessRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PayrollUnmappedProductIssue> unmappedProducts(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        return jdbcTemplate.query(
                UNMAPPED_QUERY,
                parameters(storeId, periodStart, periodEnd),
                (resultSet, rowNumber) -> new PayrollUnmappedProductIssue(
                        resultSet.getObject("product_id", UUID.class),
                        resultSet.getString("product_name"),
                        resultSet.getString("analytics_category_code"),
                        resultSet.getObject("first_sale_date", LocalDate.class),
                        resultSet.getObject("last_sale_date", LocalDate.class),
                        resultSet.getLong("sale_item_count"),
                        resultSet.getLong("return_item_count"),
                        resultSet.getBigDecimal("net_quantity"),
                        resultSet.getBigDecimal("net_revenue")
                )
        );
    }

    public List<PayrollMissingCostIssue> missingCosts(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        return jdbcTemplate.query(
                MISSING_COST_QUERY,
                parameters(storeId, periodStart, periodEnd),
                (resultSet, rowNumber) -> new PayrollMissingCostIssue(
                        resultSet.getObject("payroll_date", LocalDate.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getString("document_external_id"),
                        resultSet.getBoolean("return_document"),
                        resultSet.getObject("product_id", UUID.class),
                        resultSet.getString("product_name"),
                        PayrollCategoryCode.valueOf(resultSet.getString("payroll_category_code")),
                        resultSet.getBigDecimal("quantity"),
                        resultSet.getBigDecimal("net_amount")
                )
        );
    }

    private Map<String, Object> parameters(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        return Map.of(
                "storeId", storeId,
                "periodStart", periodStart,
                "periodEnd", periodEnd
        );
    }
}
