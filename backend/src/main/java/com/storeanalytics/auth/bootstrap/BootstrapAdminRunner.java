package com.storeanalytics.auth.bootstrap;

import com.storeanalytics.common.security.SecurityAuditLogger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private final BootstrapAdminProperties properties;
    private final BootstrapAdminService bootstrapAdminService;
    private final SecurityAuditLogger securityAuditLogger;

    public BootstrapAdminRunner(
            BootstrapAdminProperties properties,
            BootstrapAdminService bootstrapAdminService,
            SecurityAuditLogger securityAuditLogger
    ) {
        this.properties = properties;
        this.bootstrapAdminService = bootstrapAdminService;
        this.securityAuditLogger = securityAuditLogger;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!properties.configured()) {
            return;
        }
        properties.validateCompleteConfiguration();
        BootstrapAdminOutcome outcome =
                bootstrapAdminService.createIfDatabaseIsEmpty(properties);
        switch (outcome.status()) {
            case CREATED -> securityAuditLogger.bootstrapAdministratorCreated(
                    outcome.userId()
            );
            case USERS_EXIST ->
                    securityAuditLogger.bootstrapAdministratorSkipped();
            default -> throw new IllegalStateException(
                    "Unknown bootstrap administrator outcome"
            );
        }
    }
}
