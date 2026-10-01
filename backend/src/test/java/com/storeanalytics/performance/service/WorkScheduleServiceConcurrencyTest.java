package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.employee.repository.EmployeeStoreAssignmentRepository;
import com.storeanalytics.performance.model.WorkScheduleDayRevision;
import com.storeanalytics.performance.repository.EmployeeWorkShiftRepository;
import com.storeanalytics.performance.repository.WorkScheduleDayRevisionRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WorkScheduleServiceConcurrencyTest {

    @Test
    void locksStoreForReadBeforeLoadingDayRevisionAndShifts() {
        EmployeeWorkShiftRepository shiftRepository = mock(
                EmployeeWorkShiftRepository.class
        );
        WorkScheduleDayRevisionRepository revisionRepository = mock(
                WorkScheduleDayRevisionRepository.class
        );
        StoreRepository storeRepository = mock(StoreRepository.class);
        Store store = mock(Store.class);
        UUID storeId = UUID.randomUUID();
        LocalDate workDate = LocalDate.of(2026, 7, 1);

        when(store.getId()).thenReturn(storeId);
        when(storeRepository.findByIdForRead(storeId)).thenReturn(Optional.of(store));
        when(revisionRepository.findByStoreIdAndWorkDate(storeId, workDate))
                .thenReturn(Optional.empty());
        when(shiftRepository.findAllByStoreIdAndWorkDate(storeId, workDate))
                .thenReturn(List.of());

        WorkScheduleService service = new WorkScheduleService(
                shiftRepository,
                revisionRepository,
                mock(EmployeeStoreAssignmentRepository.class),
                storeRepository,
                mock(AppUserRepository.class),
                mock(com.storeanalytics.audit.service.AuditLogService.class)
        );

        WorkScheduleDayView day = service.getDay(storeId, workDate);

        InOrder reads = inOrder(storeRepository, revisionRepository, shiftRepository);
        reads.verify(storeRepository).findByIdForRead(storeId);
        reads.verify(revisionRepository).findByStoreIdAndWorkDate(storeId, workDate);
        reads.verify(shiftRepository).findAllByStoreIdAndWorkDate(storeId, workDate);
        assertThat(day.revision()).isZero();
        assertThat(day.shifts()).isEmpty();
    }

    @Test
    void locksStoreBeforeCreatingFirstDayRevision() {
        EmployeeWorkShiftRepository shiftRepository = mock(
                EmployeeWorkShiftRepository.class
        );
        WorkScheduleDayRevisionRepository revisionRepository = mock(
                WorkScheduleDayRevisionRepository.class
        );
        EmployeeStoreAssignmentRepository assignmentRepository =
                mock(EmployeeStoreAssignmentRepository.class);
        StoreRepository storeRepository = mock(StoreRepository.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        Store store = mock(Store.class);
        AppUser actor = mock(AppUser.class);
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        LocalDate workDate = LocalDate.of(2026, 7, 1);

        when(store.getId()).thenReturn(storeId);
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(assignmentRepository.findAllByStoreId(storeId)).thenReturn(List.of());
        when(shiftRepository.findAllByStoreIdAndWorkDate(storeId, workDate))
                .thenReturn(List.of());
        when(revisionRepository.findByStoreIdAndWorkDate(storeId, workDate))
                .thenReturn(Optional.empty());
        when(revisionRepository.saveAndFlush(any(WorkScheduleDayRevision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkScheduleService service = new WorkScheduleService(
                shiftRepository,
                revisionRepository,
                assignmentRepository,
                storeRepository,
                userRepository,
                mock(com.storeanalytics.audit.service.AuditLogService.class)
        );

        service.replaceDay(
                storeId,
                workDate,
                List.of(),
                WorkScheduleService.etag(storeId, workDate, 0),
                actorId
        );

        verify(storeRepository).findByIdForUpdate(storeId);
        verify(revisionRepository).saveAndFlush(any(WorkScheduleDayRevision.class));
    }
}
