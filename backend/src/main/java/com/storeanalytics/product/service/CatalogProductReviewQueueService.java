package com.storeanalytics.product.service;

import com.storeanalytics.product.repository.AnalyticsCategoryRepository;
import com.storeanalytics.product.repository.ProductRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only global queue: new sold LiveSklad products need an explicit analytical and payroll choice. */
@Service
public class CatalogProductReviewQueueService {
    private static final String QUEUE_SQL = """
            WITH sold AS (
                SELECT product.id AS product_id,
                       min(document.occurred_at) AS first_sale_at,
                       (array_agg(document.business_date ORDER BY document.occurred_at, document.id))[1]
                           AS first_sale_date,
                       count(item.id) AS sale_item_count,
                       bool_or(category.code = 'UNMAPPED') AS has_unmapped_sales
                FROM products product
                JOIN sales_document_items item ON item.product_id = product.id
                JOIN sales_documents document ON document.id = item.sales_document_id
                JOIN analytics_categories category ON category.id = item.analytics_category_id
                WHERE product.source_system = 'LIVESKLAD'
                  AND product.created_at >= ?
                  AND document.document_kind = 'SALE'
                  AND document.occurred_at >= ?
                  AND NOT document.is_deleted AND NOT item.is_deleted
                GROUP BY product.id
            )
            SELECT product.id,product.version,product.external_id,product.code,product.name,
                   product.source_kind,source_group.path AS source_group_path,
                   sold.first_sale_at,sold.first_sale_date,sold.sale_item_count,
                   sold.has_unmapped_sales,analytic.code AS assigned_analytics_category_code,
                   analytic.condition_type AS assigned_condition_type,
                   payroll.payroll_category_code AS assigned_payroll_category_code
            FROM sold
            JOIN products product ON product.id = sold.product_id
            LEFT JOIN source_product_groups source_group ON source_group.id = product.source_group_id
            LEFT JOIN LATERAL (
                SELECT category.code, assignment.condition_type
                FROM product_category_assignments assignment
                JOIN analytics_categories category ON category.id = assignment.analytics_category_id
                WHERE assignment.product_id = product.id
                  AND assignment.valid_from <= sold.first_sale_at
                  AND (assignment.valid_to IS NULL OR assignment.valid_to > sold.first_sale_at)
                ORDER BY assignment.valid_from DESC LIMIT 1
            ) analytic ON true
            LEFT JOIN LATERAL (
                SELECT assignment.payroll_category_code
                FROM product_payroll_category_assignments assignment
                WHERE assignment.product_id = product.id
                  AND assignment.valid_from <= sold.first_sale_date
                  AND (assignment.valid_to IS NULL OR assignment.valid_to > sold.first_sale_date)
                ORDER BY assignment.valid_from DESC LIMIT 1
            ) payroll ON true
            WHERE sold.has_unmapped_sales OR analytic.code IS NULL
               OR payroll.payroll_category_code IS NULL
            ORDER BY sold.first_sale_at,product.id
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final AnalyticsCategoryRepository categories;
    private final ProductAutoClassificationRuleEngine rules;
    private final CatalogClassificationCutover cutover;

    public CatalogProductReviewQueueService(
            JdbcTemplate jdbc,
            ProductRepository products,
            AnalyticsCategoryRepository categories,
            ProductAutoClassificationRuleEngine rules,
            CatalogClassificationCutover cutover
    ) {
        this.jdbc = jdbc;
        this.products = products;
        this.categories = categories;
        this.rules = rules;
        this.cutover = cutover;
    }

    @Transactional(readOnly = true)
    public CatalogProductReviewQueue list(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        Instant boundary = cutover.activationBoundary().orElse(null);
        if (boundary == null) {
            return new CatalogProductReviewQueue(null, List.of(), false);
        }
        List<CatalogProductReviewQueueItem> rows = jdbc.query(connection -> {
            var statement = connection.prepareStatement(QUEUE_SQL);
            statement.setTimestamp(1, Timestamp.from(boundary));
            statement.setTimestamp(2, Timestamp.from(boundary));
            statement.setInt(3, limit + 1);
            return statement;
        }, (result, index) -> new CatalogProductReviewQueueItem(
                result.getObject("id", UUID.class), result.getLong("version"),
                result.getString("external_id"), result.getString("code"),
                result.getString("name"), result.getString("source_kind"),
                result.getString("source_group_path"),
                result.getTimestamp("first_sale_at").toInstant(),
                result.getObject("first_sale_date", java.time.LocalDate.class),
                result.getLong("sale_item_count"), result.getBoolean("has_unmapped_sales"),
                result.getString("assigned_analytics_category_code"),
                result.getString("assigned_condition_type"),
                result.getString("assigned_payroll_category_code"), null
        ));
        boolean hasMore = rows.size() > limit;
        List<CatalogProductReviewQueueItem> page = rows.subList(0, Math.min(rows.size(), limit));
        Map<UUID, String> suggestions = suggestions(page);
        return new CatalogProductReviewQueue(boundary, page.stream().map(row ->
                new CatalogProductReviewQueueItem(
                        row.productId(), row.productVersion(), row.externalId(), row.code(),
                        row.name(), row.sourceKind(), row.sourceGroupPath(), row.firstSaleAt(),
                        row.firstSaleDate(), row.saleItemCount(), row.hasUnmappedSales(),
                        row.assignedAnalyticsCategoryCode(), row.assignedConditionType(),
                        row.assignedPayrollCategoryCode(),
                        suggestions.get(row.productId())
                )).toList(), hasMore);
    }

    private Map<UUID, String> suggestions(List<CatalogProductReviewQueueItem> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> result = new HashMap<>();
        products.findAllById(rows.stream().map(CatalogProductReviewQueueItem::productId).toList())
                .forEach(product -> rules.classify(product).ifPresent(decision ->
                        categories.findByCode(decision.categoryCode())
                                .filter(category -> category.isActive())
                                .ifPresent(category -> result.put(product.getId(), category.getCode()))));
        return result;
    }
}
