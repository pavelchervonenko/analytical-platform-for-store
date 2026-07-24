package com.storeanalytics.salary.service;

import com.storeanalytics.salary.repository.PayrollDailySalesAggregate;
import com.storeanalytics.salary.repository.PayrollSaleSourceFact;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

class PayrollSaleFactAggregator {

    List<PayrollDailySalesAggregate> aggregate(List<PayrollSaleSourceFact> facts) {
        Map<java.time.LocalDate, DailyAccumulator> days = new TreeMap<>();
        facts.stream()
                .filter(fact -> !fact.excluded())
                .forEach(fact -> days.computeIfAbsent(
                        fact.payrollDate(), ignored -> new DailyAccumulator()
                ).add(fact));
        List<PayrollDailySalesAggregate> result = new ArrayList<>(days.size());
        days.forEach((date, day) -> result.add(day.result(date)));
        return List.copyOf(result);
    }

    private static final class DailyAccumulator {

        private BigDecimal revenue = moneyZero();
        private BigDecimal accessory = moneyZero();
        private BigDecimal service = moneyZero();
        private BigDecimal playstationProfit = moneyZero();
        private BigDecimal paidRepairProfit = moneyZero();
        private BigDecimal tier1Quantity = quantityZero();
        private BigDecimal tier2Quantity = quantityZero();
        private int unmappedItems;
        private int missingCostItems;
        private boolean playstationCostMissing;
        private boolean paidRepairCostMissing;

        private void add(PayrollSaleSourceFact fact) {
            BigDecimal signedAmount = signed(fact.netAmount(), fact.sign());
            revenue = revenue.add(signedAmount);
            switch (fact.effectivePayrollCategory()) {
                case "ACCESSORY" -> accessory = accessory.add(signedAmount);
                case "SERVICE" -> service = service.add(signedAmount);
                case "PLAYSTATION_SUBSCRIPTION" -> addPlaystation(fact);
                case "PAID_REPAIR" -> addPaidRepair(fact);
                case "TECH_TIER_1" -> tier1Quantity = tier1Quantity.add(
                        signed(fact.quantity(), fact.sign())
                );
                case "TECH_TIER_2" -> tier2Quantity = tier2Quantity.add(
                        signed(fact.quantity(), fact.sign())
                );
                case "UNMAPPED" -> unmappedItems++;
                default -> {
                    // The category contributes to revenue but has no dedicated payroll reward.
                }
            }
        }

        private void addPlaystation(PayrollSaleSourceFact fact) {
            if (fact.costAmount() == null) {
                playstationCostMissing = true;
                missingCostItems++;
            } else {
                playstationProfit = playstationProfit.add(grossProfit(fact));
            }
        }

        private void addPaidRepair(PayrollSaleSourceFact fact) {
            if (fact.costAmount() == null) {
                paidRepairCostMissing = true;
                missingCostItems++;
            } else {
                paidRepairProfit = paidRepairProfit.add(grossProfit(fact));
            }
        }

        private BigDecimal grossProfit(PayrollSaleSourceFact fact) {
            return signed(fact.netAmount().subtract(fact.costAmount()), fact.sign());
        }

        private PayrollDailySalesAggregate result(java.time.LocalDate date) {
            return new PayrollDailySalesAggregate(
                    date,
                    revenue,
                    accessory,
                    service,
                    playstationCostMissing ? null : playstationProfit,
                    paidRepairCostMissing ? null : paidRepairProfit,
                    tier1Quantity,
                    tier2Quantity,
                    unmappedItems,
                    missingCostItems
            );
        }

        private BigDecimal signed(BigDecimal value, int sign) {
            return sign == 1 ? value : value.negate();
        }

        private static BigDecimal moneyZero() {
            return BigDecimal.ZERO.setScale(2);
        }

        private static BigDecimal quantityZero() {
            return BigDecimal.ZERO.setScale(3);
        }
    }
}
