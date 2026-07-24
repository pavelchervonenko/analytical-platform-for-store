package com.storeanalytics.metrics.repository;

import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.DeviceFamily;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryKpiRepository {

    private static final String CATEGORY_KPI_QUERY = """
            WITH included_items AS (
                SELECT
                    sdi.analytics_category_id,
                    CASE sd.document_kind WHEN 'SALE' THEN 1 ELSE -1 END AS sign,
                    sdi.quantity,
                    sdi.net_amount,
                    sdi.cost_amount,
                    sdi.cost_quality
                FROM sales_documents sd
                JOIN sales_document_items sdi ON sdi.sales_document_id = sd.id
                JOIN analytics_categories item_category
                  ON item_category.id = sdi.analytics_category_id
                WHERE sd.store_id = :storeId
                  AND sd.business_date BETWEEN :periodStart AND :periodEnd
                  AND NOT sd.is_deleted
                  AND NOT sdi.is_deleted
                  AND item_category.code <> 'EXCLUDE'
            ),
            category_facts AS (
                SELECT
                    analytics_category_id,
                    COALESCE(SUM(sign * net_amount), 0) AS net_revenue,
                    COALESCE(SUM(sign * quantity), 0) AS net_quantity,
                    COALESCE(SUM(sign * COALESCE(cost_amount, 0)), 0) AS cost_amount,
                    COUNT(*) AS included_item_count,
                    COUNT(*) FILTER (
                        WHERE cost_amount IS NULL
                    ) AS missing_cost_item_count,
                    COUNT(*) FILTER (
                        WHERE cost_quality = 'ZERO_UNEXPECTED'
                    ) AS unexpected_zero_cost_item_count
                FROM included_items
                GROUP BY analytics_category_id
            )
            SELECT
                category.code AS category_code,
                category.name AS category_name,
                category.category_kind,
                category.device_family,
                category.is_active AS category_active,
                category.counts_as_phone,
                category.counts_as_device,
                category.counts_as_additional_revenue,
                COALESCE(facts.net_revenue, 0) AS net_revenue,
                COALESCE(facts.net_quantity, 0) AS net_quantity,
                COALESCE(facts.cost_amount, 0) AS cost_amount,
                COALESCE(facts.included_item_count, 0) AS included_item_count,
                COALESCE(facts.missing_cost_item_count, 0) AS missing_cost_item_count,
                COALESCE(
                    facts.unexpected_zero_cost_item_count, 0
                ) AS unexpected_zero_cost_item_count
            FROM analytics_categories category
            LEFT JOIN category_facts facts ON facts.analytics_category_id = category.id
            WHERE category.code <> 'EXCLUDE'
            ORDER BY category.category_kind, category.name, category.code
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public CategoryKpiRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CategoryKpiAggregate> aggregate(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        Map<String, Object> parameters = Map.of(
                "storeId", storeId,
                "periodStart", periodStart,
                "periodEnd", periodEnd
        );
        return jdbcTemplate.query(
                CATEGORY_KPI_QUERY,
                parameters,
                (resultSet, rowNumber) -> new CategoryKpiAggregate(
                        resultSet.getString("category_code"),
                        resultSet.getString("category_name"),
                        AnalyticsCategoryKind.valueOf(resultSet.getString("category_kind")),
                        DeviceFamily.valueOf(resultSet.getString("device_family")),
                        resultSet.getBoolean("category_active"),
                        resultSet.getBoolean("counts_as_phone"),
                        resultSet.getBoolean("counts_as_device"),
                        resultSet.getBoolean("counts_as_additional_revenue"),
                        resultSet.getBigDecimal("net_revenue"),
                        resultSet.getBigDecimal("net_quantity"),
                        resultSet.getBigDecimal("cost_amount"),
                        resultSet.getLong("included_item_count"),
                        resultSet.getLong("missing_cost_item_count"),
                        resultSet.getLong("unexpected_zero_cost_item_count")
                )
        );
    }
}
