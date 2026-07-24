package com.storeanalytics.salary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.performance.model.EmployeeWorkShift;
import com.storeanalytics.performance.model.StorePerformancePlan;
import com.storeanalytics.salary.model.PayrollDailyAllocation;
import com.storeanalytics.salary.model.PayrollDailyPool;
import com.storeanalytics.salary.model.PayrollPlanResult;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollRunDefinition;
import com.storeanalytics.salary.model.PayrollRunQuality;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.model.PayrollSchemeDefinition;
import com.storeanalytics.salary.model.PayrollSourceFingerprint;
import com.storeanalytics.salary.model.PayrollStatement;
import com.storeanalytics.salary.repository.PayrollDailySalesAggregate;
import com.storeanalytics.salary.repository.PayrollRunRepository;
import com.storeanalytics.store.model.Store;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PayrollCalculationServiceTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 7);
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 7, 10);

    private PayrollCalculationSource source;
    private PayrollSourceFingerprintService fingerprintService;
    private AppUserRepository userRepository;
    private PayrollRunRepository runRepository;
    private PayrollSnapshotStore snapshotStore;
    private PayrollCalculationService service;

    @BeforeEach
    void setUp() {
        source = mock(PayrollCalculationSource.class);
        fingerprintService = mock(PayrollSourceFingerprintService.class);
        when(fingerprintService.capture(any(PayrollCalculationSourceData.class)))
                .thenReturn(fingerprint());
        userRepository = mock(AppUserRepository.class);
        runRepository = mock(PayrollRunRepository.class);
        snapshotStore = mock(PayrollSnapshotStore.class);
        service = new PayrollCalculationService(
                source, fingerprintService, userRepository, runRepository, snapshotStore,
                mock(com.storeanalytics.audit.service.AuditLogService.class)
        );
    }

    @Test
    void appliesAchievedRatesAndSplitsDailyFundEqually() {
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Store store = store(storeId);
        AppUser actor = mock(AppUser.class);
        PayrollScheme scheme = scheme();
        StorePerformancePlan plan = plan(store, "1000.00");
        Employee first = employee("First");
        Employee second = employee("Second");
        List<EmployeeWorkShift> shifts = List.of(
                shift(first, "4.00"), shift(second, "8.00")
        );
        PayrollCalculationSourceData sourceData = new PayrollCalculationSourceData(
                store,
                plan,
                scheme,
                List.of(sales("1200.00", "1000.00", "50.00", "100.00", "50.00", "2", "3")),
                shifts
        );
        when(source.load(storeId, MONTH)).thenReturn(sourceData);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(runRepository.findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
                storeId, MONTH.atDay(1)
        )).thenReturn(Optional.empty());
        when(runRepository.saveAndFlush(any(PayrollRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotStore.activeAdjustments(any(PayrollRun.class))).thenReturn(List.of());

        PayrollRun run = service.calculate(storeId, MONTH, null, actorId);

        assertThat(run.getPlanResult().revenueAchieved()).isTrue();
        assertThat(run.getPlanResult().accessoryAchieved()).isTrue();
        assertThat(run.getPlanResult().serviceAchieved()).isTrue();
        assertThat(run.isCalculationComplete()).isTrue();
        assertThat(run.getPlanResult().actualRevenue()).isEqualByComparingTo("1200.00");
        Snapshot snapshot = captureSnapshot();
        assertThat(snapshot.pools()).singleElement()
                .extracting(PayrollDailyPool::getFundAmount)
                .isEqualTo(new BigDecimal("2140.00"));
        assertThat(snapshot.allocations()).hasSize(2)
                .allSatisfy(allocation -> assertThat(allocation.getAmount())
                        .isEqualByComparingTo("1070.00"));
        assertThat(snapshot.allocations())
                .extracting(PayrollDailyAllocation::getWorkedHours)
                .containsExactlyInAnyOrder(
                        new BigDecimal("4.00"), new BigDecimal("8.00")
                );
        assertThat(snapshot.statements()).hasSize(2)
                .allSatisfy(statement -> {
                    assertThat(statement.getEarnedAmount()).isEqualByComparingTo("1070.00");
                    assertThat(statement.getAdvanceAmount()).isEqualByComparingTo("50000.00");
                    assertThat(statement.getPayableAmount()).isEqualByComparingTo("-48930.00");
                });
        assertThat(snapshot.statements())
                .extracting(PayrollStatement::getWorkedHours)
                .containsExactlyInAnyOrder(
                        new BigDecimal("4.00"), new BigDecimal("8.00")
                );
    }

    @Test
    void revenueMissDoesNotLowerAchievedAccessoryAndServiceRates() {
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Store store = store(storeId);
        AppUser actor = mock(AppUser.class);
        Employee employee = employee("Employee");
        PayrollCalculationSourceData sourceData = new PayrollCalculationSourceData(
                store,
                plan(store, "1000.00"),
                scheme(),
                List.of(sales("900.00", "1000.00", "50.00", "100.00", "50.00", "2", "3")),
                List.of(shift(employee))
        );
        when(source.load(storeId, MONTH)).thenReturn(sourceData);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(runRepository.findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
                storeId, MONTH.atDay(1)
        )).thenReturn(Optional.empty());
        when(runRepository.saveAndFlush(any(PayrollRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotStore.activeAdjustments(any(PayrollRun.class))).thenReturn(List.of());

        PayrollRun run = service.calculate(storeId, MONTH, null, actorId);

        assertThat(run.getPlanResult().revenueAchieved()).isFalse();
        assertThat(run.getPlanResult().accessoryAchieved()).isTrue();
        assertThat(run.getPlanResult().serviceAchieved()).isTrue();
        Snapshot snapshot = captureSnapshot();
        assertThat(snapshot.pools()).singleElement()
                .extracting(PayrollDailyPool::getFundAmount)
                .isEqualTo(new BigDecimal("1640.00"));
        assertThat(snapshot.statements()).singleElement().satisfies(statement -> {
            assertThat(statement.getEarnedAmount()).isEqualByComparingTo("1640.00");
            assertThat(statement.getPayableAmount()).isEqualByComparingTo("-48360.00");
        });
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void keepsSameEmployeePayrollIndependentAcrossStores() {
        UUID firstStoreId = UUID.randomUUID();
        UUID secondStoreId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Store firstStore = store(firstStoreId);
        Store secondStore = store(secondStoreId);
        AppUser actor = mock(AppUser.class);
        Employee employee = employee("Employee");

        PayrollCalculationSourceData firstSource = new PayrollCalculationSourceData(
                firstStore,
                plan(firstStore, "1000.00"),
                scheme(),
                List.of(sales("1000.00", "100.00", "0.00", "0.00", "0.00", "0", "0")),
                List.of(shift(employee))
        );
        PayrollCalculationSourceData secondSource = new PayrollCalculationSourceData(
                secondStore,
                plan(secondStore, "1000.00"),
                scheme(),
                List.of(sales("1000.00", "200.00", "0.00", "0.00", "0.00", "0", "0")),
                List.of(shift(employee))
        );
        when(source.load(firstStoreId, MONTH)).thenReturn(firstSource);
        when(source.load(secondStoreId, MONTH)).thenReturn(secondSource);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(runRepository.findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
                firstStoreId, MONTH.atDay(1)
        )).thenReturn(Optional.empty());
        when(runRepository.findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
                secondStoreId, MONTH.atDay(1)
        )).thenReturn(Optional.empty());
        when(runRepository.saveAndFlush(any(PayrollRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotStore.activeAdjustments(any(PayrollRun.class))).thenReturn(List.of());

        PayrollRun firstRun = service.calculate(firstStoreId, MONTH, null, actorId);
        PayrollRun secondRun = service.calculate(secondStoreId, MONTH, null, actorId);

        assertThat(firstRun.getStore()).isSameAs(firstStore);
        assertThat(secondRun.getStore()).isSameAs(secondStore);
        ArgumentCaptor<List<PayrollStatement>> statements = ArgumentCaptor.forClass(List.class);
        verify(snapshotStore, times(2)).replaceCalculatedSnapshot(
                any(PayrollRun.class), any(), any(), statements.capture()
        );
        assertThat(statements.getAllValues()).hasSize(2);
        assertThat(statements.getAllValues().get(0)).singleElement().satisfies(statement -> {
            assertThat(statement.getEmployee()).isSameAs(employee);
            assertThat(statement.getEarnedAmount()).isEqualByComparingTo("20.00");
        });
        assertThat(statements.getAllValues().get(1)).singleElement().satisfies(statement -> {
            assertThat(statement.getEmployee()).isSameAs(employee);
            assertThat(statement.getEarnedAmount()).isEqualByComparingTo("40.00");
        });
    }

    @Test
    void fullyReversedZeroFundDayDoesNotRequireShift() {
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Store store = store(storeId);
        AppUser actor = mock(AppUser.class);
        PayrollCalculationSourceData sourceData = new PayrollCalculationSourceData(
                store,
                plan(store, "1000.00"),
                scheme(),
                List.of(sales("0.00", "0.00", "0.00", "0.00", "0.00", "0", "0")),
                List.of()
        );
        when(source.load(storeId, MONTH)).thenReturn(sourceData);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(runRepository.findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
                storeId, MONTH.atDay(1)
        )).thenReturn(Optional.empty());
        when(runRepository.saveAndFlush(any(PayrollRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotStore.activeAdjustments(any(PayrollRun.class))).thenReturn(List.of());

        PayrollRun run = service.calculate(storeId, MONTH, null, actorId);

        assertThat(run.isCalculationComplete()).isTrue();
        assertThat(run.getDaysWithoutShift()).isZero();
        Snapshot snapshot = captureSnapshot();
        assertThat(snapshot.allocations()).isEmpty();
        assertThat(snapshot.statements()).isEmpty();
    }

    @Test
    void missingGrossProfitCostBlocksApproval() {
        Store store = store(UUID.randomUUID());
        AppUser actor = mock(AppUser.class);
        PayrollRun run = new PayrollRun(new PayrollRunDefinition(
                store,
                MONTH.atDay(1),
                1,
                null,
                null,
                scheme(),
                new PayrollPlanResult(
                        new BigDecimal("1000.00"),
                        new BigDecimal("900.00"),
                        false,
                        new BigDecimal("3.90"),
                        BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2),
                        false,
                        new BigDecimal("3.00"),
                        BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2),
                        false
                ),
                new PayrollRunQuality(false, 0, 1, 0),
                fingerprint(),
                actor
        ));

        assertThatThrownBy(() -> run.approve(actor, Instant.parse("2026-08-01T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Snapshot captureSnapshot() {
        ArgumentCaptor<List<PayrollDailyPool>> pools = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<PayrollDailyAllocation>> allocations = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<PayrollStatement>> statements = ArgumentCaptor.forClass(List.class);
        verify(snapshotStore).replaceCalculatedSnapshot(
                any(PayrollRun.class), pools.capture(), allocations.capture(), statements.capture()
        );
        return new Snapshot(pools.getValue(), allocations.getValue(), statements.getValue());
    }

    private Store store(UUID id) {
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(id);
        return store;
    }

    private StorePerformancePlan plan(Store store, String revenueTarget) {
        StorePerformancePlan plan = mock(StorePerformancePlan.class);
        when(plan.getStore()).thenReturn(store);
        when(plan.getPlanMonth()).thenReturn(MONTH.atDay(1));
        when(plan.getRevenueTarget()).thenReturn(new BigDecimal(revenueTarget));
        when(plan.getAccessoryShareTarget()).thenReturn(new BigDecimal("3.90"));
        when(plan.getServiceShareTarget()).thenReturn(new BigDecimal("3.00"));
        return plan;
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

    private Employee employee(String name) {
        Employee employee = mock(Employee.class);
        when(employee.getId()).thenReturn(UUID.randomUUID());
        when(employee.getFullName()).thenReturn(name);
        return employee;
    }

    private EmployeeWorkShift shift(Employee employee) {
        return shift(employee, "11.00");
    }

    private EmployeeWorkShift shift(Employee employee, String workedHours) {
        EmployeeWorkShift shift = mock(EmployeeWorkShift.class);
        when(shift.getWorkDate()).thenReturn(WORK_DATE);
        when(shift.getEmployee()).thenReturn(employee);
        when(shift.getWorkedHours()).thenReturn(new BigDecimal(workedHours));
        return shift;
    }

    private PayrollDailySalesAggregate sales(
            String revenue,
            String accessory,
            String service,
            String playstationProfit,
            String repairProfit,
            String tier1,
            String tier2
    ) {
        return new PayrollDailySalesAggregate(
                WORK_DATE,
                new BigDecimal(revenue),
                new BigDecimal(accessory),
                new BigDecimal(service),
                new BigDecimal(playstationProfit),
                new BigDecimal(repairProfit),
                new BigDecimal(tier1),
                new BigDecimal(tier2),
                0,
                0
        );
    }

    private PayrollSourceFingerprint fingerprint() {
        String hash = "0".repeat(64);
        return new PayrollSourceFingerprint(1, hash, hash, hash, hash, hash);
    }

    private record Snapshot(
            List<PayrollDailyPool> pools,
            List<PayrollDailyAllocation> allocations,
            List<PayrollStatement> statements
    ) {
    }
}
