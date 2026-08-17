package com.storeanalytics.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.EmployeeCategoryKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeCategoryKpiRepository;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.DeviceFamily;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmployeeCategoryKpiServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 7, 20);
    private static final LocalDate END = LocalDate.of(2026, 7, 26);

    private StoreRepository storeRepository;
    private EmployeeCategoryKpiRepository repository;
    private EmployeeCategoryKpiService service;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        repository = mock(EmployeeCategoryKpiRepository.class);
        service = new EmployeeCategoryKpiService(storeRepository, repository);
    }

    @Test
    void buildsCompleteCategoryAndOverlappingGroupProjection() {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(repository.aggregate(storeId, START, END)).thenReturn(List.of(
                aggregate(
                        employeeId,
                        "IPHONE_NEW_ASIS",
                        AnalyticsCategoryKind.DEVICE,
                        new CategoryFlags(true, true, false),
                        new AggregateValues("100.00", "1.000", "60.00", 1, 0)
                ),
                aggregate(
                        employeeId,
                        "SETUP_SERVICE",
                        AnalyticsCategoryKind.SERVICE,
                        new CategoryFlags(false, false, true),
                        new AggregateValues("50.00", "1.000", "0.00", 1, 1)
                )
        ));

        EmployeeCategoryKpiResult result = service.calculate(storeId, period());

        assertThat(result.formulaVersion()).isEqualTo("employee-category-kpi-v1");
        assertThat(result.categoryFormulaVersion()).isEqualTo("category-kpi-v3");
        EmployeeCategoryKpiEmployee employee = result.employees().getFirst();
        assertThat(employee.rankingEligible()).isTrue();
        assertThat(employee.netRevenue()).isEqualByComparingTo("150.00");
        assertThat(employee.dataQuality().completeCostData()).isFalse();
        assertThat(employee.dataQuality().includedItemCount()).isEqualTo(2);
        assertThat(employee.categories()).hasSize(2);

        assertThat(category(employee, "IPHONE_NEW_ASIS").metrics().revenueSharePercent())
                .isEqualByComparingTo("66.67");
        assertThat(group(employee, "PHONES").metrics().netRevenue())
                .isEqualByComparingTo("100.00");
        assertThat(group(employee, "DEVICES").metrics().netRevenue())
                .isEqualByComparingTo("100.00");
        assertThat(group(employee, "SERVICE").metrics().netRevenue())
                .isEqualByComparingTo("50.00");
        assertThat(group(employee, "SERVICE").metrics().costAmount()).isNull();
        assertThat(group(employee, "ADDITIONAL_REVENUE")
                .metrics().revenueSharePercent()).isEqualByComparingTo("33.33");
    }

    @Test
    void representsUnassignedFactsWithoutMakingThemRankingEligible() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(repository.aggregate(storeId, START, END)).thenReturn(List.of(
                new EmployeeCategoryKpiAggregate(
                        null,
                        null,
                        false,
                        false,
                        false,
                        false,
                        true,
                        "UNMAPPED",
                        "Не классифицировано",
                        AnalyticsCategoryKind.OTHER,
                        DeviceFamily.NONE,
                        true,
                        false,
                        false,
                        false,
                        new BigDecimal("20.00"),
                        new BigDecimal("1.000"),
                        new BigDecimal("10.00"),
                        1,
                        0,
                        0
                )
        ));

        EmployeeCategoryKpiEmployee employee =
                service.calculate(storeId, period()).employees().getFirst();

        assertThat(employee.employeeId()).isNull();
        assertThat(employee.displayName()).isEqualTo("Не назначен");
        assertThat(employee.unassigned()).isTrue();
        assertThat(employee.rankingEligible()).isFalse();
        assertThat(employee.dataQuality().unmappedItemCount()).isOne();
    }

    @Test
    void rejectsUnknownStoreBeforeProjectionQuery() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(false);

        assertThatThrownBy(() -> service.calculate(storeId, period()))
                .isInstanceOf(StoreNotFoundException.class);
        verifyNoInteractions(repository);
    }

    private EmployeeCategoryKpiAggregate aggregate(
            UUID employeeId,
            String categoryCode,
            AnalyticsCategoryKind categoryKind,
            CategoryFlags flags,
            AggregateValues values
    ) {
        return new EmployeeCategoryKpiAggregate(
                employeeId,
                "Сотрудник",
                true,
                true,
                true,
                true,
                false,
                categoryCode,
                categoryCode,
                categoryKind,
                flags.device() ? DeviceFamily.IPHONE : DeviceFamily.NONE,
                true,
                flags.phone(),
                flags.device(),
                flags.additional(),
                new BigDecimal(values.revenue()),
                new BigDecimal(values.quantity()),
                new BigDecimal(values.cost()),
                values.itemCount(),
                values.missingCostCount(),
                0
        );
    }

    private EmployeeCategoryKpiEntry category(
            EmployeeCategoryKpiEmployee employee,
            String code
    ) {
        return employee.categories().stream()
                .filter(entry -> entry.categoryCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private EmployeeCategoryKpiGroup group(
            EmployeeCategoryKpiEmployee employee,
            String code
    ) {
        return employee.groups().stream()
                .filter(entry -> entry.groupCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private StoreKpiPeriod period() {
        return new StoreKpiPeriod(START, END);
    }

    private record CategoryFlags(boolean phone, boolean device, boolean additional) {
    }

    private record AggregateValues(
            String revenue,
            String quantity,
            String cost,
            long itemCount,
            long missingCostCount
    ) {
    }
}
