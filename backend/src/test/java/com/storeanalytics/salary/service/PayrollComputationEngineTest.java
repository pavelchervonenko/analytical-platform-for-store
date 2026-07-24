package com.storeanalytics.salary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.performance.model.StorePerformancePlan;
import com.storeanalytics.salary.model.PayrollDailyPoolAmounts;
import com.storeanalytics.salary.model.PayrollPlanStatus;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.model.PayrollSchemeDefinition;
import com.storeanalytics.salary.repository.PayrollDailySalesAggregate;
import com.storeanalytics.store.model.Store;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PayrollComputationEngineTest {

    private static final LocalDate WORK_DATE = LocalDate.of(2026, 7, 10);

    private final PayrollComputationEngine engine = new PayrollComputationEngine();

    @ParameterizedTest
    @CsvSource({
        "false, false, false, 950.00",
        "false, false, true,  995.00",
        "false, true,  false, 955.00",
        "false, true,  true,  1000.00",
        "true,  false, false, 1250.00",
        "true,  false, true,  1295.00",
        "true,  true,  false, 1255.00",
        "true,  true,  true,  1300.00"
    })
    void appliesEachPlanOnlyToItsOwnRewardDirection(
            boolean revenueAchieved,
            boolean accessoryAchieved,
            boolean serviceAchieved,
            String expectedFund
    ) {
        PayrollPlanStatus status = new PayrollPlanStatus(
                revenueAchieved, accessoryAchieved, serviceAchieved
        );
        String accessoryRate = accessoryAchieved ? "20.00" : "15.00";
        String serviceRate = serviceAchieved ? "20.00" : "15.00";
        String tier1Rate = revenueAchieved ? "500.00" : "400.00";
        String tier2Rate = revenueAchieved ? "300.00" : "200.00";


        PayrollDailyPoolAmounts result = engine.amounts(sales(), scheme(), status);

        assertThat(result.accessoryPercentageRate()).isEqualByComparingTo(accessoryRate);
        assertThat(result.servicePercentageRate()).isEqualByComparingTo(serviceRate);
        assertThat(result.tier1Rate()).isEqualByComparingTo(tier1Rate);
        assertThat(result.tier2Rate()).isEqualByComparingTo(tier2Rate);
        assertThat(result.playstationReward())
                .isEqualByComparingTo(percent("300.00", serviceRate));
        assertThat(result.paidRepairReward())
                .isEqualByComparingTo(percent("400.00", serviceRate));
        assertThat(result.fundAmount()).isEqualByComparingTo(expectedFund);
    }

    @Test
    void doesNotUseRoundedShareToDecidePlan() {
        StorePerformancePlan plan = mock(StorePerformancePlan.class);
        when(plan.getRevenueTarget()).thenReturn(new BigDecimal("10000.00"));
        when(plan.getAccessoryShareTarget()).thenReturn(new BigDecimal("3.90"));
        when(plan.getServiceShareTarget()).thenReturn(new BigDecimal("3.00"));
        PayrollDailySalesAggregate sales = new PayrollDailySalesAggregate(
                WORK_DATE,
                new BigDecimal("10000.00"),
                new BigDecimal("389.60"),
                new BigDecimal("300.00"),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(3),
                BigDecimal.ZERO.setScale(3),
                0,
                0
        );
        PayrollCalculationSourceData source = new PayrollCalculationSourceData(
                mock(Store.class), plan, scheme(), List.of(sales), List.of()
        );

        PayrollComputationResult result = engine.compute(source);

        assertThat(result.planResult().revenueAchieved()).isTrue();
        assertThat(result.planResult().accessoryAchieved()).isFalse();
        assertThat(result.planResult().serviceAchieved()).isTrue();
        assertThat(result.planResult().actualAccessorySharePercent())
                .isEqualByComparingTo("3.90");
        assertThat(result.planResult().actualServiceSharePercent())
                .isEqualByComparingTo("3.00");
    }

    @Test
    void marksSharePlansMissedWhenRevenueIsZero() {
        StorePerformancePlan plan = mock(StorePerformancePlan.class);
        when(plan.getRevenueTarget()).thenReturn(new BigDecimal("10000.00"));
        when(plan.getAccessoryShareTarget()).thenReturn(new BigDecimal("3.90"));
        when(plan.getServiceShareTarget()).thenReturn(new BigDecimal("3.00"));
        PayrollDailySalesAggregate sales = new PayrollDailySalesAggregate(
                WORK_DATE,
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(3),
                BigDecimal.ZERO.setScale(3),
                0,
                0
        );
        PayrollCalculationSourceData source = new PayrollCalculationSourceData(
                mock(Store.class), plan, scheme(), List.of(sales), List.of()
        );

        PayrollComputationResult result = engine.compute(source);

        assertThat(result.planResult().revenueAchieved()).isFalse();
        assertThat(result.planResult().actualAccessorySharePercent()).isNull();
        assertThat(result.planResult().accessoryAchieved()).isFalse();
        assertThat(result.planResult().actualServiceSharePercent()).isNull();
        assertThat(result.planResult().serviceAchieved()).isFalse();
    }

    private PayrollDailySalesAggregate sales() {
        return new PayrollDailySalesAggregate(
                WORK_DATE,
                new BigDecimal("1000.00"),
                new BigDecimal("100.00"),
                new BigDecimal("200.00"),
                new BigDecimal("300.00"),
                new BigDecimal("400.00"),
                new BigDecimal("1.000"),
                new BigDecimal("2.000"),
                0,
                0
        );
    }

    private PayrollScheme scheme() {
        return new PayrollScheme(
                "seller-payroll-v1",
                LocalDate.of(1970, 1, 1),
                new PayrollSchemeDefinition(
                        new BigDecimal("20.00"),
                        new BigDecimal("15.00"),
                        new BigDecimal("500.00"),
                        new BigDecimal("400.00"),
                        new BigDecimal("300.00"),
                        new BigDecimal("200.00"),
                        new BigDecimal("50000.00")
                ),
                null
        );
    }

    private BigDecimal percent(String base, String rate) {
        return new BigDecimal(base)
                .multiply(new BigDecimal(rate))
                .divide(BigDecimal.valueOf(100));
    }
}
