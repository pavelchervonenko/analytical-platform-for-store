package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.RevenuePeriod;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the auditable sales/returns decomposition for two adjacent weekly periods. */
@Repository
public class WeeklyReviewRevenueRepository {

    private static final String QUERY = """
            WITH periods(period_code, period_start, period_end) AS (
                VALUES
                    ('CURRENT', CAST(:currentStart AS date), CAST(:currentEnd AS date)),
                    ('PREVIOUS', CAST(:previousStart AS date), CAST(:previousEnd AS date))
            ),
            period_documents AS (
                SELECT period.period_code, document.id, document.document_kind
                FROM periods period
                JOIN sales_documents document
                  ON document.store_id = :storeId
                 AND document.business_date BETWEEN period.period_start AND period.period_end
                 AND NOT document.is_deleted
            ),
            document_counts AS (
                SELECT
                    period_code,
                    COUNT(*) FILTER (WHERE document_kind = 'SALE') AS sale_document_count,
                    COUNT(*) FILTER (WHERE document_kind = 'RETURN') AS return_document_count
                FROM period_documents
                GROUP BY period_code
            ),
            included_amounts AS (
                SELECT
                    document.period_code,
                    COALESCE(SUM(item.net_amount) FILTER (
                        WHERE document.document_kind = 'SALE'
                    ), 0) AS sales_revenue,
                    COALESCE(SUM(item.net_amount) FILTER (
                        WHERE document.document_kind = 'RETURN'
                    ), 0) AS return_revenue
                FROM period_documents document
                JOIN sales_document_items item ON item.sales_document_id = document.id
                JOIN analytics_categories category ON category.id = item.analytics_category_id
                WHERE NOT item.is_deleted
                  AND category.code <> 'EXCLUDE'
                GROUP BY document.period_code
            )
            SELECT
                period.period_code,
                COALESCE(amount.sales_revenue, 0) AS sales_revenue,
                COALESCE(amount.return_revenue, 0) AS return_revenue,
                COALESCE(counts.sale_document_count, 0) AS sale_document_count,
                COALESCE(counts.return_document_count, 0) AS return_document_count
            FROM periods period
            LEFT JOIN included_amounts amount USING (period_code)
            LEFT JOIN document_counts counts USING (period_code)
            ORDER BY CASE period.period_code WHEN 'CURRENT' THEN 0 ELSE 1 END
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public WeeklyReviewRevenueRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RevenueComparison read(
            UUID storeId,
            StoreKpiPeriod current,
            StoreKpiPeriod previous
    ) {
        List<RevenuePeriod> periods = jdbcTemplate.query(
                QUERY,
                Map.of(
                        "storeId", requireNonNull(storeId, "storeId"),
                        "currentStart", requireNonNull(current, "current").start(),
                        "currentEnd", current.end(),
                        "previousStart", requireNonNull(previous, "previous").start(),
                        "previousEnd", previous.end()
                ),
                (resultSet, rowNumber) -> period(
                        resultSet.getBigDecimal("sales_revenue"),
                        resultSet.getBigDecimal("return_revenue"),
                        resultSet.getLong("sale_document_count"),
                        resultSet.getLong("return_document_count")
                )
        );
        if (periods.size() != 2) {
            throw new IllegalStateException("Weekly revenue query must return two periods");
        }
        return new RevenueComparison(periods.get(0), periods.get(1));
    }

    private RevenuePeriod period(
            BigDecimal salesRevenue,
            BigDecimal returnRevenue,
            long saleDocumentCount,
            long returnDocumentCount
    ) {
        BigDecimal sales = salesRevenue.setScale(2);
        BigDecimal returns = returnRevenue.setScale(2);
        return new RevenuePeriod(
                sales,
                returns,
                sales.subtract(returns),
                saleDocumentCount,
                returnDocumentCount
        );
    }

    public record RevenueComparison(RevenuePeriod current, RevenuePeriod previous) {

        public RevenueComparison {
            requireNonNull(current, "current");
            requireNonNull(previous, "previous");
        }
    }
}
