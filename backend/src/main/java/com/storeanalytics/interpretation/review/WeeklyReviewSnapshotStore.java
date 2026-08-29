package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Provenance;
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
public class WeeklyReviewSnapshotStore {

    private static final String LOCK_STORE_SQL = "SELECT id FROM stores WHERE id = ? FOR UPDATE";
    private static final String LATEST_SQL = """
            SELECT *
            FROM weekly_review_snapshots
            WHERE store_id = ?
              AND period_start = ?
              AND period_end = ?
            ORDER BY revision DESC
            LIMIT 1
            """;
    private static final String BY_ID_SQL = """
            SELECT *
            FROM weekly_review_snapshots
            WHERE id = ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO weekly_review_snapshots (
                id, store_id, period_start, period_end, timezone, revision,
                supersedes_snapshot_id, report_contract_version,
                metrics_policy_version, snapshot_policy_version, quality_policy_version,
                report_state, source_data_updated_at, report_payload, content_hash
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final WeeklyReviewSnapshotCodec codec;
    private final WeeklyReviewAssembler assembler =
            new WeeklyReviewAssembler(new WeeklyReviewPolicyV1());

    public WeeklyReviewSnapshotStore(
            JdbcTemplate jdbcTemplate,
            WeeklyReviewSnapshotCodec codec
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.codec = codec;
    }

    @Transactional
    public PersistedWeeklyReviewSnapshot persist(
            WeeklyReviewFacts facts,
            Instant calculatedAt
    ) {
        WeeklyReviewFacts source = requireNonNull(facts, "facts");
        Instant calculated = requireNonNull(calculatedAt, "calculatedAt");
        lockStore(source.storeId());
        Optional<PersistedWeeklyReviewSnapshot> latest = findLatestInternal(
                source.storeId(), source.period().current()
        );
        int revision = latest.map(snapshot -> snapshot.revision() + 1).orElse(1);
        UUID snapshotId = UUID.randomUUID();
        Provenance provenance = new Provenance(
                snapshotId.toString(),
                revision,
                calculated,
                source.sourceDataUpdatedAt(),
                latest.isPresent(),
                latest.map(PersistedWeeklyReviewSnapshot::createdAt).orElse(null)
        );
        WeeklyReviewResponse response = assembler.assemble(source, provenance);
        String contentHash = codec.contentHash(response);
        if (latest.isPresent() && latest.get().contentHash().equals(contentHash)) {
            return latest.get();
        }
        insert(
                snapshotId,
                revision,
                latest.map(PersistedWeeklyReviewSnapshot::id).orElse(null),
                source,
                response,
                contentHash
        );
        return findByIdInternal(snapshotId).orElseThrow(() ->
                new IllegalStateException("Created weekly review snapshot could not be read")
        );
    }

    @Transactional(readOnly = true)
    public Optional<PersistedWeeklyReviewSnapshot> findLatest(
            UUID storeId,
            DateRange period
    ) {
        return findLatestInternal(
                requireNonNull(storeId, "storeId"),
                requireNonNull(period, "period")
        );
    }

    @Transactional(readOnly = true)
    public Optional<PersistedWeeklyReviewSnapshot> findById(UUID snapshotId) {
        return findByIdInternal(requireNonNull(snapshotId, "snapshotId"));
    }

    private Optional<PersistedWeeklyReviewSnapshot> findLatestInternal(
            UUID storeId,
            DateRange period
    ) {
        return single(jdbcTemplate.query(
                LATEST_SQL,
                this::mapRow,
                storeId,
                period.start(),
                period.end()
        ));
    }

    private Optional<PersistedWeeklyReviewSnapshot> findByIdInternal(UUID snapshotId) {
        return single(jdbcTemplate.query(BY_ID_SQL, this::mapRow, snapshotId));
    }

    private PersistedWeeklyReviewSnapshot mapRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        UUID id = resultSet.getObject("id", UUID.class);
        UUID storeId = resultSet.getObject("store_id", UUID.class);
        WeeklyReviewResponse response = codec.deserialize(
                resultSet.getString("report_payload")
        );
        String contentHash = resultSet.getString("content_hash");
        verifyIntegrity(resultSet, id, response, contentHash);
        return new PersistedWeeklyReviewSnapshot(
                id,
                storeId,
                resultSet.getInt("revision"),
                resultSet.getObject("supersedes_snapshot_id", UUID.class),
                response,
                contentHash,
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private void verifyIntegrity(
            ResultSet row,
            UUID id,
            WeeklyReviewResponse response,
            String expectedHash
    ) throws SQLException {
        LocalDate start = row.getObject("period_start", LocalDate.class);
        LocalDate end = row.getObject("period_end", LocalDate.class);
        boolean headerMatches = id.toString().equals(
                response.provenance().snapshotPublicId()
        ) && row.getInt("revision") == response.provenance().revision()
                && row.getInt("report_contract_version") == response.contractVersion()
                && row.getString("timezone").equals(response.period().timezone())
                && start.equals(response.period().current().start())
                && end.equals(response.period().current().end())
                && row.getString("metrics_policy_version").equals(
                        response.versions().metricsPolicy()
                )
                && row.getString("snapshot_policy_version").equals(
                        response.versions().snapshotPolicy()
                )
                && row.getString("quality_policy_version").equals(
                        response.versions().qualityPolicy()
                );
        if (!headerMatches || !expectedHash.equals(codec.contentHash(response))) {
            throw new IllegalStateException("Weekly review snapshot integrity check failed");
        }
    }

    private void lockStore(UUID storeId) {
        List<UUID> stores = jdbcTemplate.query(
                LOCK_STORE_SQL,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                storeId
        );
        if (stores.isEmpty()) {
            throw new IllegalArgumentException("Store does not exist: " + storeId);
        }
    }

    private void insert(
            UUID id,
            int revision,
            UUID supersedes,
            WeeklyReviewFacts facts,
            WeeklyReviewResponse response,
            String contentHash
    ) {
        jdbcTemplate.update(
                INSERT_SQL,
                id,
                facts.storeId(),
                facts.period().current().start(),
                facts.period().current().end(),
                facts.period().timezone(),
                revision,
                supersedes,
                response.contractVersion(),
                response.versions().metricsPolicy(),
                response.versions().snapshotPolicy(),
                response.versions().qualityPolicy(),
                response.reportState().name(),
                timestamp(facts.sourceDataUpdatedAt()),
                codec.serialize(response),
                contentHash
        );
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private <T> Optional<T> single(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }
}
