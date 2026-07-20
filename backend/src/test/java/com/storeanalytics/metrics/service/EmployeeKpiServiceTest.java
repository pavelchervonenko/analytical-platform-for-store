package com.storeanalytics.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.EmployeeKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeKpiRepository;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmployeeKpiServiceTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    private StoreRepository storeRepository;
    private EmployeeKpiRepository employeeKpiRepository;
    private EmployeeKpiService service;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        employeeKpiRepository = mock(EmployeeKpiRepository.class);
        service = new EmployeeKpiService(storeRepository, employeeKpiRepository);
    }

    @Test
    void mapsEligibilityAndKeepsNonParticipantMetrics() {
        UUID storeId = UUID.randomUUID();
        UUID eligibleEmployeeId = UUID.randomUUID();
        UUID nonParticipantId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(employeeKpiRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(
                        aggregate(
                                eligibleEmployeeId,
                                "Eligible",
                                new EmployeeState(true, true, true, true, false),
                                new EmployeeAmounts("300.00", "3.000", "200.00"),
                                new QualityCounts(2, 0, 0, 0)
                        ),
                        aggregate(
                                nonParticipantId,
                                "Not ranked",
                                new EmployeeState(true, true, true, false, false),
                                new EmployeeAmounts("80.00", "1.000", "30.00"),
                                new QualityCounts(1, 0, 0, 0)
                        )
                ));

        EmployeeKpiResult result = service.calculate(storeId, period());

        assertThat(result.formulaVersion()).isEqualTo("store-kpi-v1");
        assertThat(result.employees()).hasSize(2);
        EmployeeKpiEntry eligible = result.employees().getFirst();
        assertThat(eligible.rankingEligible()).isTrue();
        assertThat(eligible.grossProfit()).isEqualByComparingTo("100.00");
        assertThat(eligible.marginPercent()).isEqualByComparingTo("33.33");

        EmployeeKpiEntry nonParticipant = result.employees().get(1);
        assertThat(nonParticipant.participatesInRanking()).isFalse();
        assertThat(nonParticipant.rankingEligible()).isFalse();
        assertThat(nonParticipant.netRevenue()).isEqualByComparingTo("80.00");
    }

    @Test
    void marksUnassignedAndIncompleteCostWithoutInventingProfit() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(employeeKpiRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(aggregate(
                        null,
                        "Не назначен",
                        new EmployeeState(false, false, false, false, true),
                        new EmployeeAmounts("30.00", "1.000", "0.00"),
                        new QualityCounts(1, 1, 1, 0)
                )));

        EmployeeKpiEntry entry = service.calculate(storeId, period()).employees().getFirst();

        assertThat(entry.employeeId()).isNull();
        assertThat(entry.unassigned()).isTrue();
        assertThat(entry.rankingEligible()).isFalse();
        assertThat(entry.netRevenue()).isEqualByComparingTo("30.00");
        assertThat(entry.costAmount()).isNull();
        assertThat(entry.grossProfit()).isNull();
        assertThat(entry.marginPercent()).isNull();
        assertThat(entry.dataQuality().completeCostData()).isFalse();
    }

    @Test
    void rejectsUnknownStoreBeforeRunningAnalyticsQuery() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(false);

        assertThatThrownBy(() -> service.calculate(storeId, period()))
                .isInstanceOf(StoreNotFoundException.class);
        verifyNoInteractions(employeeKpiRepository);
    }

    private EmployeeKpiAggregate aggregate(
            UUID employeeId,
            String displayName,
            EmployeeState state,
            EmployeeAmounts amounts,
            QualityCounts quality
    ) {
        return new EmployeeKpiAggregate(
                employeeId,
                displayName,
                state.employeeActive(),
                state.assignedToStore(),
                state.assignmentActive(),
                state.participatesInRanking(),
                state.unassigned(),
                new BigDecimal(amounts.netRevenue()),
                new BigDecimal(amounts.netQuantity()),
                new BigDecimal(amounts.costAmount()),
                quality.includedItems(),
                quality.unmappedItems(),
                quality.missingCostItems(),
                quality.unexpectedZeroCostItems()
        );
    }

    private StoreKpiPeriod period() {
        return new StoreKpiPeriod(PERIOD_START, PERIOD_END);
    }

    private record EmployeeState(
            boolean employeeActive,
            boolean assignedToStore,
            boolean assignmentActive,
            boolean participatesInRanking,
            boolean unassigned
    ) {
    }

    private record EmployeeAmounts(
            String netRevenue,
            String netQuantity,
            String costAmount
    ) {
    }

    private record QualityCounts(
            long includedItems,
            long unmappedItems,
            long missingCostItems,
            long unexpectedZeroCostItems
    ) {
    }
}
