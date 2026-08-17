package com.storeanalytics.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.metrics.model.ReportSnapshot;
import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.metrics.model.ReportType;
import com.storeanalytics.metrics.repository.ReportSnapshotRepository;
import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.AverageKpiResult;
import com.storeanalytics.metrics.service.AverageMetricComparison;
import com.storeanalytics.metrics.service.AverageMetricSnapshot;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.performance.service.StorePlanProgressView;
import com.storeanalytics.quality.service.StorePeriodQualityView;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollRunStatus;
import com.storeanalytics.salary.service.PayrollRunDetailView;
import com.storeanalytics.salary.service.PayrollRunSummaryView;
import com.storeanalytics.store.model.Store;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MonthlyReportFinalizationServiceTest {

    private ReportSnapshotRepository repository;
    private ReportSnapshotCodec codec;
    private MonthlyReportCalculators calculators;
    private AuditLogService auditLogService;
    private MonthlyReportFinalizationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReportSnapshotRepository.class);
        codec = mock(ReportSnapshotCodec.class);
        calculators = mock(MonthlyReportCalculators.class);
        auditLogService = mock(AuditLogService.class);
        service = new MonthlyReportFinalizationService(
                repository,
                codec,
                calculators,
                auditLogService,
                Clock.fixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void createsOneFinalizedSnapshotForExactPaidPayrollRun() {
        UUID storeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Store store = store(storeId);
        PayrollRun run = paidRun(runId, store);
        AppUser actor = actor();
        PayrollRunDetailView payroll = payroll(runId);
        MonthlyReportParts parts = parts(storeId);
        when(repository.findByPayrollRunId(runId)).thenReturn(Optional.empty());
        when(repository
                .findFirstByStoreIdAndReportTypeAndPeriodStartAndPeriodEndAndStatusOrderByRevisionDesc(
                        storeId,
                        ReportType.MONTHLY,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31),
                        ReportStatus.FINALIZED
                )).thenReturn(Optional.empty());
        when(calculators.calculate(storeId, java.time.YearMonth.of(2026, 7)))
                .thenReturn(parts);
        when(codec.encode(any(MonthlyReportPayload.class)))
                .thenReturn(new EncodedReport("{}", "1".repeat(64)));
        when(codec.sourceHash(any(MonthlyReportSource.class))).thenReturn("0".repeat(64));
        when(repository.saveAndFlush(any(ReportSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReportSnapshot result = service.finalizePaidRun(run, payroll, actor);

        assertThat(result.getReportType()).isEqualTo(ReportType.MONTHLY);
        assertThat(result.getStatus()).isEqualTo(ReportStatus.FINALIZED);
        assertThat(result.getRevision()).isEqualTo(1);
        assertThat(result.getPayrollRun()).isSameAs(run);
        assertThat(result.getPayloadHash()).isEqualTo("1".repeat(64));
        assertThat(result.getGeneratedAt())
                .isEqualTo(Instant.parse("2026-08-02T10:00:00Z"));
        ArgumentCaptor<MonthlyReportPayload> payload =
                ArgumentCaptor.forClass(MonthlyReportPayload.class);
        verify(codec).encode(payload.capture());
        assertThat(payload.getValue().averageKpi().averageReceipt().value())
                .isEqualByComparingTo("150.00");
        verify(auditLogService).record(
                any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void repeatedFinalizationReturnsExistingSnapshotWithoutRecalculation() {
        UUID runId = UUID.randomUUID();
        Store store = store(UUID.randomUUID());
        PayrollRun run = paidRun(runId, store);
        ReportSnapshot existing = mock(ReportSnapshot.class);
        when(repository.findByPayrollRunId(runId)).thenReturn(Optional.of(existing));

        ReportSnapshot result = service.finalizePaidRun(run, payroll(runId), actor());

        assertThat(result).isSameAs(existing);
        verify(calculators, never()).calculate(any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    private Store store(UUID storeId) {
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(store.getName()).thenReturn("Store");
        when(store.getAddress()).thenReturn("Address");
        when(store.getReportingStartedOn()).thenReturn(LocalDate.of(2026, 1, 1));
        return store;
    }

    private PayrollRun paidRun(UUID runId, Store store) {
        PayrollRun run = mock(PayrollRun.class);
        when(run.getId()).thenReturn(runId);
        when(run.getStore()).thenReturn(store);
        when(run.getPeriodMonth()).thenReturn(LocalDate.of(2026, 7, 1));
        when(run.getRevision()).thenReturn(1);
        when(run.getStatus()).thenReturn(PayrollRunStatus.PAID);
        return run;
    }

    private AppUser actor() {
        AppUser actor = mock(AppUser.class);
        when(actor.getId()).thenReturn(UUID.randomUUID());
        when(actor.getDisplayName()).thenReturn("Manager");
        return actor;
    }

    private PayrollRunDetailView payroll(UUID runId) {
        PayrollRunSummaryView summary = mock(PayrollRunSummaryView.class);
        when(summary.id()).thenReturn(runId);
        when(summary.status()).thenReturn(PayrollRunStatus.PAID);
        PayrollRunDetailView detail = mock(PayrollRunDetailView.class);
        when(detail.run()).thenReturn(summary);
        when(detail.statements()).thenReturn(List.of());
        return detail;
    }

    private MonthlyReportParts parts(UUID storeId) {
        StoreKpiResult storeKpi = mock(StoreKpiResult.class);
        when(storeKpi.formulaVersion()).thenReturn("store-kpi-v1");
        CategoryKpiResult categories = mock(CategoryKpiResult.class);
        when(categories.formulaVersion()).thenReturn("category-kpi-v2");
        AverageMetricSnapshot current = new AverageMetricSnapshot(
                new BigDecimal("300.00"),
                new BigDecimal("2.00"),
                new BigDecimal("150.00")
        );
        AverageKpiResult averages = new AverageKpiResult(
                storeId,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                "average-kpi-v1",
                new AverageMetricComparison(current, current, BigDecimal.ZERO),
                new AverageMetricComparison(current, current, BigDecimal.ZERO),
                List.of()
        );
        AttachRateResult attachRates = mock(AttachRateResult.class);
        when(attachRates.formulaVersion()).thenReturn("attach-rate-v1");
        EmployeeRatingResult rating = mock(EmployeeRatingResult.class, RETURNS_DEEP_STUBS);
        when(rating.formula().version()).thenReturn("rating-v1");
        return new MonthlyReportParts(
                storeKpi,
                categories,
                averages,
                attachRates,
                mock(StorePlanProgressView.class),
                rating,
                mock(StorePeriodQualityView.class)
        );
    }
}
