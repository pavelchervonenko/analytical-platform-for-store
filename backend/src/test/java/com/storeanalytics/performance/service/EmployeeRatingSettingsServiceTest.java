package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.employee.model.EmployeeStoreAssignment;
import com.storeanalytics.employee.model.EmployeeStoreAssignmentId;
import com.storeanalytics.employee.repository.EmployeeStoreAssignmentRepository;
import com.storeanalytics.performance.exception.EmployeeAssignmentNotFoundException;
import com.storeanalytics.performance.exception.EmployeeRatingConflictException;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmployeeRatingSettingsServiceTest {

    private EmployeeStoreAssignmentRepository assignmentRepository;
    private StoreRepository storeRepository;
    private EmployeeRatingSettingsService service;

    @BeforeEach
    void setUp() {
        assignmentRepository = mock(EmployeeStoreAssignmentRepository.class);
        storeRepository = mock(StoreRepository.class);
        service = new EmployeeRatingSettingsService(
                assignmentRepository,
                storeRepository,
                mock(com.storeanalytics.audit.service.AuditLogService.class)
        );
    }

    @Test
    void listsAllAssignmentsInDisplayNameOrder() {
        UUID storeId = UUID.randomUUID();
        EmployeeStoreAssignment yana = assignment(UUID.randomUUID(), "\u042f\u043d\u0430", true, 2);
        EmployeeStoreAssignment anna = assignment(UUID.randomUUID(), "\u0410\u043d\u043d\u0430", false, 1);
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(assignmentRepository.findAllByStoreId(storeId)).thenReturn(List.of(yana, anna));

        List<EmployeeRatingSettingView> result = service.findAll(storeId);

        assertThat(result).extracting(EmployeeRatingSettingView::displayName)
                .containsExactly("\u0410\u043d\u043d\u0430", "\u042f\u043d\u0430");
        assertThat(result.getFirst().participatesInRanking()).isFalse();
        assertThat(result.getLast().participatesInRanking()).isTrue();
    }

    @Test
    void updatesParticipationWhenClientVersionMatches() {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        EmployeeStoreAssignment assignment = assignment(employeeId, "\u0410\u043d\u043d\u0430", false, 4);
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(assignmentRepository.findById(new EmployeeStoreAssignmentId(employeeId, storeId)))
                .thenReturn(Optional.of(assignment));
        when(assignmentRepository.saveAndFlush(assignment)).thenReturn(assignment);

        EmployeeRatingSettingView result = service.updateParticipation(
                storeId, employeeId, true, 4, UUID.randomUUID()
        );

        verify(assignment).update(true, true);
        assertThat(result.employeeId()).isEqualTo(employeeId);
    }

    @Test
    void rejectsMissingAssignmentAndStaleClientVersion() {
        UUID storeId = UUID.randomUUID();
        UUID missingEmployeeId = UUID.randomUUID();
        UUID staleEmployeeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(assignmentRepository.findById(
                new EmployeeStoreAssignmentId(missingEmployeeId, storeId)
        )).thenReturn(Optional.empty());
        EmployeeStoreAssignment stale = assignment(staleEmployeeId, "\u0410\u043d\u043d\u0430", false, 5);
        when(assignmentRepository.findById(
                new EmployeeStoreAssignmentId(staleEmployeeId, storeId)
        )).thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> service.updateParticipation(
                storeId, missingEmployeeId, true, 0, UUID.randomUUID()
        )).isInstanceOf(EmployeeAssignmentNotFoundException.class);
        assertThatThrownBy(() -> service.updateParticipation(
                storeId, staleEmployeeId, true, 4, UUID.randomUUID()
        )).isInstanceOf(EmployeeRatingConflictException.class)
                .hasMessageContaining("another user");
        verify(assignmentRepository, never()).saveAndFlush(stale);
    }

    private EmployeeStoreAssignment assignment(
            UUID employeeId,
            String name,
            boolean participates,
            long version
    ) {
        Employee employee = mock(Employee.class);
        when(employee.getId()).thenReturn(employeeId);
        when(employee.getFullName()).thenReturn(name);
        when(employee.isActive()).thenReturn(true);
        EmployeeStoreAssignment assignment = mock(EmployeeStoreAssignment.class);
        when(assignment.getEmployee()).thenReturn(employee);
        when(assignment.isActive()).thenReturn(true);
        when(assignment.participatesInRanking()).thenReturn(participates);
        when(assignment.getVersion()).thenReturn(version);
        when(assignment.getUpdatedAt()).thenReturn(Instant.parse("2026-07-21T10:00:00Z"));
        return assignment;
    }
}
