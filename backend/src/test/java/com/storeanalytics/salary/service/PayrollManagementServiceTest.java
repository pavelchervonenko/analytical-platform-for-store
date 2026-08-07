package com.storeanalytics.salary.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.idempotency.IdempotencyService;
import com.storeanalytics.employee.repository.EmployeeStoreAssignmentRepository;
import com.storeanalytics.report.service.MonthlyReportFinalizationService;
import com.storeanalytics.salary.exception.PayrollStateConflictException;
import com.storeanalytics.salary.model.PayrollAdjustmentType;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollRunStatus;
import com.storeanalytics.salary.repository.PayrollAdjustmentRepository;
import com.storeanalytics.salary.repository.PayrollDailyAllocationRepository;
import com.storeanalytics.salary.repository.PayrollDailyPoolRepository;
import com.storeanalytics.salary.repository.PayrollEventRepository;
import com.storeanalytics.salary.repository.PayrollRunRepository;
import com.storeanalytics.salary.repository.PayrollStatementRepository;
import com.storeanalytics.store.model.Store;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayrollManagementServiceTest {

    private PayrollRunRepository runRepository;
    private PayrollAdjustmentRepository adjustmentRepository;
    private EmployeeStoreAssignmentRepository assignmentRepository;
    private IdempotencyService idempotencyService;
    private PayrollManagementService service;

    @BeforeEach
    void setUp() {
        runRepository = mock(PayrollRunRepository.class);
        adjustmentRepository = mock(PayrollAdjustmentRepository.class);
        assignmentRepository = mock(EmployeeStoreAssignmentRepository.class);
        idempotencyService = mock(IdempotencyService.class);
        PayrollManagementRepositories repositories = new PayrollManagementRepositories(
                runRepository,
                mock(PayrollDailyPoolRepository.class),
                mock(PayrollDailyAllocationRepository.class),
                adjustmentRepository,
                mock(PayrollStatementRepository.class),
                mock(PayrollEventRepository.class)
        );
        PayrollManagementSupport support = new PayrollManagementSupport(
                Clock.fixed(Instant.parse("2026-08-01T09:00:00Z"), ZoneOffset.UTC),
                mock(AuditLogService.class),
                mock(MonthlyReportFinalizationService.class),
                idempotencyService
        );
        service = new PayrollManagementService(
                mock(PayrollCalculationService.class),
                mock(PayrollFreshnessService.class),
                repositories,
                assignmentRepository,
                mock(AppUserRepository.class),
                mock(PayrollSnapshotStore.class),
                support
        );
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotencyService)
                .execute(
                        any(),
                        anyString(),
                        any(),
                        eq(PayrollRunDetailView.class),
                        any()
                );
    }

    @Test
    void rejectsAdjustmentForApprovedRunAsTypedStateConflict() {
        UUID storeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Store store = mock(Store.class);
        PayrollRun run = mock(PayrollRun.class);
        when(store.getId()).thenReturn(storeId);
        when(run.getId()).thenReturn(runId);
        when(run.getStore()).thenReturn(store);
        when(run.getPeriodMonth()).thenReturn(LocalDate.of(2026, 6, 1));
        when(run.getStatus()).thenReturn(PayrollRunStatus.APPROVED);
        when(run.getVersion()).thenReturn(4L);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runRepository.findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
                storeId,
                LocalDate.of(2026, 6, 1)
        )).thenReturn(Optional.of(run));
        AddPayrollAdjustmentCommand command = new AddPayrollAdjustmentCommand(
                storeId,
                runId,
                UUID.randomUUID(),
                PayrollAdjustmentType.PENALTY,
                new BigDecimal("100.00"),
                "Late adjustment",
                4,
                UUID.randomUUID()
        );

        assertThatThrownBy(() -> service.addAdjustment(command, "payroll-state-test"))
                .isInstanceOf(PayrollStateConflictException.class);
        verifyNoInteractions(assignmentRepository, adjustmentRepository);
    }
}
