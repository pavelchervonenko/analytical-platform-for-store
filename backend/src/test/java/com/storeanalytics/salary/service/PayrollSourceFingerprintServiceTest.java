package com.storeanalytics.salary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.performance.model.EmployeeWorkShift;
import com.storeanalytics.performance.model.StorePerformancePlan;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.model.PayrollSourceFingerprint;
import com.storeanalytics.salary.repository.PayrollDailySalesAggregate;
import com.storeanalytics.salary.repository.PayrollSaleSourceFact;
import com.storeanalytics.store.model.Store;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayrollSourceFingerprintServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 10);
    private final PayrollSourceFingerprintService service =
            new PayrollSourceFingerprintService();

    @Test
    void separatesSalesClassificationShiftsPlanAndSchemeChanges() {
        Fixture fixture = fixture();
        PayrollSourceFingerprint base = service.capture(fixture.source());

        PayrollSourceFingerprint salesChanged = service.capture(fixture.withFact(
                fact(fixture.fact().effectivePayrollCategory(), "101.00")
        ));
        assertThat(salesChanged.salesHash()).isNotEqualTo(base.salesHash());
        assertThat(salesChanged.classificationHash()).isEqualTo(base.classificationHash());

        PayrollSourceFingerprint classificationChanged = service.capture(fixture.withFact(
                fact("SERVICE", "100.00")
        ));
        assertThat(classificationChanged.salesHash()).isEqualTo(base.salesHash());
        assertThat(classificationChanged.classificationHash())
                .isNotEqualTo(base.classificationHash());

        when(fixture.shift().getWorkedHours()).thenReturn(new BigDecimal("8.00"));
        PayrollSourceFingerprint shiftChanged = service.capture(fixture.source());
        assertThat(shiftChanged.shiftsHash()).isNotEqualTo(base.shiftsHash());

        when(fixture.plan().getRevenueTarget()).thenReturn(new BigDecimal("1100.00"));
        PayrollSourceFingerprint planChanged = service.capture(fixture.source());
        assertThat(planChanged.planHash()).isNotEqualTo(base.planHash());

        when(fixture.scheme().getAdvanceAmount()).thenReturn(new BigDecimal("51000.00"));
        PayrollSourceFingerprint schemeChanged = service.capture(fixture.source());
        assertThat(schemeChanged.schemeHash()).isNotEqualTo(base.schemeHash());
    }

    @Test
    void normalizesDecimalScale() {
        Fixture fixture = fixture();
        PayrollSourceFingerprint base = service.capture(fixture.source());

        PayrollSourceFingerprint sameValue = service.capture(fixture.withFact(
                fact("ACCESSORY", "100.0000")
        ));

        assertThat(sameValue.salesHash()).isEqualTo(base.salesHash());
    }

    private Fixture fixture() {
        Store store = mock(Store.class);
        StorePerformancePlan plan = mock(StorePerformancePlan.class);
        when(plan.getPlanMonth()).thenReturn(DATE.withDayOfMonth(1));
        when(plan.getRevenueTarget()).thenReturn(new BigDecimal("1000.00"));
        when(plan.getAccessoryShareTarget()).thenReturn(new BigDecimal("3.90"));
        when(plan.getServiceShareTarget()).thenReturn(new BigDecimal("3.00"));
        PayrollScheme scheme = mock(PayrollScheme.class);
        when(scheme.getId()).thenReturn(UUID.randomUUID());
        when(scheme.getCode()).thenReturn("seller-payroll-v1");
        when(scheme.getEffectiveFrom()).thenReturn(LocalDate.of(1970, 1, 1));
        when(scheme.getAchievedPercentage()).thenReturn(new BigDecimal("20.00"));
        when(scheme.getMissedPercentage()).thenReturn(new BigDecimal("15.00"));
        when(scheme.getAchievedTier1Rate()).thenReturn(new BigDecimal("500.00"));
        when(scheme.getMissedTier1Rate()).thenReturn(new BigDecimal("400.00"));
        when(scheme.getAchievedTier2Rate()).thenReturn(new BigDecimal("300.00"));
        when(scheme.getMissedTier2Rate()).thenReturn(new BigDecimal("200.00"));
        when(scheme.getAdvanceAmount()).thenReturn(new BigDecimal("50000.00"));
        Employee employee = mock(Employee.class);
        when(employee.getId()).thenReturn(UUID.randomUUID());
        EmployeeWorkShift shift = mock(EmployeeWorkShift.class);
        when(shift.getWorkDate()).thenReturn(DATE);
        when(shift.getEmployee()).thenReturn(employee);
        when(shift.getWorkedHours()).thenReturn(new BigDecimal("11.00"));
        when(shift.isActive()).thenReturn(true);
        PayrollSaleSourceFact fact = fact("ACCESSORY", "100.00");
        PayrollCalculationSourceData source = new PayrollCalculationSourceData(
                store, plan, scheme, List.of(aggregate()), List.of(shift), List.of(fact)
        );
        return new Fixture(source, plan, scheme, shift, fact);
    }

    private PayrollSaleSourceFact fact(String category, String netAmount) {
        return new PayrollSaleSourceFact(
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                DATE,
                1,
                BigDecimal.ONE,
                new BigDecimal(netAmount),
                new BigDecimal("50.00"),
                UUID.fromString("00000000-0000-0000-0000-000000000102"),
                UUID.fromString("00000000-0000-0000-0000-000000000103"),
                "ACCESSORY",
                null,
                category,
                null,
                null,
                false
        );
    }

    private PayrollDailySalesAggregate aggregate() {
        return new PayrollDailySalesAggregate(
                DATE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                0
        );
    }

    private record Fixture(
            PayrollCalculationSourceData source,
            StorePerformancePlan plan,
            PayrollScheme scheme,
            EmployeeWorkShift shift,
            PayrollSaleSourceFact fact
    ) {

        private PayrollCalculationSourceData withFact(PayrollSaleSourceFact replacement) {
            return new PayrollCalculationSourceData(
                    source.store(),
                    plan,
                    scheme,
                    source.dailySales(),
                    source.shifts(),
                    List.of(replacement)
            );
        }
    }
}
