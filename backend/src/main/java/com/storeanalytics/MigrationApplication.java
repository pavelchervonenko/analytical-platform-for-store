package com.storeanalytics;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ApplicationRoleResolver;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.common.config.MigrationSafetyProperties;
import com.storeanalytics.common.database.ExpectedSchemaVersion;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

final class MigrationApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            MigrationApplication.class
    );

    private MigrationApplication() {
    }

    static ConfigurableApplicationContext run(String[] arguments) {
        ApplicationRole role = ApplicationRoleResolver.resolve(arguments);
        if (role != ApplicationRole.MIGRATION) {
            throw new IllegalArgumentException(
                    "Migration application requires app.runtime.role=MIGRATION"
            );
        }
        SpringApplication application = new SpringApplication(
                MigrationConfiguration.class
        );
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run(arguments);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnApplicationRole(ApplicationRole.MIGRATION)
    @EnableConfigurationProperties(MigrationSafetyProperties.class)
    @EnableAutoConfiguration(exclude = {
            HibernateJpaAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class
    })
    static class MigrationConfiguration {

        @Bean
        FlywayConfigurationCustomizer migrationSafetyCustomizer(
                MigrationSafetyProperties properties
        ) {
            return configuration -> configuration
                    .initSql(properties.connectionInitSql())
                    .lockRetryCount(properties.lockRetryCount());
        }

        @Bean
        ExpectedSchemaVersion expectedSchemaVersion() {
            return new ExpectedSchemaVersion();
        }

        @Bean
        ApplicationRunner migrationCompletionVerifier(
                Flyway flyway,
                ExpectedSchemaVersion expectedSchemaVersion
        ) {
            return arguments -> {
                flyway.validate();
                MigrationInfo current = flyway.info().current();
                if (current == null || !expectedSchemaVersion.migrationVersion()
                        .equals(current.getVersion())) {
                    String actual = current == null
                            ? "none"
                            : current.getVersion().getVersion();
                    throw new IllegalStateException(
                            "Migration finished at schema version " + actual
                                    + ", expected "
                                    + expectedSchemaVersion.value()
                    );
                }
                LOGGER.info(
                        "Database migration completed at schema version {}",
                        expectedSchemaVersion.value()
                );
            };
        }
    }
}
