package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.snapshot.EmployeeSalesSampleFacts;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.repository.EmployeeAttachRateAggregate;
import com.storeanalytics.performance.repository.EmployeeAttachRateRepository;
import com.storeanalytics.performance.repository.EmployeePerformanceAggregate;
import com.storeanalytics.performance.repository.EmployeePerformanceRepository;
import com.storeanalytics.performance.service.EmployeeAttachRatingEntry;
import com.storeanalytics.performance.service.EmployeeRatingEntry;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads raw employee weekly facts without invoking rating scores or monthly plans. */
@Component
public class WeeklyReviewEmployeeFactsReader {

    private static final String SALES_SAMPLE_QUERY = """
            SELECT
                document.employee_id,
                COUNT(DISTINCT document.id) AS completed_sales
            FROM sales_documents document
            JOIN sales_document_items item ON item.sales_document_id = document.id
            JOIN analytics_categories category ON category.id = item.analytics_category_id
            WHERE document.store_id = :storeId
              AND document.business_date BETWEEN :periodStart AND :periodEnd
              AND document.document_kind = 'SALE'
              AND document.source_document_type = 'sale'
              AND NOT document.is_deleted
              AND NOT item.is_deleted
              AND category.code <> 'EXCLUDE'
              AND document.employee_id IS NOT NULL
            GROUP BY document.employee_id
            """;

    private static final String UNATTRIBUTED_RETURNS_QUERY = """
            SELECT COUNT(DISTINCT document.id)
            FROM sales_documents document
            JOIN sales_document_items item ON item.sales_document_id = document.id
            JOIN analytics_categories category ON category.id = item.analytics_category_id
            WHERE document.store_id = :storeId
              AND document.business_date BETWEEN :periodStart AND :periodEnd
              AND document.document_kind = 'RETURN'
              AND NOT document.is_deleted
              AND NOT item.is_deleted
              AND category.code <> 'EXCLUDE'
              AND document.employee_id IS NULL
            """;

    private final EmployeePerformanceRepository performanceRepository;
    private final EmployeeAttachRateRepository attachRateRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public WeeklyReviewEmployeeFactsReader(
            EmployeePerformanceRepository performanceRepository,
            EmployeeAttachRateRepository attachRateRepository,
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.performanceRepository = performanceRepository;
        this.attachRateRepository = attachRateRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public EmployeePeriod read(UUID storeId, StoreKpiPeriod period) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        StoreKpiPeriod validatedPeriod = requireNonNull(period, "period");
        List<EmployeePerformanceAggregate> employees = performanceRepository.aggregate(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end()
        );
        Map<UUID, List<EmployeeAttachRateAggregate>> attach = attachRateRepository.aggregate(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end()
        ).stream().collect(Collectors.groupingBy(
                EmployeeAttachRateAggregate::employeeId,
                HashMap::new,
                Collectors.toList()
        ));
        EmployeeSalesSampleFacts salesSamples = salesSamples(
                validatedStoreId, validatedPeriod
        );
        List<EmployeeRatingEntry> entries = employees.stream()
                .map(employee -> entry(
                        employee,
                        attach.getOrDefault(employee.employeeId(), List.of())
                ))
                .toList();
        return new EmployeePeriod(
                new EmployeeRatingResult(
                        validatedStoreId,
                        validatedPeriod.start(),
                        validatedPeriod.end(),
                        null,
                        null,
                        entries,
                        null
                ),
                salesSamples,
                unattributedReturns(validatedStoreId, validatedPeriod)
        );
    }

    private long unattributedReturns(UUID storeId, StoreKpiPeriod period) {
        Long count = jdbcTemplate.queryForObject(
                UNATTRIBUTED_RETURNS_QUERY,
                Map.of(
                        "storeId", storeId,
                        "periodStart", period.start(),
                        "periodEnd", period.end()
                ),
                Long.class
        );
        return count == null ? 0 : count;
    }

    private EmployeeRatingEntry entry(
            EmployeePerformanceAggregate source,
            List<EmployeeAttachRateAggregate> attachRates
    ) {
        BigDecimal revenue = money(source.netRevenue());
        BigDecimal hours = source.workedHours().setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal accessory = money(source.accessoryRevenue());
        BigDecimal service = money(source.serviceRevenue());
        BigDecimal additional = money(source.additionalRevenue());
        return new EmployeeRatingEntry(
                source.employeeId(),
                source.displayName(),
                source.employeeActive(),
                source.assignmentActive(),
                source.participatesInRanking(),
                source.employeeActive()
                        && source.assignmentActive()
                        && source.participatesInRanking(),
                source.shiftCount(),
                hours,
                revenue,
                null,
                perShift(revenue, source.shiftCount()),
                perHour(revenue, hours),
                accessory,
                share(accessory, revenue),
                service,
                share(service, revenue),
                additional,
                share(additional, revenue),
                null,
                false,
                null,
                attachRates.stream().map(this::attach).toList()
        );
    }

    private EmployeeAttachRatingEntry attach(EmployeeAttachRateAggregate source) {
        BigDecimal numerator = source.numeratorReceiptCount().setScale(
                3, RoundingMode.UNNECESSARY
        );
        BigDecimal denominator = source.denominatorReceiptCount().setScale(
                3, RoundingMode.UNNECESSARY
        );
        return new EmployeeAttachRatingEntry(
                source.metricCode(),
                source.numeratorCategoryCode(),
                source.denominatorCode(),
                numerator,
                denominator,
                rate(numerator, denominator),
                null,
                false,
                null
        );
    }

    private EmployeeSalesSampleFacts salesSamples(UUID storeId, StoreKpiPeriod period) {
        List<Map.Entry<UUID, Long>> rows = jdbcTemplate.query(
                SALES_SAMPLE_QUERY,
                Map.of(
                        "storeId", storeId,
                        "periodStart", period.start(),
                        "periodEnd", period.end()
                ),
                (resultSet, rowNumber) -> Map.entry(
                        resultSet.getObject("employee_id", UUID.class),
                        resultSet.getLong("completed_sales")
                )
        );
        Map<UUID, Long> result = new HashMap<>();
        rows.forEach(row -> result.put(row.getKey(), row.getValue()));
        return new EmployeeSalesSampleFacts(result);
    }

    private BigDecimal perShift(BigDecimal revenue, long shiftCount) {
        return shiftCount == 0
                ? null
                : revenue.divide(BigDecimal.valueOf(shiftCount), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal perHour(BigDecimal revenue, BigDecimal hours) {
        return hours.signum() <= 0
                ? null
                : revenue.divide(hours, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal share(BigDecimal part, BigDecimal total) {
        return total.signum() <= 0
                ? null
                : part.multiply(BigDecimal.valueOf(100))
                        .divide(total, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() <= 0
                ? null
                : numerator.max(BigDecimal.ZERO)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public record EmployeePeriod(
            EmployeeRatingResult employees,
            EmployeeSalesSampleFacts salesSamples,
            long unattributedReturnDocumentCount
    ) {

        public EmployeePeriod {
            requireNonNull(employees, "employees");
            requireNonNull(salesSamples, "salesSamples");
            if (unattributedReturnDocumentCount < 0) {
                throw new IllegalArgumentException(
                        "unattributedReturnDocumentCount must not be negative"
                );
            }
        }
    }
}
