package com.storeanalytics.auth.bootstrap;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.service.PasswordPolicy;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BootstrapAdminService {

    private static final String CREATION_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(1937006964, 20260727)";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    public BootstrapAdminService(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            JdbcTemplate jdbcTemplate,
            AuditLogService auditLogService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public BootstrapAdminOutcome createIfDatabaseIsEmpty(
            BootstrapAdminProperties properties
    ) {
        BootstrapAdminProperties configuration = Objects.requireNonNull(
                properties, "properties"
        );
        configuration.validateCompleteConfiguration();
        jdbcTemplate.execute(CREATION_LOCK_SQL);
        if (userRepository.count() > 0) {
            return BootstrapAdminOutcome.usersExist();
        }

        passwordPolicy.validate(configuration.password());
        AppUser administrator = userRepository.saveAndFlush(new AppUser(
                configuration.email(),
                passwordEncoder.encode(configuration.password()),
                configuration.resolvedDisplayName(),
                UserRole.ADMIN
        ));
        auditLogService.recordSystem(
                null,
                AuditAction.BOOTSTRAP_ADMIN_CREATED,
                new AuditTarget(AuditEntityType.USER, administrator.getId()),
                null,
                null,
                Map.of(
                        "role", UserRole.ADMIN,
                        "passwordChangeRequired", true
                )
        );
        return BootstrapAdminOutcome.created(administrator.getId());
    }
}
