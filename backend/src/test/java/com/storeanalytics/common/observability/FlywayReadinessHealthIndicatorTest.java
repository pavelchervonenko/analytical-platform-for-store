package com.storeanalytics.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class FlywayReadinessHealthIndicatorTest {

    @Test
    void isUpWhenValidationPassesAndNoMigrationIsPending() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.pending()).thenReturn(new MigrationInfo[0]);

        assertThat(new FlywayReadinessHealthIndicator(flyway)
                .health()
                .getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void isDownWhenMigrationIsPending() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.pending()).thenReturn(new MigrationInfo[]{mock(MigrationInfo.class)});

        assertThat(new FlywayReadinessHealthIndicator(flyway)
                .health()
                .getStatus()).isEqualTo(Status.DOWN);
    }
}
