package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

class ResourceLimitsPropertiesTest {

    @Test
    void applicationConfigurationProvidesBoundedRuntimeBudgets() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load(
                "application-resource-limits",
                new ClassPathResource("application.yml")
        ).forEach(environment.getPropertySources()::addLast);

        ResourceLimitsProperties properties = Binder.get(environment)
                .bind("app.resources", Bindable.of(ResourceLimitsProperties.class))
                .orElseThrow(() -> new AssertionError(
                        "app.resources must be configured"
                ));

        assertThat(properties.http().maxThreads()).isEqualTo(64);
        assertThat(properties.http().maxQueueCapacity()).isEqualTo(128);
        assertThat(properties.http().maxRequestHeaderSize())
                .isEqualTo(DataSize.ofKilobytes(8));
        assertThat(properties.http().maxRequestBodySize())
                .isEqualTo(DataSize.ofMegabytes(2));
        assertThat(properties.database().maximumPoolSize()).isEqualTo(10);
        assertThat(properties.database().connectionTimeoutMs()).isEqualTo(5_000);
        assertThat(environment.getProperty(
                "spring.datasource.hikari.maximum-pool-size",
                Integer.class
        )).isEqualTo(10);
        assertThat(environment.getProperty(
                "server.tomcat.threads.max",
                Integer.class
        )).isEqualTo(64);
        assertThat(environment.getProperty("server.shutdown"))
                .isEqualTo("graceful");
    }

    @Test
    void rejectsUnboundedOrInternallyInconsistentHttpLimits() {
        assertThatThrownBy(() -> http(513, 8, 512))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxThreads");
        assertThatThrownBy(() -> http(64, 65, 512))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minSpareThreads");
        assertThatThrownBy(() -> http(64, 8, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConnections");
        assertThatThrownBy(() -> http(
                64,
                8,
                512,
                DataSize.ofMegabytes(17),
                DataSize.ofMegabytes(17)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRequestBodySize");
        assertThatThrownBy(() -> http(
                64,
                8,
                512,
                DataSize.ofMegabytes(3),
                DataSize.ofMegabytes(2)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSwallowSize");
    }

    @Test
    void rejectsUnsafeDatabasePoolBudgets() {
        assertThatThrownBy(() -> database(65, 2, 5_000, 3_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumPoolSize");
        assertThatThrownBy(() -> database(10, 11, 5_000, 3_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumIdle");
        assertThatThrownBy(() -> database(10, 2, 5_000, 6_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validationTimeoutMs");
    }

    private ResourceLimitsProperties.Http http(
            int maxThreads,
            int minSpareThreads,
            int maxConnections
    ) {
        return http(
                maxThreads,
                minSpareThreads,
                maxConnections,
                DataSize.ofMegabytes(2),
                DataSize.ofMegabytes(2)
        );
    }

    private ResourceLimitsProperties.Http http(
            int maxThreads,
            int minSpareThreads,
            int maxConnections,
            DataSize maxRequestBodySize,
            DataSize maxSwallowSize
    ) {
        return new ResourceLimitsProperties.Http(
                DataSize.ofKilobytes(8),
                maxRequestBodySize,
                DataSize.ofMegabytes(2),
                maxSwallowSize,
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                maxConnections,
                100,
                maxThreads,
                minSpareThreads,
                128,
                100,
                256
        );
    }

    private ResourceLimitsProperties.Database database(
            int maximumPoolSize,
            int minimumIdle,
            long connectionTimeoutMs,
            long validationTimeoutMs
    ) {
        return new ResourceLimitsProperties.Database(
                maximumPoolSize,
                minimumIdle,
                connectionTimeoutMs,
                validationTimeoutMs,
                600_000,
                1_800_000,
                120_000,
                1
        );
    }
}
