package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MigrationSafetyPropertiesTest {

    @Test
    void producesAValueOnlyFlywayConnectionInitializer() {
        MigrationSafetyProperties properties = new MigrationSafetyProperties(
                Duration.ofSeconds(5),
                Duration.ofMinutes(10),
                10
        );

        assertThat(properties.connectionInitSql()).isEqualTo(
                "SELECT set_config('lock_timeout', '5000ms', false), "
                        + "set_config('statement_timeout', '600000ms', false)"
        );
    }

    @Test
    void rejectsUnboundedOrInternallyInconsistentSettings() {
        assertThatThrownBy(() -> new MigrationSafetyProperties(
                Duration.ofMinutes(2),
                Duration.ofMinutes(10),
                10
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MigrationSafetyProperties(
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                10
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MigrationSafetyProperties(
                Duration.ofSeconds(5),
                Duration.ofMinutes(10),
                101
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
