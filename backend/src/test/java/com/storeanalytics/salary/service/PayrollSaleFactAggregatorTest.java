package com.storeanalytics.salary.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.salary.repository.PayrollDailySalesAggregate;
import com.storeanalytics.salary.repository.PayrollSaleSourceFact;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayrollSaleFactAggregatorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 10);

    @Test
    void reproducesPayrollAggregationForSalesReturnsOverridesAndMissingCosts() {
        List<PayrollDailySalesAggregate> result = new PayrollSaleFactAggregator().aggregate(List.of(
                fact("ACCESSORY", "100.00", "50.00", "1.000", 1, false),
                fact("ACCESSORY", "20.00", "10.00", "0.200", -1, false),
                fact("TECH_TIER_1", "1000.00", "700.00", "1.000", 1, false),
                fact("PLAYSTATION_SUBSCRIPTION", "200.00", null, "1.000", 1, false),
                fact("SERVICE", "999.00", "0.00", "1.000", 1, true)
        ));

        assertThat(result).singleElement().satisfies(day -> {
            assertThat(day.netRevenue()).isEqualByComparingTo("1280.00");
            assertThat(day.accessoryTurnover()).isEqualByComparingTo("80.00");
            assertThat(day.serviceTurnover()).isEqualByComparingTo("0.00");
            assertThat(day.tier1Quantity()).isEqualByComparingTo("1.000");
            assertThat(day.playstationGrossProfit()).isNull();
            assertThat(day.missingCostItemCount()).isOne();
        });
    }

    private PayrollSaleSourceFact fact(
            String category,
            String amount,
            String cost,
            String quantity,
            int sign,
            boolean excluded
    ) {
        return new PayrollSaleSourceFact(
                UUID.randomUUID(),
                DATE,
                sign,
                new BigDecimal(quantity),
                new BigDecimal(amount),
                cost == null ? null : new BigDecimal(cost),
                UUID.randomUUID(),
                UUID.randomUUID(),
                category,
                null,
                category,
                null,
                null,
                excluded
        );
    }
}
