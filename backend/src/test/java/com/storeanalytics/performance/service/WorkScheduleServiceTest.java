package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.employee.model.EmployeeStoreAssignment;
import com.storeanalytics.employee.repository.EmployeeStoreAssignmentRepository;
import com.storeanalytics.performance.model.EmployeeWorkShift;
import com.storeanalytics.performance.repository.EmployeeWorkShiftRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkScheduleServiceTest {

    private EmployeeWorkShiftRepository shiftRepository;
    private EmployeeStoreAssignmentRepository assignmentRepository;
    private StoreRepository storeRepository;
    private AppUserRepository userRepository;
    private WorkScheduleService service;

    @BeforeEach
    void setUp() {
        shiftRepository = mock(EmployeeWorkShiftRepository.class);
        assignmentRepository = mock(EmployeeStoreAssignmentRepository.class);
        storeRepository = mock(StoreRepository.class);
        userRepository = mock(AppUserRepository.class);
        service = new WorkScheduleService(
                shiftRepository, assignmentRepository, storeRepository, userRepository,
                mock(com.storeanalytics.audit.service.AuditLogService.class)
        );
    }

    @Test
    void updatesActualHoursOfExistingShift() {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        LocalDate workDate = LocalDate.of(2026, 7, 21);
        BigDecimal hours = new BigDecimal("6.50");
        Store store = mock(Store.class);
        Employee employee = mock(Employee.class);
        EmployeeStoreAssignment assignment = mock(EmployeeStoreAssignment.class);
        EmployeeWorkShift existing = mock(EmployeeWorkShift.class);
        AppUser actor = mock(AppUser.class);

        when(store.getId()).thenReturn(storeId);
        when(employee.getId()).thenReturn(employeeId);
        when(employee.getFullName()).thenReturn("\u0410\u043d\u043d\u0430");
        when(employee.isActive()).thenReturn(true);
        when(assignment.getEmployee()).thenReturn(employee);
        when(assignment.isActive()).thenReturn(true);
        when(existing.getEmployee()).thenReturn(employee);
        when(existing.isActive()).thenReturn(true);
        when(existing.getWorkedHours()).thenReturn(hours);
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(assignmentRepository.findAllByStoreId(storeId)).thenReturn(List.of(assignment));
        when(shiftRepository.findAllByStoreIdAndWorkDate(storeId, workDate))
                .thenReturn(List.of(existing));

        service.replaceDay(
                storeId,
                workDate,
                List.of(new WorkShiftInput(employeeId, hours)),
                actorId
        );

        verify(existing).setWorkedHours(hours, actor);
        verify(shiftRepository).flush();
    }

    @Test
    void rejectsDuplicateEmployeeBeforeChangingRoster() {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        WorkShiftInput shift = new WorkShiftInput(employeeId, new BigDecimal("8.00"));

        assertThatThrownBy(() -> service.replaceDay(
                storeId,
                LocalDate.of(2026, 7, 21),
                List.of(shift, shift),
                UUID.randomUUID()
        ))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("twice");
    }
}
