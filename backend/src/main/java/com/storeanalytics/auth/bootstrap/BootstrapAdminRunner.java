package com.storeanalytics.auth.bootstrap;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.auth.service.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final BootstrapAdminProperties properties;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    public BootstrapAdminRunner(
            BootstrapAdminProperties properties,
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy
    ) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        boolean hasEmail = StringUtils.hasText(properties.email());
        boolean hasPassword = StringUtils.hasText(properties.password());
        if (!hasEmail && !hasPassword) {
            return;
        }
        if (!hasEmail || !hasPassword) {
            throw new IllegalStateException(
                    "Both bootstrap admin email and password must be configured"
            );
        }
        if (userRepository.count() > 0) {
            LOGGER.info("Bootstrap administrator was not created because application users already exist");
            return;
        }

        passwordPolicy.validate(properties.password());
        String displayName = StringUtils.hasText(properties.displayName())
                ? properties.displayName()
                : "Administrator";
        AppUser administrator = new AppUser(
                properties.email(),
                passwordEncoder.encode(properties.password()),
                displayName,
                UserRole.ADMIN
        );
        userRepository.save(administrator);
        LOGGER.info("Bootstrap administrator created for {}", administrator.getEmail());
    }
}
