package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireNumeric;
import static com.storeanalytics.common.validation.ModelValidation.require;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PayrollDailyPoolInput(
        LocalDate workDate,
        BigDecimal accessoryTurnover,
        BigDecimal serviceTurnover,
        BigDecimal playstationGrossProfit,
        BigDecimal paidRepairGrossProfit,
        BigDecimal tier1Quantity,
        BigDecimal tier2Quantity,
        int unmappedItemCount,
        int missingCostItemCount
) {

    public PayrollDailyPoolInput {
        workDate = requireNonNull(workDate, "workDate");
        accessoryTurnover = money(accessoryTurnover, "accessoryTurnover");
        serviceTurnover = money(serviceTurnover, "serviceTurnover");
        playstationGrossProfit = nullableMoney(
                playstationGrossProfit, "playstationGrossProfit"
        );
        paidRepairGrossProfit = nullableMoney(
                paidRepairGrossProfit, "paidRepairGrossProfit"
        );
        tier1Quantity = requireNumeric(tier1Quantity, "tier1Quantity", 19, 3);
        tier2Quantity = requireNumeric(tier2Quantity, "tier2Quantity", 19, 3);
        require(unmappedItemCount >= 0, "unmappedItemCount must not be negative");
        require(missingCostItemCount >= 0, "missingCostItemCount must not be negative");
    }

    public boolean complete() {
        return unmappedItemCount == 0 && missingCostItemCount == 0;
    }

    private static BigDecimal money(BigDecimal value, String fieldName) {
        return requireNumeric(value, fieldName, 19, 2);
    }

    private static BigDecimal nullableMoney(BigDecimal value, String fieldName) {
        return value == null ? null : money(value, fieldName);
    }
}
