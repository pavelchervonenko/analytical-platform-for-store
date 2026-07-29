package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.employee.model.EmployeeStoreAssignment;
import com.storeanalytics.employee.repository.EmployeeStoreAssignmentRepository;
import com.storeanalytics.performance.model.EmployeeWorkShift;
import com.storeanalytics.performance.model.WorkScheduleDayRevision;
import com.storeanalytics.performance.repository.EmployeeWorkShiftRepository;
import com.storeanalytics.performance.repository.WorkScheduleDayRevisionRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkScheduleServiceTest {

    private EmployeeWorkShiftRepository shiftRepository;
    private WorkScheduleDayRevisionRepository revisionRepository;
    private EmployeeStoreAssignmentRepository assignmentRepository;
    private StoreRepository storeRepository;
    private AppUserRepository userRepository;
    private WorkScheduleService service;

    @BeforeEach
    void setUp() {
        shiftRepository = mock(EmployeeWorkShiftRepository.class);
        revisionRepository = mock(WorkScheduleDayRevisionRepository.class);
        assignmentRepository = mock(EmployeeStoreAssignmentRepository.class);
        storeRepository = mock(StoreRepository.class);
        userRepository = mock(AppUserRepository.class);
        service = new WorkScheduleService(
                shiftRepository,
                revisionRepository,
                assignmentRepository,
                storeRepository,
                userRepository,
                mock(com.storeanalytics.audit.service.AuditLogService.class)
        );
    }

    @Test
    void rejectsScheduleRangeLongerThanOneMonthBeforeQueryingRows() {
        UUID storeId = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 1, 1);
        when(storeRepository.existsById(storeId)).thenReturn(true);

        assertThatThrownBy(() -> service.find(
                storeId,
                start,
                start.plusDays(WorkScheduleService.MAXIMUM_RANGE_DAYS)
        ))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("31 inclusive days");

        verifyNoInteractions(shiftRepository);
    }

    @Test
    void rejectsScheduleResponseAboveItemBudget() {
        UUID storeId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 1, 1);
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(shiftRepository
                .findAllByStoreIdAndWorkDateBetweenOrderByWorkDateAscEmployeeFullNameAsc(
                        storeId,
                        date,
                        date,
                        org.springframework.data.domain.PageRequest.of(
                                0,
                                WorkScheduleService.MAXIMUM_RESULT_ITEMS + 1
                        )
                )).thenReturn(Collections.nCopies(
                        WorkScheduleService.MAXIMUM_RESULT_ITEMS + 1,
                        mock(EmployeeWorkShift.class)
                ));

        assertThatThrownBy(() -> service.find(storeId, date, date))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("exceeds 10000 items");
    }

    @Test
    void rejectsOversizedDayBeforeLoadingAssignments() {
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 1, 1);
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(revisionRepository.findByStoreIdAndWorkDate(storeId, date))
                .thenReturn(Optional.empty());
        List<WorkShiftInput> shifts = IntStream.rangeClosed(
                0,
                WorkScheduleService.MAXIMUM_SHIFTS_PER_DAY
        ).mapToObj(index -> new WorkShiftInput(
                UUID.randomUUID(),
                BigDecimal.ONE
        )).toList();

        assertThatThrownBy(() -> service.replaceDay(
                storeId,
                date,
                shifts,
                WorkScheduleService.etag(storeId, date, 0),
                actorId
        ))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("no more than 500");

        verifyNoInteractions(assignmentRepository, userRepository);
    }

    @Test
    void updatesActualHoursAndAdvancesDayRevision() {
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
        when(employee.getFullName()).thenReturn("Анна");
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
        when(revisionRepository.findByStoreIdAndWorkDate(storeId, workDate))
                .thenReturn(Optional.empty());
        when(revisionRepository.saveAndFlush(any(WorkScheduleDayRevision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkScheduleDayView result = service.replaceDay(
                storeId,
                workDate,
                List.of(new WorkShiftInput(employeeId, hours)),
                WorkScheduleService.etag(storeId, workDate, 0),
                actorId
        );

        verify(existing).setWorkedHours(hours, actor);
        verify(shiftRepository).flush();
        assertThat(result.revision()).isOne();
    }

    @Test
    void rejectsStaleDayEtagBeforeChangingRoster() {
        UUID storeId = UUID.randomUUID();
        LocalDate workDate = LocalDate.of(2026, 7, 21);
        Store store = mock(Store.class);
        WorkScheduleDayRevision revision = mock(WorkScheduleDayRevision.class);
        when(store.getId()).thenReturn(storeId);
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(revision.getRevision()).thenReturn(3L);
        when(revisionRepository.findByStoreIdAndWorkDate(storeId, workDate))
                .thenReturn(Optional.of(revision));

        assertThatThrownBy(() -> service.replaceDay(
                storeId,
                workDate,
                List.of(),
                WorkScheduleService.etag(storeId, workDate, 2),
                UUID.randomUUID()
        )).isInstanceOf(PreconditionFailedException.class);

        verifyNoInteractions(assignmentRepository);
    }

    @Test
    void rejectsDuplicateEmployeeBeforeChangingRoster() {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        LocalDate workDate = LocalDate.of(2026, 7, 21);
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(revisionRepository.findByStoreIdAndWorkDate(storeId, workDate))
                .thenReturn(Optional.empty());
        WorkShiftInput shift = new WorkShiftInput(employeeId, new BigDecimal("8.00"));

        assertThatThrownBy(() -> service.replaceDay(
                storeId,
                workDate,
                List.of(shift, shift),
                WorkScheduleService.etag(storeId, workDate, 0),
                UUID.randomUUID()
        ))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("twice");
    }
}
