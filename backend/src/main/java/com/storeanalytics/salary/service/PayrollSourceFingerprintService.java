package com.storeanalytics.salary.service;

import com.storeanalytics.performance.model.EmployeeWorkShift;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.model.PayrollSourceFingerprint;
import com.storeanalytics.salary.repository.PayrollDailySalesAggregate;
import com.storeanalytics.salary.repository.PayrollSaleSourceFact;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class PayrollSourceFingerprintService {

    PayrollSourceFingerprint capture(PayrollCalculationSourceData source) {
        return new PayrollSourceFingerprint(
                PayrollSourceFingerprint.CURRENT_VERSION,
                salesHash(source),
                shiftsHash(source.shifts()),
                planHash(source),
                classificationHash(source.saleSourceFacts()),
                schemeHash(source.scheme())
        );
    }

    private String salesHash(PayrollCalculationSourceData source) {
        CanonicalHasher hasher = new CanonicalHasher("payroll-sales-v1");
        List<PayrollSaleSourceFact> facts = source.saleSourceFacts();
        if (facts.isEmpty()) {
            source.dailySales().stream()
                    .sorted(Comparator.comparing(PayrollDailySalesAggregate::workDate))
                    .forEach(row -> hasher.add(
                            row.workDate(), row.netRevenue(), row.accessoryTurnover(),
                            row.serviceTurnover(), row.playstationGrossProfit(),
                            row.paidRepairGrossProfit(), row.tier1Quantity(),
                            row.tier2Quantity(), row.unmappedItemCount(),
                            row.missingCostItemCount()
                    ));
        } else {
            facts.stream()
                    .sorted(Comparator.comparing(PayrollSaleSourceFact::payrollDate)
                            .thenComparing(PayrollSaleSourceFact::itemId))
                    .forEach(fact -> hasher.add(
                            fact.itemId(), fact.payrollDate(), fact.sign(), fact.quantity(),
                            fact.netAmount(), fact.costAmount()
                    ));
        }
        return hasher.finish();
    }

    private String classificationHash(List<PayrollSaleSourceFact> facts) {
        CanonicalHasher hasher = new CanonicalHasher("payroll-classification-v1");
        facts.stream()
                .sorted(Comparator.comparing(PayrollSaleSourceFact::payrollDate)
                        .thenComparing(PayrollSaleSourceFact::itemId))
                .forEach(fact -> hasher.add(
                        fact.itemId(), fact.productId(), fact.analyticsCategoryId(),
                        fact.basePayrollCategory(), fact.overrideAssignmentId(),
                        fact.effectivePayrollCategory(), fact.overrideValidFrom(),
                        fact.overrideValidTo(), fact.excluded()
                ));
        return hasher.finish();
    }

    private String shiftsHash(List<EmployeeWorkShift> shifts) {
        CanonicalHasher hasher = new CanonicalHasher("payroll-shifts-v1");
        shifts.stream()
                .sorted(Comparator.comparing(EmployeeWorkShift::getWorkDate)
                        .thenComparing(shift -> shift.getEmployee().getId()))
                .forEach(shift -> hasher.add(
                        shift.getWorkDate(), shift.getEmployee().getId(),
                        shift.getWorkedHours(), shift.isActive()
                ));
        return hasher.finish();
    }

    private String planHash(PayrollCalculationSourceData source) {
        return new CanonicalHasher("payroll-plan-v1")
                .add(
                        source.plan().getPlanMonth(),
                        source.plan().getRevenueTarget(),
                        source.plan().getAccessoryShareTarget(),
                        source.plan().getServiceShareTarget()
                )
                .finish();
    }

    private String schemeHash(PayrollScheme scheme) {
        return new CanonicalHasher("payroll-scheme-v1")
                .add(
                        scheme.getId(), scheme.getCode(), scheme.getEffectiveFrom(),
                        scheme.getAchievedPercentage(), scheme.getMissedPercentage(),
                        scheme.getAchievedTier1Rate(), scheme.getMissedTier1Rate(),
                        scheme.getAchievedTier2Rate(), scheme.getMissedTier2Rate(),
                        scheme.getAdvanceAmount()
                )
                .finish();
    }

    private static final class CanonicalHasher {

        private final MessageDigest digest;

        private CanonicalHasher(String namespace) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
            add(namespace);
        }

        private CanonicalHasher add(Object... values) {
            for (Object value : values) {
                String text = canonical(value);
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) ';');
            }
            return this;
        }

        private String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }

        private String canonical(Object value) {
            if (value == null) {
                return "<null>";
            }
            if (value instanceof BigDecimal decimal) {
                return decimal.stripTrailingZeros().toPlainString();
            }
            return value.toString();
        }
    }
}
