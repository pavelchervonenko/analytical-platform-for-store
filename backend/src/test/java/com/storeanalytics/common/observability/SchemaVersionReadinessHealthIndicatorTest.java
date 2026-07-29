package com.storeanalytics.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.common.database.ExpectedSchemaVersion;
import com.storeanalytics.common.database.SchemaHistoryEntry;
import com.storeanalytics.common.database.SchemaHistoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataAccessResourceFailureException;

class SchemaVersionReadinessHealthIndicatorTest {

    private final SchemaHistoryRepository repository = mock(
            SchemaHistoryRepository.class
    );
    private final ExpectedSchemaVersion expectedSchemaVersion = mock(
            ExpectedSchemaVersion.class
    );
    private final SchemaVersionReadinessHealthIndicator indicator =
            new SchemaVersionReadinessHealthIndicator(
                    repository,
                    expectedSchemaVersion
            );

    @BeforeEach
    void setUp() {
        when(expectedSchemaVersion.value()).thenReturn("12");
    }

    @Test
    void isUpWhenRuntimeSchemaMatchesImage() {
        when(repository.findLatestVersionedMigration()).thenReturn(
                Optional.of(new SchemaHistoryEntry("12", true))
        );

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("schemaVersion", "12");
    }

    @Test
    void isDownWhenSchemaIsBehindOrAheadOfImage() {
        when(repository.findLatestVersionedMigration()).thenReturn(
                Optional.of(new SchemaHistoryEntry("11", true))
        );

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("reason", "SCHEMA_VERSION_MISMATCH")
                .containsEntry("expectedVersion", "12")
                .containsEntry("actualVersion", "11");
    }

    @Test
    void isDownWhenLatestMigrationFailed() {
        when(repository.findLatestVersionedMigration()).thenReturn(
                Optional.of(new SchemaHistoryEntry("12", false))
        );

        assertThat(indicator.health().getDetails())
                .containsEntry("reason", "SCHEMA_MIGRATION_FAILED");
    }

    @Test
    void isDownWhenSchemaHasNotBeenInitialized() {
        when(repository.findLatestVersionedMigration()).thenReturn(
                Optional.empty()
        );

        assertThat(indicator.health().getDetails())
                .containsEntry("reason", "SCHEMA_NOT_INITIALIZED");
    }

    @Test
    void isDownWithoutExposingDatabaseException() {
        when(repository.findLatestVersionedMigration()).thenThrow(
                new DataAccessResourceFailureException("secret database detail")
        );

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("reason", "SCHEMA_HISTORY_UNAVAILABLE")
                .doesNotContainValue("secret database detail");
    }
}
