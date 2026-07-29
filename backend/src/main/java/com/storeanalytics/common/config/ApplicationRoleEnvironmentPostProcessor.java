package com.storeanalytics.common.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public final class ApplicationRoleEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "applicationRoleMode";

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application
    ) {
        ApplicationRole role = ApplicationRoleResolver.resolve(environment);
        Map<String, Object> enforcedProperties = new LinkedHashMap<>();
        enforcedProperties.put("app.runtime.role", role.name());
        switch (role) {
            case API, WORKER -> enforcedProperties.put(
                    "spring.flyway.enabled",
                    "false"
            );
            case MIGRATION -> {
                enforcedProperties.put("spring.flyway.enabled", "true");
                enforcedProperties.put("spring.main.web-application-type", "none");
                enforcedProperties.put("spring.jpa.hibernate.ddl-auto", "none");


                enforcedProperties.put(
                        "management.endpoints.access.max-permitted",
                        "none"
                );
            }
            case COMBINED -> {
                // Combined mode retains the local-development Flyway configuration.
            }
            default -> throw new IllegalStateException(
                    "Unsupported application role: " + role
            );
        }
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, enforcedProperties)
        );
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
