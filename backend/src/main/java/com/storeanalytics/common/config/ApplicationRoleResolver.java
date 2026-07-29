package com.storeanalytics.common.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

public final class ApplicationRoleResolver {

    private static final String ROLE_PROPERTY = "app.runtime.role";

    private ApplicationRoleResolver() {
    }

    public static ApplicationRole resolve(String[] arguments) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        if (arguments.length > 0) {
            environment.getPropertySources().addFirst(
                    new SimpleCommandLinePropertySource(arguments)
            );
        }
        return resolve(environment);
    }

    public static ApplicationRole resolve(Environment environment) {
        return Binder.get(environment)
                .bind(ROLE_PROPERTY, ApplicationRole.class)
                .orElse(ApplicationRole.COMBINED);
    }
}
