package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.require;

import com.storeanalytics.common.persistence.AbstractCreatedEntity;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "payroll_daily_pools")
public class PayrollDailyPool extends AbstractCreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_run_id", nullable = false, updatable = false)
    private PayrollRun payrollRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "work_date", nullable = false, updatable = false)
    private LocalDate workDate;

    @Column(name = "accessory_turnover", nullable = false, precision = 19, scale = 2)
    private BigDecimal accessoryTurnover;

    @Column(name = "service_turnover", nullable = false, precision = 19, scale = 2)
    private BigDecimal serviceTurnover;

    @Column(name = "playstation_gross_profit", precision = 19, scale = 2)
    private BigDecimal playstationGrossProfit;

    @Column(name = "paid_repair_gross_profit", precision = 19, scale = 2)
    private BigDecimal paidRepairGrossProfit;

    @Column(name = "tier1_quantity", nullable = false, precision = 19, scale = 3)
    private BigDecimal tier1Quantity;

    @Column(name = "tier2_quantity", nullable = false, precision = 19, scale = 3)
    private BigDecimal tier2Quantity;

    @Column(name = "accessory_percentage_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal accessoryPercentageRate;

    @Column(name = "service_percentage_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal servicePercentageRate;

    @Column(name = "tier1_rate", nullable = false, precision = 19, scale = 2)
    private BigDecimal tier1Rate;

    @Column(name = "tier2_rate", nullable = false, precision = 19, scale = 2)
    private BigDecimal tier2Rate;

    @Column(name = "accessory_reward", nullable = false, precision = 19, scale = 2)
    private BigDecimal accessoryReward;

    @Column(name = "service_reward", nullable = false, precision = 19, scale = 2)
    private BigDecimal serviceReward;

    @Column(name = "playstation_reward", precision = 19, scale = 2)
    private BigDecimal playstationReward;

    @Column(name = "paid_repair_reward", precision = 19, scale = 2)
    private BigDecimal paidRepairReward;

    @Column(name = "tier1_reward", nullable = false, precision = 19, scale = 2)
    private BigDecimal tier1Reward;

    @Column(name = "tier2_reward", nullable = false, precision = 19, scale = 2)
    private BigDecimal tier2Reward;

    @Column(name = "fund_amount", precision = 19, scale = 2)
    private BigDecimal fundAmount;

    @Column(name = "shift_employee_count", nullable = false)
    private int shiftEmployeeCount;

    @Column(name = "unmapped_item_count", nullable = false)
    private int unmappedItemCount;

    @Column(name = "missing_cost_item_count", nullable = false)
    private int missingCostItemCount;

    @Column(name = "calculation_complete", nullable = false)
    private boolean calculationComplete;

    protected PayrollDailyPool() {
    }

    public PayrollDailyPool(
            PayrollRun payrollRun,
            PayrollDailyPoolInput input,
            PayrollDailyPoolAmounts amounts,
            int shiftEmployeeCount
    ) {
        this.payrollRun = requireNonNull(payrollRun, "payrollRun");
        this.store = payrollRun.getStore();
        PayrollDailyPoolInput validatedInput = requireNonNull(input, "input");
        PayrollDailyPoolAmounts validatedAmounts = requireNonNull(amounts, "amounts");
        require(shiftEmployeeCount >= 0, "shiftEmployeeCount must not be negative");
        workDate = validatedInput.workDate();
        accessoryTurnover = validatedInput.accessoryTurnover();
        serviceTurnover = validatedInput.serviceTurnover();
        playstationGrossProfit = validatedInput.playstationGrossProfit();
        paidRepairGrossProfit = validatedInput.paidRepairGrossProfit();
        tier1Quantity = validatedInput.tier1Quantity();
        tier2Quantity = validatedInput.tier2Quantity();
        unmappedItemCount = validatedInput.unmappedItemCount();
        missingCostItemCount = validatedInput.missingCostItemCount();
        accessoryPercentageRate = validatedAmounts.accessoryPercentageRate();
        servicePercentageRate = validatedAmounts.servicePercentageRate();
        tier1Rate = validatedAmounts.tier1Rate();
        tier2Rate = validatedAmounts.tier2Rate();
        accessoryReward = validatedAmounts.accessoryReward();
        serviceReward = validatedAmounts.serviceReward();
        playstationReward = validatedAmounts.playstationReward();
        paidRepairReward = validatedAmounts.paidRepairReward();
        tier1Reward = validatedAmounts.tier1Reward();
        tier2Reward = validatedAmounts.tier2Reward();
        fundAmount = validatedAmounts.fundAmount();
        this.shiftEmployeeCount = shiftEmployeeCount;
        calculationComplete = validatedInput.complete()
                && (fundAmount == null || shiftEmployeeCount > 0 || fundAmount.signum() == 0);
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public BigDecimal getAccessoryTurnover() {
        return accessoryTurnover;
    }

    public BigDecimal getServiceTurnover() {
        return serviceTurnover;
    }

    public BigDecimal getPlaystationGrossProfit() {
        return playstationGrossProfit;
    }

    public BigDecimal getPaidRepairGrossProfit() {
        return paidRepairGrossProfit;
    }

    public BigDecimal getTier1Quantity() {
        return tier1Quantity;
    }

    public BigDecimal getTier2Quantity() {
        return tier2Quantity;
    }

    public BigDecimal getAccessoryPercentageRate() {
        return accessoryPercentageRate;
    }

    public BigDecimal getServicePercentageRate() {
        return servicePercentageRate;
    }

    public BigDecimal getTier1Rate() {
        return tier1Rate;
    }

    public BigDecimal getTier2Rate() {
        return tier2Rate;
    }

    public BigDecimal getAccessoryReward() {
        return accessoryReward;
    }

    public BigDecimal getServiceReward() {
        return serviceReward;
    }

    public BigDecimal getPlaystationReward() {
        return playstationReward;
    }

    public BigDecimal getPaidRepairReward() {
        return paidRepairReward;
    }

    public BigDecimal getTier1Reward() {
        return tier1Reward;
    }

    public BigDecimal getTier2Reward() {
        return tier2Reward;
    }

    public BigDecimal getFundAmount() {
        return fundAmount;
    }

    public boolean isCalculationComplete() {
        return calculationComplete;
    }

    public int getShiftEmployeeCount() {
        return shiftEmployeeCount;
    }

    public int getUnmappedItemCount() {
        return unmappedItemCount;
    }

    public int getMissingCostItemCount() {
        return missingCostItemCount;
    }
}
