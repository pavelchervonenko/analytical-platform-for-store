package com.storeanalytics.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.DeviceFamily;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OverviewMetricsServiceTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID SELLER_ID = UUID.randomUUID();
    private static final UUID WHOLESALE_ID = UUID.randomUUID();
    private static final StoreKpiPeriod PERIOD = new StoreKpiPeriod(
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 28)
    );

    private StoreKpiService storeKpiService;
    private CategoryKpiService categoryKpiService;
    private EmployeeKpiService employeeKpiService;
    private EmployeeCategoryKpiService employeeCategoryKpiService;
    private OverviewMetricsService service;

    @BeforeEach
    void setUp() {
        storeKpiService = mock(StoreKpiService.class);
        categoryKpiService = mock(CategoryKpiService.class);
        employeeKpiService = mock(EmployeeKpiService.class);
        employeeCategoryKpiService = mock(EmployeeCategoryKpiService.class);
        service = new OverviewMetricsService(
                storeKpiService,
                categoryKpiService,
                employeeKpiService,
                employeeCategoryKpiService
        );
        stubConsistentProjections("225.00");
    }

    @Test
    void selectsOnlyRankingEligibleEmployeesForSellerMetrics() {
        OverviewMetricsResult result = service.calculate(
                STORE_ID,
                PERIOD,
                OverviewMetricScope.SELLERS
        );

        assertThat(result.scope()).isEqualTo(OverviewMetricScope.SELLERS);
        assertThat(result.netRevenue()).isEqualByComparingTo("1000.00");
        assertThat(result.netQuantity()).isEqualByComparingTo("10.000");
        assertThat(result.costAmount()).isEqualByComparingTo("600.00");
        assertThat(result.grossProfit()).isEqualByComparingTo("400.00");
        assertThat(result.marginPercent()).isEqualByComparingTo("40.00");
        assertThat(result.additional().netRevenue()).isEqualByComparingTo("150.00");
        assertThat(result.additional().sharePercent()).isEqualByComparingTo("15.00");
        assertThat(result.accessory().sharePercent()).isEqualByComparingTo("10.00");
        assertThat(result.service().sharePercent()).isEqualByComparingTo("5.00");
        assertThat(result.dataQuality().includedItemCount()).isEqualTo(2);
        assertThat(result.dataQuality().reconciliationPassed()).isTrue();
    }

    @Test
    void storeScopeReturnsTheFullStoreProjection() {
        OverviewMetricsResult result = service.calculate(
                STORE_ID,
                PERIOD,
                OverviewMetricScope.STORE
        );

        assertThat(result.netRevenue()).isEqualByComparingTo("1500.00");
        assertThat(result.additional().netRevenue()).isEqualByComparingTo("225.00");
        assertThat(result.dataQuality().includedItemCount()).isEqualTo(4);
    }

    @Test
    void failsClosedWhenAdditionalRevenueDoesNotEqualAccessoriesPlusServices() {
        stubConsistentProjections("224.00");

        assertThatThrownBy(() -> service.calculate(
                STORE_ID,
                PERIOD,
                OverviewMetricScope.SELLERS
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reconciliation failed");
    }

    private void stubConsistentProjections(String storeAdditional) {
        when(storeKpiService.calculate(STORE_ID, PERIOD)).thenReturn(storeKpi());
        when(categoryKpiService.calculate(STORE_ID, PERIOD)).thenReturn(
                categoryKpi(storeAdditional)
        );
        when(employeeKpiService.calculate(STORE_ID, PERIOD)).thenReturn(employeeKpi());
        when(employeeCategoryKpiService.calculate(STORE_ID, PERIOD)).thenReturn(
                employeeCategoryKpi()
        );
    }

    private StoreKpiResult storeKpi() {
        return new StoreKpiResult(
                STORE_ID,
                PERIOD.start(),
                PERIOD.end(),
                "store-kpi-v1",
                amount("1500.00"),
                amount("15.000"),
                amount("900.00"),
                amount("600.00"),
                amount("40.00"),
                new StoreKpiDataQuality(true, 4, 0, 0, 0, 2, 3)
        );
    }

    private CategoryKpiResult categoryKpi(String additional) {
        return new CategoryKpiResult(
                STORE_ID,
                PERIOD.start(),
                PERIOD.end(),
                "category-kpi-v3",
                List.of(
                        categoryGroup("ACCESSORY", "150.00", "1.500"),
                        categoryGroup("SERVICE", "75.00", "0.750"),
                        categoryGroup("ADDITIONAL_REVENUE", additional, "2.250")
                ),
                List.of(new CategoryKpiEntry(
                        "ALL",
                        "All",
                        AnalyticsCategoryKind.OTHER,
                        DeviceFamily.NONE,
                        true,
                        false,
                        false,
                        false,
                        categoryMetrics("1500.00", "15.000")
                ))
        );
    }

    private EmployeeKpiResult employeeKpi() {
        return new EmployeeKpiResult(
                STORE_ID,
                PERIOD.start(),
                PERIOD.end(),
                "employee-kpi-v1",
                List.of(
                        employeeKpiEntry(
                                SELLER_ID, "Seller", true,
                                "1000.00", "10.000", "600.00", 2
                        ),
                        employeeKpiEntry(
                                WHOLESALE_ID, "Wholesale", false,
                                "400.00", "4.000", "240.00", 1
                        ),
                        employeeKpiEntry(
                                null, "Unassigned", false,
                                "100.00", "1.000", "60.00", 1
                        )
                )
        );
    }

    private EmployeeKpiEntry employeeKpiEntry(
            UUID employeeId,
            String name,
            boolean rankingEligible,
            String revenue,
            String quantity,
            String cost,
            long itemCount
    ) {
        BigDecimal revenueAmount = amount(revenue);
        BigDecimal costAmount = amount(cost);
        return new EmployeeKpiEntry(
                employeeId,
                name,
                employeeId != null,
                employeeId != null,
                employeeId != null,
                rankingEligible,
                rankingEligible,
                employeeId == null,
                revenueAmount,
                amount(quantity),
                costAmount,
                revenueAmount.subtract(costAmount),
                amount("40.00"),
                employeeQuality(itemCount)
        );
    }

    private EmployeeCategoryKpiResult employeeCategoryKpi() {
        return new EmployeeCategoryKpiResult(
                STORE_ID,
                PERIOD.start(),
                PERIOD.end(),
                "employee-category-kpi-v1",
                "category-kpi-v3",
                List.of(
                        employeeCategory(
                                SELLER_ID, true,
                                "1000.00", "100.00", "50.00", "150.00", 2
                        ),
                        employeeCategory(
                                WHOLESALE_ID, false,
                                "400.00", "40.00", "20.00", "60.00", 1
                        ),
                        employeeCategory(
                                null, false,
                                "100.00", "10.00", "5.00", "15.00", 1
                        )
                )
        );
    }

    private EmployeeCategoryKpiEmployee employeeCategory(
            UUID employeeId,
            boolean rankingEligible,
            String revenue,
            String accessory,
            String service,
            String additional,
            long itemCount
    ) {
        return new EmployeeCategoryKpiEmployee(
                employeeId,
                employeeId == null
                        ? "Unassigned"
                        : rankingEligible ? "Seller" : "Wholesale",
                employeeId != null,
                employeeId != null,
                employeeId != null,
                rankingEligible,
                rankingEligible,
                employeeId == null,
                amount(revenue),
                employeeQuality(itemCount),
                List.of(
                        employeeGroup("ACCESSORY", accessory),
                        employeeGroup("SERVICE", service),
                        employeeGroup("ADDITIONAL_REVENUE", additional)
                ),
                List.of()
        );
    }

    private CategoryKpiGroup categoryGroup(
            String code,
            String revenue,
            String quantity
    ) {
        return new CategoryKpiGroup(code, code, categoryMetrics(revenue, quantity));
    }

    private CategoryKpiMetrics categoryMetrics(String revenue, String quantity) {
        return new CategoryKpiMetrics(
                amount(revenue),
                amount(quantity),
                BigDecimal.ZERO,
                amount(revenue),
                null,
                null,
                new CategoryKpiDataQuality(true, 1, 0, 0)
        );
    }

    private EmployeeCategoryKpiGroup employeeGroup(
            String code,
            String revenue
    ) {
        BigDecimal revenueAmount = amount(revenue);
        return new EmployeeCategoryKpiGroup(
                code,
                code,
                new EmployeeCategoryKpiMetrics(
                        revenueAmount,
                        revenueAmount.movePointLeft(2).setScale(3),
                        BigDecimal.ZERO,
                        amount(revenue),
                        null,
                        null,
                        new CategoryKpiDataQuality(true, 1, 0, 0)
                )
        );
    }

    private EmployeeKpiDataQuality employeeQuality(long itemCount) {
        return new EmployeeKpiDataQuality(true, itemCount, 0, 0, 0);
    }

    private BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
