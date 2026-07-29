package com.storeanalytics.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.common.security.SecurityAuditLogger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class BootstrapAdminRunnerTest {

    @Test
    void neverRendersBootstrapIdentityOrCredential() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties(
                "sensitive-admin@example.com",
                "sensitive-bootstrap-password",
                "Sensitive Administrator"
        );

        assertThat(properties.toString())
                .contains("emailConfigured=true")
                .contains("passwordConfigured=true")
                .doesNotContain("sensitive-admin@example.com")
                .doesNotContain("sensitive-bootstrap-password")
                .doesNotContain("Sensitive Administrator");
    }

    @Test
    void doesNothingWhenBootstrapCredentialsAreAbsent() {
        BootstrapAdminService service = mock(BootstrapAdminService.class);
        SecurityAuditLogger auditLogger = mock(SecurityAuditLogger.class);
        BootstrapAdminRunner runner = runner(
                new BootstrapAdminProperties("", "", "Administrator"),
                service,
                auditLogger
        );

        runner.run(mock(ApplicationArguments.class));

        verifyNoInteractions(service, auditLogger);
    }

    @Test
    void failsWithoutDisclosingPartiallyConfiguredCredential() {
        BootstrapAdminService service = mock(BootstrapAdminService.class);
        SecurityAuditLogger auditLogger = mock(SecurityAuditLogger.class);
        BootstrapAdminRunner runner = runner(
                new BootstrapAdminProperties(
                        "sensitive-admin@example.com", "", "Administrator"
                ),
                service,
                auditLogger
        );

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Both bootstrap admin email and password must be configured")
                .hasMessageNotContaining("sensitive-admin@example.com");
        verifyNoInteractions(service, auditLogger);
    }

    @Test
    void emitsCreatedSignalOnlyAfterServiceReturns() {
        BootstrapAdminProperties properties = properties();
        BootstrapAdminService service = mock(BootstrapAdminService.class);
        SecurityAuditLogger auditLogger = mock(SecurityAuditLogger.class);
        UUID userId = UUID.randomUUID();
        when(service.createIfDatabaseIsEmpty(properties))
                .thenReturn(BootstrapAdminOutcome.created(userId));

        runner(properties, service, auditLogger)
                .run(mock(ApplicationArguments.class));

        verify(auditLogger).bootstrapAdministratorCreated(userId);
    }

    @Test
    void warnsWhenCredentialsRemainAfterAnApplicationUserExists() {
        BootstrapAdminProperties properties = properties();
        BootstrapAdminService service = mock(BootstrapAdminService.class);
        SecurityAuditLogger auditLogger = mock(SecurityAuditLogger.class);
        when(service.createIfDatabaseIsEmpty(properties))
                .thenReturn(BootstrapAdminOutcome.usersExist());

        runner(properties, service, auditLogger)
                .run(mock(ApplicationArguments.class));

        verify(auditLogger).bootstrapAdministratorSkipped();
    }

    private BootstrapAdminRunner runner(
            BootstrapAdminProperties properties,
            BootstrapAdminService service,
            SecurityAuditLogger auditLogger
    ) {
        return new BootstrapAdminRunner(properties, service, auditLogger);
    }

    private BootstrapAdminProperties properties() {
        return new BootstrapAdminProperties(
                "admin@example.com",
                "correct horse battery staple",
                "Administrator"
        );
    }
}
