package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WeeklySnapshotStore {

    private static final String LOCK_STORE_SQL = "SELECT id FROM stores WHERE id = ? FOR UPDATE";
    private static final String LATEST_SQL = """
            SELECT *
            FROM analytics_snapshots
            WHERE store_id = ?
              AND snapshot_type = 'WEEKLY'
              AND period_start = ?
              AND period_end = ?
            ORDER BY revision DESC
            LIMIT 1
            """;
    private static final String BY_ID_SQL = """
            SELECT *
            FROM analytics_snapshots
            WHERE id = ?
            """;
    private static final String INSERT_SNAPSHOT_SQL = """
            INSERT INTO analytics_snapshots (
                id, store_id, snapshot_type, period_start, period_end, timezone,
                revision, supersedes_snapshot_id, revision_reason_code, revision_note,
                source_sync_job_id, source_sync_completed_at, source_data_cutoff,
                facts_schema_version, metrics_contract_version, calculation_version,
                quality_policy_version, quality_status, facts_payload, facts_hash
            ) VALUES (
                ?, ?, 'WEEKLY', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                CAST(? AS jsonb), ?
            )
            """;
    private static final String INSERT_EMPLOYEE_SQL = """
            INSERT INTO analytics_snapshot_employees (
                snapshot_id, employee_id, employee_ref, display_name_snapshot
            ) VALUES (?, ?, ?, ?)
            """;
    private static final String EMPLOYEES_SQL = """
            SELECT employee_id, employee_ref, display_name_snapshot
            FROM analytics_snapshot_employees
            WHERE snapshot_id = ?
            ORDER BY employee_ref
            """;

    private final JdbcTemplate jdbcTemplate;
    private final WeeklySnapshotPayloadCodec codec;

    public WeeklySnapshotStore(
            JdbcTemplate jdbcTemplate,
            WeeklySnapshotPayloadCodec codec
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.codec = codec;
    }

    @Transactional
    public WeeklySnapshotWriteResult persist(WeeklySnapshotPersistenceCommand command) {
        WeeklySnapshotPersistenceCommand value = requireNonNull(command, "command");
        WeeklySnapshotDraft draft = value.draft();
        lockStore(draft.storeId());
        Optional<PersistedWeeklySnapshot> latest = findLatestInternal(
                draft.storeId(),
                draft.query().period()
        );
        if (latest.isPresent() && unchanged(draft, latest.get())) {
            WeeklySnapshotWriteOutcome outcome = sameWriteAttempt(value, latest.get())
                    ? WeeklySnapshotWriteOutcome.CREATED
                    : WeeklySnapshotWriteOutcome.UNCHANGED;
            return new WeeklySnapshotWriteResult(
                    outcome,
                    latest.get()
            );
        }

        int revision = latest.map(snapshot -> snapshot.revision() + 1).orElse(1);
        UUID supersedes = latest.map(PersistedWeeklySnapshot::id).orElse(null);
        String reason = revision == 1 ? "INITIAL" : value.revisionReason().name();
        UUID snapshotId = UUID.randomUUID();
        insert(snapshotId, revision, supersedes, reason, value);
        insertEmployees(snapshotId, draft.employees());
        PersistedWeeklySnapshot created = findByIdInternal(snapshotId)
                .orElseThrow(() -> new IllegalStateException(
                        "Created weekly snapshot could not be read"
                ));
        return new WeeklySnapshotWriteResult(WeeklySnapshotWriteOutcome.CREATED, created);
    }

    private boolean sameWriteAttempt(
            WeeklySnapshotPersistenceCommand command,
            PersistedWeeklySnapshot snapshot
    ) {
        return snapshot.sourceSyncJobId().equals(command.sourceSyncJobId())
                && snapshot.sourceSyncCompletedAt().equals(command.sourceSyncCompletedAt())
                && snapshot.sourceDataCutoff().equals(command.sourceDataCutoff());
    }

    @Transactional(readOnly = true)
    public Optional<PersistedWeeklySnapshot> findById(UUID snapshotId) {
        return findByIdInternal(requireNonNull(snapshotId, "snapshotId"));
    }

    @Transactional(readOnly = true)
    public Optional<PersistedWeeklySnapshot> findLatest(
            UUID storeId,
            StoreKpiPeriod period
    ) {
        return findLatestInternal(
                requireNonNull(storeId, "storeId"),
                requireNonNull(period, "period")
        );
    }

    private void lockStore(UUID storeId) {
        List<UUID> locked = jdbcTemplate.query(
                LOCK_STORE_SQL,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                storeId
        );
        if (locked.isEmpty()) {
            throw new IllegalArgumentException("Store does not exist: " + storeId);
        }
    }

    private Optional<PersistedWeeklySnapshot> findLatestInternal(
            UUID storeId,
            StoreKpiPeriod period
    ) {
        return single(jdbcTemplate.query(
                LATEST_SQL,
                this::mapRow,
                storeId,
                period.start(),
                period.end()
        ));
    }

    private Optional<PersistedWeeklySnapshot> findByIdInternal(UUID snapshotId) {
        return single(jdbcTemplate.query(BY_ID_SQL, this::mapRow, snapshotId));
    }

    private PersistedWeeklySnapshot mapRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        UUID snapshotId = resultSet.getObject("id", UUID.class);
        UUID storeId = resultSet.getObject("store_id", UUID.class);
        LocalDate start = resultSet.getObject("period_start", LocalDate.class);
        LocalDate end = resultSet.getObject("period_end", LocalDate.class);
        WeeklyAnalyticsFactsQuery query = new WeeklyAnalyticsFactsQuery(
                storeId,
                new StoreKpiPeriod(start, end),
                new StoreKpiPeriod(start.minusDays(7), end.minusDays(7))
        );
        Versions versions = new Versions(
                resultSet.getInt("facts_schema_version"),
                resultSet.getString("metrics_contract_version"),
                resultSet.getString("calculation_version"),
                resultSet.getString("quality_policy_version")
        );
        WeeklySnapshotPayload payload = codec.deserialize(resultSet.getString("facts_payload"));
        List<SnapshotEmployeeMembership> employees = employees(snapshotId);
        String factsHash = resultSet.getString("facts_hash");
        verifyIntegrity(payload, employees, factsHash, versions);
        return new PersistedWeeklySnapshot(
                snapshotId,
                storeId,
                query,
                resultSet.getString("timezone"),
                resultSet.getInt("revision"),
                resultSet.getObject("supersedes_snapshot_id", UUID.class),
                resultSet.getString("revision_reason_code"),
                resultSet.getString("revision_note"),
                resultSet.getObject("source_sync_job_id", UUID.class),
                instant(resultSet, "source_sync_completed_at"),
                instant(resultSet, "source_data_cutoff"),
                QualityStatus.valueOf(resultSet.getString("quality_status")),
                versions,
                payload,
                factsHash,
                employees,
                instant(resultSet, "created_at")
        );
    }

    private List<SnapshotEmployeeMembership> employees(UUID snapshotId) {
        return jdbcTemplate.query(
                EMPLOYEES_SQL,
                (resultSet, rowNumber) -> new SnapshotEmployeeMembership(
                        resultSet.getObject("employee_id", UUID.class),
                        resultSet.getString("employee_ref"),
                        resultSet.getString("display_name_snapshot")
                ),
                snapshotId
        );
    }

    private void verifyIntegrity(
            WeeklySnapshotPayload payload,
            List<SnapshotEmployeeMembership> employees,
            String expectedHash,
            Versions versions
    ) {
        String actualHash = codec.hash(payload, employees);
        if (!MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII),
                actualHash.getBytes(StandardCharsets.US_ASCII)
        )) {
            throw new WeeklySnapshotIntegrityException(
                    "Weekly snapshot facts hash does not match its payload"
            );
        }
        List<String> membershipRefs = employees.stream()
                .map(SnapshotEmployeeMembership::employeeRef)
                .toList();
        if (!payload.manifest().employeeRefs().equals(membershipRefs)) {
            throw new WeeklySnapshotIntegrityException(
                    "Weekly snapshot employee manifest does not match membership"
            );
        }
        if (payload.contractVersion() != versions.factsSchemaVersion()) {
            throw new WeeklySnapshotIntegrityException(
                    "Weekly snapshot payload version does not match its header"
            );
        }
    }

    private boolean unchanged(
            WeeklySnapshotDraft draft,
            PersistedWeeklySnapshot latest
    ) {
        return draft.factsHash().equals(latest.factsHash())
                && draft.versions().equals(latest.versions())
                && draft.qualityStatus() == latest.qualityStatus()
                && draft.timezone().equals(latest.timezone());
    }

    private void insert(
            UUID snapshotId,
            int revision,
            UUID supersedes,
            String reason,
            WeeklySnapshotPersistenceCommand command
    ) {
        WeeklySnapshotDraft draft = command.draft();
        Versions versions = draft.versions();
        jdbcTemplate.update(
                INSERT_SNAPSHOT_SQL,
                snapshotId,
                draft.storeId(),
                draft.query().period().start(),
                draft.query().period().end(),
                draft.timezone(),
                revision,
                supersedes,
                reason,
                command.revisionNote(),
                command.sourceSyncJobId(),
                Timestamp.from(command.sourceSyncCompletedAt()),
                Timestamp.from(command.sourceDataCutoff()),
                versions.factsSchemaVersion(),
                versions.metricContractVersion(),
                versions.calculationVersion(),
                versions.qualityPolicyVersion(),
                draft.qualityStatus().name(),
                codec.serialize(draft.payload()),
                draft.factsHash()
        );
    }

    private void insertEmployees(
            UUID snapshotId,
            List<SnapshotEmployeeMembership> employees
    ) {
        if (employees.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                INSERT_EMPLOYEE_SQL,
                employees,
                employees.size(),
                (statement, employee) -> {
                    statement.setObject(1, snapshotId);
                    statement.setObject(2, employee.employeeId());
                    statement.setString(3, employee.employeeRef());
                    statement.setString(4, employee.displayNameSnapshot());
                }
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private static <T> Optional<T> single(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }
}
