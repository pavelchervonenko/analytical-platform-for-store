package com.storeanalytics.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.service.PasswordPolicy;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class BootstrapAdminServiceTest {

    @Test
    void serializesCreationAndPersistsSecurityAudit() {
        AppUserRepository repository = mock(AppUserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        AppUser saved = mock(AppUser.class);
        UUID userId = UUID.randomUUID();
        when(repository.count()).thenReturn(0L);
        when(encoder.encode("correct horse battery staple"))
                .thenReturn("{bcrypt}encoded");
        when(repository.saveAndFlush(any(AppUser.class))).thenReturn(saved);
        when(saved.getId()).thenReturn(userId);
        BootstrapAdminService service = service(
                repository, encoder, jdbcTemplate, auditLogService
        );

        BootstrapAdminOutcome outcome = service.createIfDatabaseIsEmpty(
                properties()
        );

        assertThat(outcome).isEqualTo(BootstrapAdminOutcome.created(userId));
        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("admin@example.com");
        assertThat(userCaptor.getValue().getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(userCaptor.getValue().isPasswordChangeRequired()).isTrue();
        verify(auditLogService).recordSystem(
                null,
                AuditAction.BOOTSTRAP_ADMIN_CREATED,
                new AuditTarget(AuditEntityType.USER, userId),
                null,
                null,
                Map.of("role", UserRole.ADMIN, "passwordChangeRequired", true)
        );
        InOrder order = inOrder(jdbcTemplate, repository);
        order.verify(jdbcTemplate).execute(any(String.class));
        order.verify(repository).count();
        order.verify(repository).saveAndFlush(any(AppUser.class));
    }

    @Test
    void neverCreatesAnotherAdministratorWhenAnyUserExists() {
        AppUserRepository repository = mock(AppUserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(repository.count()).thenReturn(1L);
        BootstrapAdminService service = service(
                repository, encoder, jdbcTemplate, auditLogService
        );

        BootstrapAdminOutcome outcome = service.createIfDatabaseIsEmpty(
                properties()
        );

        assertThat(outcome).isEqualTo(BootstrapAdminOutcome.usersExist());
        verify(jdbcTemplate).execute(any(String.class));
        verify(repository).count();
        verifyNoInteractions(encoder, auditLogService);
    }

    private BootstrapAdminService service(
            AppUserRepository repository,
            PasswordEncoder encoder,
            JdbcTemplate jdbcTemplate,
            AuditLogService auditLogService
    ) {
        return new BootstrapAdminService(
                repository,
                encoder,
                new PasswordPolicy(password -> false),
                jdbcTemplate,
                auditLogService
        );
    }

    private BootstrapAdminProperties properties() {
        return new BootstrapAdminProperties(
                "admin@example.com",
                "correct horse battery staple",
                "Administrator"
        );
    }
}
