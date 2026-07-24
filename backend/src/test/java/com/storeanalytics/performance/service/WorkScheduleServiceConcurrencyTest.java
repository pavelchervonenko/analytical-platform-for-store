package com.storeanalytics.performance.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.employee.repository.EmployeeStoreAssignmentRepository;
import com.storeanalytics.performance.repository.EmployeeWorkShiftRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkScheduleServiceConcurrencyTest {

    @Test
    void locksStoreBeforeReplacingDay() {
        EmployeeWorkShiftRepository shiftRepository = mock(EmployeeWorkShiftRepository.class);
        EmployeeStoreAssignmentRepository assignmentRepository =
                mock(EmployeeStoreAssignmentRepository.class);
        StoreRepository storeRepository = mock(StoreRepository.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        Store store = mock(Store.class);
        AppUser actor = mock(AppUser.class);
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        when(store.getId()).thenReturn(storeId);
        when(storeRepository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(assignmentRepository.findAllByStoreId(storeId)).thenReturn(List.of());
        when(shiftRepository.findAllByStoreIdAndWorkDate(storeId, LocalDate.of(2026, 7, 1)))
                .thenReturn(List.of());

        WorkScheduleService service = new WorkScheduleService(
                shiftRepository,
                assignmentRepository,
                storeRepository,
                userRepository,
                mock(com.storeanalytics.audit.service.AuditLogService.class)
        );

        service.replaceDay(
                storeId,
                LocalDate.of(2026, 7, 1),
                List.of(),
                actorId
        );

        verify(storeRepository).findByIdForUpdate(storeId);
    }
}
