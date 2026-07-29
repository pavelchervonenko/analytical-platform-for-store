package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class ApplicationRoleEnvironmentPostProcessorTest {

    private final ApplicationRoleEnvironmentPostProcessor processor =
            new ApplicationRoleEnvironmentPostProcessor();
    private final SpringApplication application = new SpringApplication(
            Object.class
    );

    @Test
    void apiRoleForcesRuntimeFlywayOff() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.runtime.role", "API")
                .withProperty("spring.flyway.enabled", "true");

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty("spring.flyway.enabled"))
                .isEqualTo("false");
        assertThat(environment.getProperty("app.runtime.role"))
                .isEqualTo("API");
    }

    @Test
    void workerRoleForcesRuntimeFlywayOff() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.runtime.role", "worker")
                .withProperty("spring.flyway.enabled", "true");

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty("spring.flyway.enabled"))
                .isEqualTo("false");
        assertThat(environment.getProperty("app.runtime.role"))
                .isEqualTo("WORKER");
    }

    @Test
    void migrationRoleForcesFlywayAndNonWebMode() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.runtime.role", "MIGRATION")
                .withProperty("spring.flyway.enabled", "false");

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty("spring.flyway.enabled"))
                .isEqualTo("true");
        assertThat(environment.getProperty("spring.main.web-application-type"))
                .isEqualTo("none");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                .isEqualTo("none");
    }

    @Test
    void combinedRoleRetainsConfiguredFlywayBehavior() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.runtime.role", "COMBINED")
                .withProperty("spring.flyway.enabled", "true");

        processor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty("spring.flyway.enabled"))
                .isEqualTo("true");
    }

    @Test
    void unknownRoleFailsBeforeAutoConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.runtime.role", "UNKNOWN_ROLE");

        assertThatThrownBy(() -> processor.postProcessEnvironment(
                environment,
                application
        )).isInstanceOf(RuntimeException.class);
    }
}
