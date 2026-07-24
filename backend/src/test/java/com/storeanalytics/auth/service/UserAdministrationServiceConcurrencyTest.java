package com.storeanalytics.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.exception.UserAdministrationConflictException;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.repository.UserStoreAccessRepository;
import com.storeanalytics.common.security.SecurityAuditLogger;
import com.storeanalytics.store.repository.StoreRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserAdministrationServiceConcurrencyTest {

    @Test
    void locksActiveAdministratorsBeforeRemovingOne() {
        AppUserRepository userRepository = mock(AppUserRepository.class);
        AppUser administrator = mock(AppUser.class);
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(administrator));
        when(userRepository.findAllActiveByRoleForUpdate(UserRole.ADMIN))
                .thenReturn(List.of(administrator));
        when(administrator.getRole()).thenReturn(UserRole.ADMIN);
        when(administrator.isActive()).thenReturn(true);

        UserAdministrationService service = new UserAdministrationService(
                userRepository,
                mock(UserStoreAccessRepository.class),
                mock(StoreRepository.class),
                mock(PasswordEncoder.class),
                new PasswordPolicy(),
                mock(SecurityAuditLogger.class),
                mock(com.storeanalytics.audit.service.AuditLogService.class)
        );

        UpdateUserCommand command = new UpdateUserCommand(
                "Administrator",
                UserRole.MANAGER,
                true
        );

        assertThatThrownBy(() -> service.update(userId, command, UUID.randomUUID()))
                .isInstanceOf(UserAdministrationConflictException.class);
        verify(userRepository).findAllActiveByRoleForUpdate(UserRole.ADMIN);
    }
}
