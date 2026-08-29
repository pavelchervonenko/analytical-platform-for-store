package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WeeklySnapshotPlanningStoreIntegrationTest {

    private static final Instant COVERAGE_START = Instant.parse(
            "2026-08-09T22:00:00Z"
    );
    private static final Instant COVERAGE_END = Instant.parse(
            "2026-08-23T22:00:00Z"
    );
    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklySnapshotPlanningStore planningStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void acceptsBackfillFollowedByContiguousIncrementalJobs() {
        TestStore store = createStore();
        Instant firstEnd = Instant.parse("2026-08-15T22:00:00Z");
        Instant secondEnd = Instant.parse("2026-08-18T22:00:00Z");
        Instant thirdEnd = Instant.parse("2026-08-21T22:00:00Z");
        addSuccessfulSync(
                store.connectionId(), "BACKFILL", COVERAGE_START, firstEnd,
                Instant.parse("2026-08-16T02:00:00Z")
        );
        addSuccessfulSync(
                store.connectionId(), "INCREMENTAL", firstEnd, secondEnd,
                Instant.parse("2026-08-19T02:00:00Z")
        );
        addSuccessfulSync(
                store.connectionId(), "INCREMENTAL", secondEnd, thirdEnd,
                Instant.parse("2026-08-22T02:00:00Z")
        );
        UUID newestId = addSuccessfulSync(
                store.connectionId(), "INCREMENTAL", thirdEnd, COVERAGE_END,
                Instant.parse("2026-08-24T02:00:00Z")
        );

        assertThat(planningStore.newestSuitableSource(
                store.storeId(), COVERAGE_START, COVERAGE_END, NOW
        )).hasValueSatisfying(source -> {
            assertThat(source.syncJobId()).isEqualTo(newestId);
            assertThat(source.completedAt())
                    .isEqualTo(Instant.parse("2026-08-24T02:00:00Z"));
        });
    }

    @Test
    void rejectsWhenOnlyTheLatestIncrementalWindowExists() {
        TestStore store = createStore();
        addSuccessfulSync(
                store.connectionId(),
                "INCREMENTAL",
                Instant.parse("2026-08-20T22:00:00Z"),
                COVERAGE_END,
                Instant.parse("2026-08-24T02:00:00Z")
        );

        assertThat(planningStore.newestSuitableSource(
                store.storeId(), COVERAGE_START, COVERAGE_END, NOW
        )).isEmpty();
    }

    @Test
    void rejectsAChainWithAGapBetweenSuccessfulJobs() {
        TestStore store = createStore();
        addSuccessfulSync(
                store.connectionId(),
                "BACKFILL",
                COVERAGE_START,
                Instant.parse("2026-08-16T22:00:00Z"),
                Instant.parse("2026-08-17T02:00:00Z")
        );
        addSuccessfulSync(
                store.connectionId(),
                "INCREMENTAL",
                Instant.parse("2026-08-17T22:00:00Z"),
                COVERAGE_END,
                Instant.parse("2026-08-24T02:00:00Z")
        );

        assertThat(planningStore.newestSuitableSource(
                store.storeId(), COVERAGE_START, COVERAGE_END, NOW
        )).isEmpty();
    }

    private TestStore createStore() {
        UUID connectionId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO integration_connections (
                    id, connection_key, source_system, display_name
                ) VALUES (?, ?, 'LIVESKLAD', 'Coverage test connection')
                """,
                connectionId,
                "weekly-coverage-" + connectionId
        );
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name, timezone
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Coverage test store', ?)
                """,
                storeId,
                connectionId,
                "weekly-coverage-" + storeId,
                "Europe/Kaliningrad"
        );
        return new TestStore(storeId, connectionId);
    }

    private UUID addSuccessfulSync(
            UUID connectionId,
            String jobType,
            Instant periodStart,
            Instant periodEnd,
            Instant completedAt
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_jobs (
                    id, connection_id, job_type, status, phase,
                    period_start, period_end, cursor_start,
                    current_window_end, window_size_minutes, max_attempts,
                    next_attempt_at, started_at, finished_at
                ) VALUES (
                    ?, ?, ?, 'SUCCESS', 'RETURNS', ?, ?, ?, ?, 1440, 5,
                    ?, ?, ?
                )
                """,
                id,
                connectionId,
                jobType,
                Timestamp.from(periodStart),
                Timestamp.from(periodEnd),
                Timestamp.from(periodEnd),
                Timestamp.from(periodEnd),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt.minusSeconds(60)),
                Timestamp.from(completedAt)
        );
        return id;
    }

    private record TestStore(UUID storeId, UUID connectionId) {
    }
}
