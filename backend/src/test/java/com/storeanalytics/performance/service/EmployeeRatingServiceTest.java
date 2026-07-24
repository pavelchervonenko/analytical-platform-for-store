package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.repository.AttachRateAggregate;
import com.storeanalytics.metrics.repository.AttachRateRepository;
import com.storeanalytics.metrics.repository.StoreKpiAggregate;
import com.storeanalytics.metrics.repository.StoreKpiRepository;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.model.RatingScheme;
import com.storeanalytics.performance.model.RatingSchemeDefinition;
import com.storeanalytics.performance.repository.EmployeeAttachRateAggregate;
import com.storeanalytics.performance.repository.EmployeeAttachRateRepository;
import com.storeanalytics.performance.repository.EmployeePerformanceAggregate;
import com.storeanalytics.performance.repository.EmployeePerformanceRepository;
import com.storeanalytics.product.model.AttachDenominatorCode;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmployeeRatingServiceTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    private StoreRepository storeRepository;
    private StoreKpiRepository storeKpiRepository;
    private EmployeePerformanceRepository performanceRepository;
    private AttachRateRepository storeAttachRateRepository;
    private EmployeeAttachRateRepository employeeAttachRateRepository;
    private StorePerformancePlanService planService;
    private RatingSchemeService schemeService;
    private EmployeeRatingService service;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        storeKpiRepository = mock(StoreKpiRepository.class);
        performanceRepository = mock(EmployeePerformanceRepository.class);
        storeAttachRateRepository = mock(AttachRateRepository.class);
        employeeAttachRateRepository = mock(EmployeeAttachRateRepository.class);
        planService = mock(StorePerformancePlanService.class);
        schemeService = mock(RatingSchemeService.class);
        service = new EmployeeRatingService(
                storeRepository,
                storeKpiRepository,
                performanceRepository,
                storeAttachRateRepository,
                employeeAttachRateRepository,
                planService,
                schemeService
        );
    }

    @Test
    void calculatesFourDirectionsAndRanksOnlyEmployeesWithEnoughCoverage() {
        UUID storeId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        prepareStore(storeId);
        when(performanceRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(
                        employee(firstId, "First", "600.00", "24.00", "18.00", 2, "22.00"),
                        employee(secondId, "Second", "600.00", "12.00", "6.00", 1, "11.00")
                ));
        when(storeAttachRateRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(storeAttachRate()));
        when(employeeAttachRateRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(
                        employeeAttachRate(firstId, "1.000", "5.000"),
                        employeeAttachRate(secondId, "1.000", "2.000")
                ));

        EmployeeRatingResult result = service.calculate(storeId, period());

        assertThat(result.formula().version()).isEqualTo("employee-rating-v1");
        assertThat(result.employees()).extracting(EmployeeRatingEntry::displayName)
                .containsExactly("Second", "First");
        EmployeeRatingEntry second = result.employees().getFirst();
        assertThat(second.rank()).isEqualTo(1);
        assertThat(second.scores().contributionScore()).isEqualByComparingTo("100.00");
        assertThat(second.scores().efficiencyScore()).isEqualByComparingTo("150.00");
        assertThat(second.scores().structureScore()).isEqualByComparingTo("41.67");
        assertThat(second.scores().attachScore()).isNull();
        assertThat(second.scores().coveragePercent()).isEqualByComparingTo("75.00");
        assertThat(second.scores().overallScore()).isEqualByComparingTo("97.23");

        EmployeeRatingEntry first = result.employees().get(1);
        assertThat(first.rank()).isEqualTo(2);
        assertThat(first.scores().efficiencyScore()).isEqualByComparingTo("75.00");
        assertThat(first.scores().structureScore()).isEqualByComparingTo("100.00");
        assertThat(first.scores().attachScore()).isEqualByComparingTo("100.00");
        assertThat(first.scores().overallScore()).isEqualByComparingTo("93.75");
        assertThat(first.attachRates().getFirst().includedInScore()).isTrue();
    }

    @Test
    void withholdsPlaceWhenPlanAndAttachDataLeaveCoverageBelowThreshold() {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        prepareStore(storeId);
        when(planService.context(storeId, PERIOD_START, PERIOD_END, new BigDecimal("1200.00")))
                .thenReturn(new RatingPlanContext(
                        false,
                        BigDecimal.ZERO.setScale(2),
                        null,
                        null,
                        null,
                        null,
                        new BigDecimal("1200.00"),
                        null
                ));
        when(performanceRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(employee(
                        employeeId, "Employee", "600.00", "0.00", "0.00", 1, "11.00"
                )));
        when(storeAttachRateRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(List.of());
        when(employeeAttachRateRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(List.of());

        EmployeeRatingEntry entry = service.calculate(storeId, period()).employees().getFirst();

        assertThat(entry.scores().coveragePercent()).isEqualByComparingTo("50.00");
        assertThat(entry.scores().overallScore()).isEqualByComparingTo("100.00");
        assertThat(entry.ranked()).isFalse();
        assertThat(entry.rank()).isNull();
    }

    private void prepareStore(UUID storeId) {
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(storeKpiRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(new StoreKpiAggregate(
                        new BigDecimal("1200.00"),
                        new BigDecimal("10.000"),
                        new BigDecimal("800.00"),
                        10,
                        0,
                        0,
                        0,
                        0
                ));
        when(planService.context(storeId, PERIOD_START, PERIOD_END, new BigDecimal("1200.00")))
                .thenReturn(new RatingPlanContext(
                        true,
                        new BigDecimal("100.00"),
                        new BigDecimal("1000.00"),
                        new BigDecimal("4.00"),
                        new BigDecimal("3.00"),
                        new BigDecimal("7.00"),
                        new BigDecimal("1200.00"),
                        new BigDecimal("120.00")
                ));
        when(schemeService.effectiveOn(PERIOD_END)).thenReturn(scheme());
    }

    private EmployeePerformanceAggregate employee(
            UUID employeeId,
            String name,
            String revenue,
            String accessoryRevenue,
            String serviceRevenue,
            long shifts,
            String hours
    ) {
        BigDecimal accessories = new BigDecimal(accessoryRevenue);
        BigDecimal services = new BigDecimal(serviceRevenue);
        return new EmployeePerformanceAggregate(
                employeeId,
                name,
                true,
                true,
                true,
                new BigDecimal(revenue),
                accessories,
                services,
                accessories.add(services),
                shifts,
                new BigDecimal(hours)
        );
    }

    private AttachRateAggregate storeAttachRate() {
        return new AttachRateAggregate(
                "CASE_APPLE_IPHONE",
                "CASE_APPLE_IPHONE",
                AttachDenominatorCode.IPHONE,
                new BigDecimal("2.000"),
                new BigDecimal("10.000"),
                0,
                0,
                0
        );
    }

    private EmployeeAttachRateAggregate employeeAttachRate(
            UUID employeeId,
            String numerator,
            String denominator
    ) {
        return new EmployeeAttachRateAggregate(
                employeeId,
                "CASE_APPLE_IPHONE",
                "CASE_APPLE_IPHONE",
                AttachDenominatorCode.IPHONE,
                new BigDecimal(numerator),
                new BigDecimal(denominator)
        );
    }

    private RatingScheme scheme() {
        return new RatingScheme(
                "employee-rating-v1",
                LocalDate.of(1970, 1, 1),
                new RatingSchemeDefinition(
                        new BigDecimal("25.00"),
                        new BigDecimal("25.00"),
                        new BigDecimal("25.00"),
                        new BigDecimal("25.00"),
                        new BigDecimal("50.00"),
                        new BigDecimal("50.00"),
                        new BigDecimal("3.000"),
                        new BigDecimal("150.00"),
                        new BigDecimal("75.00")
                ),
                null
        );
    }

    private StoreKpiPeriod period() {
        return new StoreKpiPeriod(PERIOD_START, PERIOD_END);
    }
}
