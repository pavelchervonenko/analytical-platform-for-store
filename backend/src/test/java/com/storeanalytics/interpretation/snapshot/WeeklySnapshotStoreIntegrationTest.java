package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Comparison;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EmployeeFacts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Scope;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WeeklySnapshotStoreIntegrationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 20);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 26);
    private static final Instant FIRST_SYNC_AT = Instant.parse("2026-07-27T02:00:00Z");
    private static final Instant SECOND_SYNC_AT = Instant.parse("2026-07-27T03:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklySnapshotStore snapshotStore;

    @Autowired
    private WeeklySnapshotPayloadCodec codec;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void persistsInitialSkipsUnchangedAndCreatesImmutableRevision() {
        TestGraph graph = createGraph();
        WeeklySnapshotDraft initialDraft = draft(graph, "100000.00");

        WeeklySnapshotWriteResult initial = snapshotStore.persist(command(
                initialDraft,
                graph.firstSyncJobId(),
                FIRST_SYNC_AT,
                WeeklySnapshotRevisionReason.AUTO_REVISION,
                null
        ));
        WeeklySnapshotWriteResult unchanged = snapshotStore.persist(command(
                initialDraft,
                graph.secondSyncJobId(),
                SECOND_SYNC_AT,
                WeeklySnapshotRevisionReason.AUTO_REVISION,
                null
        ));
        WeeklySnapshotWriteResult revision = snapshotStore.persist(command(
                draft(graph, "110000.00"),
                graph.secondSyncJobId(),
                SECOND_SYNC_AT,
                WeeklySnapshotRevisionReason.AUTO_REVISION,
                "Late sales synchronization"
        ));

        assertThat(initial.outcome()).isEqualTo(WeeklySnapshotWriteOutcome.CREATED);
        assertThat(initial.snapshot().revision()).isEqualTo(1);
        assertThat(initial.snapshot().revisionReasonCode()).isEqualTo("INITIAL");
        assertThat(initial.snapshot().supersedesSnapshotId()).isNull();
        assertThat(unchanged.outcome()).isEqualTo(WeeklySnapshotWriteOutcome.UNCHANGED);
        assertThat(unchanged.snapshot().id()).isEqualTo(initial.snapshot().id());
        assertThat(revision.outcome()).isEqualTo(WeeklySnapshotWriteOutcome.CREATED);
        assertThat(revision.snapshot().revision()).isEqualTo(2);
        assertThat(revision.snapshot().supersedesSnapshotId())
                .isEqualTo(initial.snapshot().id());
        assertThat(revision.snapshot().revisionReasonCode()).isEqualTo("AUTO_REVISION");
        assertThat(revision.snapshot().revisionNote())
                .isEqualTo("Late sales synchronization");
        assertThat(revision.snapshot().employees())
                .extracting(SnapshotEmployeeMembership::employeeRef)
                .containsExactly("E01");
        assertThat(snapshotStore.findLatest(graph.storeId(), period()))
                .hasValueSatisfying(latest -> assertThat(latest.id())
                        .isEqualTo(revision.snapshot().id()));
        assertThat(snapshotCount(graph.storeId())).isEqualTo(2);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE analytics_snapshots SET timezone = 'UTC' WHERE id = ?",
                initial.snapshot().id()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesToReadPayloadWhoseHashWasNotCreatedFromItsMembership() {
        TestGraph graph = createGraph();
        WeeklySnapshotDraft draft = draft(graph, "100000.00");
        UUID corruptedId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO analytics_snapshots (
                    id, store_id, period_start, period_end, timezone, revision,
                    revision_reason_code, source_sync_job_id, source_sync_completed_at,
                    source_data_cutoff, facts_schema_version, metrics_contract_version,
                    calculation_version, quality_policy_version, quality_status,
                    facts_payload, facts_hash
                ) VALUES (?, ?, ?, ?, ?, 1, 'INITIAL', ?, ?, ?, ?, ?, ?, ?, ?,
                    CAST(? AS jsonb), ?)
                """,
                corruptedId,
                graph.storeId(),
                PERIOD_START,
                PERIOD_END,
                "Europe/Moscow",
                graph.firstSyncJobId(),
                Timestamp.from(FIRST_SYNC_AT),
                Timestamp.from(FIRST_SYNC_AT),
                draft.versions().factsSchemaVersion(),
                draft.versions().metricContractVersion(),
                draft.versions().calculationVersion(),
                draft.versions().qualityPolicyVersion(),
                draft.qualityStatus().name(),
                codec.serialize(draft.payload()),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        SnapshotEmployeeMembership employee = draft.employees().getFirst();
        jdbcTemplate.update(
                """
                INSERT INTO analytics_snapshot_employees (
                    snapshot_id, employee_id, employee_ref, display_name_snapshot
                ) VALUES (?, ?, ?, ?)
                """,
                corruptedId,
                employee.employeeId(),
                employee.employeeRef(),
                employee.displayNameSnapshot()
        );

        assertThatThrownBy(() -> snapshotStore.findById(corruptedId))
                .isInstanceOf(WeeklySnapshotIntegrityException.class)
                .hasMessageContaining("hash does not match");
    }

    private WeeklySnapshotPersistenceCommand command(
            WeeklySnapshotDraft draft,
            UUID sourceSyncJobId,
            Instant completedAt,
            WeeklySnapshotRevisionReason reason,
            String note
    ) {
        return new WeeklySnapshotPersistenceCommand(
                draft,
                sourceSyncJobId,
                completedAt,
                completedAt,
                reason,
                note
        );
    }

    private WeeklySnapshotDraft draft(TestGraph graph, String revenue) {
        SnapshotEmployeeMembership membership = new SnapshotEmployeeMembership(
                graph.employeeId(),
                "E01",
                "Manager Snapshot Name"
        );
        Fact revenueFact = new Fact(
                "STORE.NET_REVENUE.CURRENT",
                "NET_REVENUE",
                null,
                Unit.MONEY,
                new BigDecimal(revenue),
                new Comparison(
                        new BigDecimal("90000.00"),
                        new BigDecimal(revenue).subtract(new BigDecimal("90000.00")),
                        null
                ),
                Sufficiency.SUFFICIENT,
                Materiality.PRIMARY
        );
        Manifest manifest = new Manifest(
                List.of("E01"),
                List.of(new EvidenceIndexEntry(
                        revenueFact.evidenceRef(), Scope.STORE, null, true
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        WeeklySnapshotPayload payload = new WeeklySnapshotPayload(
                1,
                manifest,
                new Facts(
                        List.of(revenueFact),
                        List.of(),
                        List.of(new EmployeeFacts(
                                "E01", Sufficiency.SUFFICIENT,
                                List.of("RESULT"), List.of()
                        )),
                        List.of()
                )
        );
        List<SnapshotEmployeeMembership> employees = List.of(membership);
        WeeklyAnalyticsFactsQuery query = new WeeklyAnalyticsFactsQuery(
                graph.storeId(),
                period(),
                new StoreKpiPeriod(PERIOD_START.minusDays(7), PERIOD_END.minusDays(7))
        );
        return new WeeklySnapshotDraft(
                graph.storeId(),
                query,
                "Europe/Moscow",
                QualityStatus.READY,
                WeeklySnapshotPolicyV1.VERSIONS,
                employees,
                payload,
                codec.hash(payload, employees)
        );
    }

    private TestGraph createGraph() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Snapshot test store')
                """,
                storeId,
                connectionId,
                "snapshot-store-" + storeId
        );
        jdbcTemplate.update(
                """
                INSERT INTO employees (
                    id, connection_id, source_system, external_id, full_name
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Snapshot test manager')
                """,
                employeeId,
                connectionId,
                "snapshot-employee-" + employeeId
        );
        UUID firstSync = addSuccessfulSync(connectionId, FIRST_SYNC_AT);
        UUID secondSync = addSuccessfulSync(connectionId, SECOND_SYNC_AT);
        return new TestGraph(storeId, employeeId, firstSync, secondSync);
    }

    private UUID addSuccessfulSync(UUID connectionId, Instant completedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_jobs (
                    id, connection_id, job_type, status, phase, period_start, period_end,
                    cursor_start, current_window_end, window_size_minutes, max_attempts,
                    next_attempt_at, started_at, finished_at
                ) VALUES (
                    ?, ?, 'BACKFILL', 'SUCCESS', 'RETURNS', ?, ?, ?, ?, 1440, 3, ?, ?, ?
                )
                """,
                id,
                connectionId,
                Timestamp.from(completedAt.minusSeconds(604_800)),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt.minusSeconds(60)),
                Timestamp.from(completedAt)
        );
        return id;
    }

    private int snapshotCount(UUID storeId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM analytics_snapshots WHERE store_id = ?",
                Integer.class,
                storeId
        );
        return value == null ? 0 : value;
    }

    private StoreKpiPeriod period() {
        return new StoreKpiPeriod(PERIOD_START, PERIOD_END);
    }

    private record TestGraph(
            UUID storeId,
            UUID employeeId,
            UUID firstSyncJobId,
            UUID secondSyncJobId
    ) {
    }
}
