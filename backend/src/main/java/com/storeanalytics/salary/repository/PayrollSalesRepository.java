package com.storeanalytics.salary.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PayrollSalesRepository {

    private static final String SOURCE_FACTS_QUERY = """
            SELECT
                item.id AS item_id,
                document.business_date AS payroll_date,
                CASE document.document_kind WHEN 'SALE' THEN 1 ELSE -1 END AS sign,
                item.quantity,
                item.net_amount,
                item.cost_amount,
                item.product_id,
                item.analytics_category_id,
                category.payroll_category_code AS base_payroll_category,
                override.id AS override_assignment_id,
                COALESCE(override.payroll_category_code,
                         category.payroll_category_code) AS effective_payroll_category,
                override.valid_from AS override_valid_from,
                override.valid_to AS override_valid_to,
                category.code = 'EXCLUDE' AS excluded
            FROM sales_documents document
            JOIN sales_document_items item
              ON item.sales_document_id = document.id
             AND NOT item.is_deleted
            LEFT JOIN sales_documents original_document
              ON original_document.id = document.original_document_id
            JOIN analytics_categories category
              ON category.id = item.analytics_category_id
            LEFT JOIN LATERAL (
                SELECT
                    assignment.id,
                    assignment.payroll_category_code,
                    assignment.valid_from,
                    assignment.valid_to
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
              AND document.business_date BETWEEN :periodStart AND :periodEnd
            ORDER BY payroll_date, item.id
            """;

    private static final String DAILY_SALES_QUERY = """
            WITH source_items AS (
                SELECT
                    document.business_date AS payroll_date,
                    CASE document.document_kind
                        WHEN 'SALE' THEN document.business_date
                        ELSE original_document.business_date
                    END AS classification_date,
                    item.product_id,
                    item.analytics_category_id,
                    CASE document.document_kind WHEN 'SALE' THEN 1 ELSE -1 END AS sign,
                    item.quantity,
                    item.net_amount,
                    item.cost_amount
                FROM sales_documents document
                JOIN sales_document_items item
                  ON item.sales_document_id = document.id
                 AND NOT item.is_deleted
                LEFT JOIN sales_documents original_document
                  ON original_document.id = document.original_document_id
                WHERE document.store_id = :storeId
                  AND NOT document.is_deleted
                  AND document.business_date BETWEEN :periodStart AND :periodEnd
            ),
            classified_items AS (
                SELECT
                    source.*,
                    COALESCE(override.payroll_category_code,
                             category.payroll_category_code) AS payroll_category_code
                FROM source_items source
                JOIN analytics_categories category
                  ON category.id = source.analytics_category_id
                LEFT JOIN LATERAL (
                    SELECT assignment.payroll_category_code
                    FROM product_payroll_category_assignments assignment
                    WHERE assignment.product_id = source.product_id
                      AND assignment.valid_from <= source.classification_date
                      AND (assignment.valid_to IS NULL
                           OR assignment.valid_to > source.classification_date)
                    ORDER BY assignment.valid_from DESC
                    LIMIT 1
                ) override ON true
                WHERE category.code <> 'EXCLUDE'
            )
            SELECT
                payroll_date AS work_date,
                COALESCE(SUM(sign * net_amount), 0) AS net_revenue,
                COALESCE(SUM(sign * net_amount) FILTER (
                    WHERE payroll_category_code = 'ACCESSORY'
                ), 0) AS accessory_turnover,
                COALESCE(SUM(sign * net_amount) FILTER (
                    WHERE payroll_category_code = 'SERVICE'
                ), 0) AS service_turnover,
                CASE WHEN COUNT(*) FILTER (
                    WHERE payroll_category_code = 'PLAYSTATION_SUBSCRIPTION'
                      AND cost_amount IS NULL
                ) > 0 THEN NULL ELSE COALESCE(SUM(
                    sign * (net_amount - cost_amount)
                ) FILTER (
                    WHERE payroll_category_code = 'PLAYSTATION_SUBSCRIPTION'
                ), 0) END AS playstation_gross_profit,
                CASE WHEN COUNT(*) FILTER (
                    WHERE payroll_category_code = 'PAID_REPAIR'
                      AND cost_amount IS NULL
                ) > 0 THEN NULL ELSE COALESCE(SUM(
                    sign * (net_amount - cost_amount)
                ) FILTER (
                    WHERE payroll_category_code = 'PAID_REPAIR'
                ), 0) END AS paid_repair_gross_profit,
                COALESCE(SUM(sign * quantity) FILTER (
                    WHERE payroll_category_code = 'TECH_TIER_1'
                ), 0) AS tier1_quantity,
                COALESCE(SUM(sign * quantity) FILTER (
                    WHERE payroll_category_code = 'TECH_TIER_2'
                ), 0) AS tier2_quantity,
                COUNT(*) FILTER (
                    WHERE payroll_category_code = 'UNMAPPED'
                ) AS unmapped_item_count,
                COUNT(*) FILTER (
                    WHERE payroll_category_code IN ('PLAYSTATION_SUBSCRIPTION', 'PAID_REPAIR')
                      AND cost_amount IS NULL
                ) AS missing_cost_item_count
            FROM classified_items
            GROUP BY payroll_date
            ORDER BY payroll_date
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PayrollSalesRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PayrollDailySalesAggregate> aggregate(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        return jdbcTemplate.query(
                DAILY_SALES_QUERY,
                parameters(storeId, periodStart, periodEnd),
                (resultSet, rowNumber) -> new PayrollDailySalesAggregate(
                        resultSet.getObject("work_date", LocalDate.class),
                        resultSet.getBigDecimal("net_revenue"),
                        resultSet.getBigDecimal("accessory_turnover"),
                        resultSet.getBigDecimal("service_turnover"),
                        resultSet.getBigDecimal("playstation_gross_profit"),
                        resultSet.getBigDecimal("paid_repair_gross_profit"),
                        resultSet.getBigDecimal("tier1_quantity"),
                        resultSet.getBigDecimal("tier2_quantity"),
                        resultSet.getInt("unmapped_item_count"),
                        resultSet.getInt("missing_cost_item_count")
                )
        );
    }

    public List<PayrollSaleSourceFact> sourceFacts(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        return jdbcTemplate.query(
                SOURCE_FACTS_QUERY,
                parameters(storeId, periodStart, periodEnd),
                (resultSet, rowNumber) -> new PayrollSaleSourceFact(
                        resultSet.getObject("item_id", UUID.class),
                        resultSet.getObject("payroll_date", LocalDate.class),
                        resultSet.getInt("sign"),
                        resultSet.getBigDecimal("quantity"),
                        resultSet.getBigDecimal("net_amount"),
                        resultSet.getBigDecimal("cost_amount"),
                        resultSet.getObject("product_id", UUID.class),
                        resultSet.getObject("analytics_category_id", UUID.class),
                        resultSet.getString("base_payroll_category"),
                        resultSet.getObject("override_assignment_id", UUID.class),
                        resultSet.getString("effective_payroll_category"),
                        resultSet.getObject("override_valid_from", LocalDate.class),
                        resultSet.getObject("override_valid_to", LocalDate.class),
                        resultSet.getBoolean("excluded")
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
