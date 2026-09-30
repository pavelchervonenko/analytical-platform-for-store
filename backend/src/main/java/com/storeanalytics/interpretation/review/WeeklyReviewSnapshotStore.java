package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Provenance;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class WeeklyReviewSnapshotStore {

    private static final String LOCK_STORE_SQL = "SELECT id FROM stores WHERE id = ? FOR UPDATE";
    private static final String LATEST_V2_SQL = """
            SELECT *
            FROM weekly_review_snapshots
            WHERE store_id = ?
              AND period_start = ?
              AND period_end = ?
              AND report_contract_version = 2
            ORDER BY revision DESC
            LIMIT 1
            """;
    private static final String LATEST_HEADER_SQL = """
            SELECT id, revision, report_contract_version, content_hash, created_at
            FROM weekly_review_snapshots
            WHERE store_id = ?
              AND period_start = ?
              AND period_end = ?
            ORDER BY revision DESC
            LIMIT 1
            """;
    private static final String LATEST_V3_SQL = """
            SELECT *
            FROM weekly_review_snapshots
            WHERE store_id = ?
              AND period_start = ?
              AND period_end = ?
              AND report_contract_version = 3
            ORDER BY revision DESC
            LIMIT 1
            """;
    private static final String BY_ID_V2_SQL = """
            SELECT *
            FROM weekly_review_snapshots
            WHERE id = ?
              AND report_contract_version = 2
            """;
    private static final String BY_ID_V3_SQL = """
            SELECT *
            FROM weekly_review_snapshots
            WHERE id = ?
              AND report_contract_version = 3
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
    private static final String INSERT_V3_SQL = """
            INSERT INTO weekly_review_snapshots (
                id, store_id, period_start, period_end, timezone, revision,
                supersedes_snapshot_id, report_contract_version,
                metrics_policy_version, snapshot_policy_version, quality_policy_version,
                report_state, source_data_updated_at, report_payload, content_hash,
                report_scope, source_identity_hash
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, 3, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 'SELLERS', ?
            )
            """;
    private static final String UPSERT_V3_GENERATION_STATE_SQL = """
            INSERT INTO weekly_review_generation_state (
                store_id, period_start, period_end, target_contract_version,
                last_evaluated_source_identity_hash, last_evaluated_source_revision,
                compatible_snapshot_id, evaluated_at, outcome
            ) VALUES (?, ?, ?, 3, ?, ?, ?, ?, ?)
            ON CONFLICT (store_id, period_start, period_end, target_contract_version)
            DO UPDATE SET
                last_evaluated_source_identity_hash = EXCLUDED.last_evaluated_source_identity_hash,
                last_evaluated_source_revision = EXCLUDED.last_evaluated_source_revision,
                compatible_snapshot_id = EXCLUDED.compatible_snapshot_id,
                evaluated_at = EXCLUDED.evaluated_at,
                outcome = EXCLUDED.outcome
            """;
    private static final String V3_GENERATION_STATE_SQL = """
            SELECT state.last_evaluated_source_identity_hash,
                   state.last_evaluated_source_revision,
                   state.compatible_snapshot_id,
                   state.evaluated_at,
                   state.outcome,
                   (SELECT snapshot.id FROM weekly_review_snapshots snapshot
                    WHERE snapshot.store_id = state.store_id
                      AND snapshot.period_start = state.period_start
                      AND snapshot.period_end = state.period_end
                    ORDER BY snapshot.revision DESC LIMIT 1) AS latest_snapshot_id
            FROM weekly_review_generation_state state
            WHERE state.store_id = ? AND state.period_start = ? AND state.period_end = ?
              AND state.target_contract_version = 3
            """;

    private final JdbcTemplate jdbcTemplate;
    private final WeeklyReviewSnapshotCodec codec;
    private final WeeklyReviewVersionedCodec versionedCodec;
    private final com.storeanalytics.metrics.warranty.AttachAttributionPolicy attachPolicy;
    private final Clock clock;
    private final WeeklyReviewAttributionRepository attribution;
    private final WeeklyReviewAssembler assembler =
            new WeeklyReviewAssembler(new WeeklyReviewPolicyV1());
    private final SellerWeeklyV3Assembler sellerAssembler = new SellerWeeklyV3Assembler();

    public WeeklyReviewSnapshotStore(
            JdbcTemplate jdbcTemplate,
            WeeklyReviewSnapshotCodec codec,
            WeeklyReviewVersionedCodec versionedCodec,
            com.storeanalytics.metrics.warranty.AttachAttributionPolicy attachPolicy,
            Clock clock,
            WeeklyReviewAttributionRepository attribution
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.codec = codec;
        this.versionedCodec = versionedCodec;
        this.attachPolicy = attachPolicy;
        this.clock = clock;
        this.attribution = attribution;
    }

    public boolean currentAttributionPolicy(WeeklyReviewResponse.VersionSet versions) {
        return WeeklyReviewPolicyV1.versionsForAttach(attachPolicy.enabled()).equals(versions);
    }

    @Transactional(readOnly = true)
    public boolean attributionChangedSince(UUID storeId, UUID snapshotId, Instant calculatedAt) {
        // calculatedAt is presentation time, not a trustworthy database watermark.
        requireNonNull(calculatedAt, "calculatedAt");
        return attribution.changed(storeId, snapshotId);
    }

    @Transactional
    public void acknowledgeAttribution(UUID storeId, UUID snapshotId, Optional<Instant> observedChange) {
        attribution.acknowledge(storeId, snapshotId, observedChange);
    }

    @Transactional
    public PersistedWeeklyReviewSnapshot persist(
            WeeklyReviewFacts facts,
            Instant calculatedAt
    ) {
        WeeklyReviewFacts source = requireNonNull(facts, "facts");
        Instant calculated = requireNonNull(calculatedAt, "calculatedAt");
        lockStore(source.storeId());
        Optional<LatestHeader> latest = findLatestHeaderInternal(
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
                latest.map(LatestHeader::createdAt).orElse(null)
        );
        WeeklyReviewResponse response = assembler.assemble(source, provenance);
        String contentHash = codec.contentHash(response);
        if (latest.isPresent() && latest.get().contractVersion() == 2
                && latest.get().contentHash().equals(contentHash)) {
            return findByIdInternal(latest.get().id()).orElseThrow(() ->
                    new IllegalStateException("Latest v2 weekly review snapshot is missing"));
        }
        insert(
                snapshotId,
                revision,
                latest.map(LatestHeader::id).orElse(null),
                source,
                response,
                contentHash
        );
        return findByIdInternal(snapshotId).orElseThrow(() ->
                new IllegalStateException("Created weekly review snapshot could not be read")
        );
    }

    /** Internal candidate writer only; publication still needs a bounded retry and freshness protocol. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    PersistedWeeklyReviewV3Snapshot persistV3Candidate(
            SellerWeeklyReviewFacts facts,
            Instant calculatedAt,
            String sourceIdentityHash
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("V3 snapshot writer requires a writable transaction");
        }
        SellerWeeklyReviewFacts source = requireNonNull(facts, "facts");
        Instant calculated = requireNonNull(calculatedAt, "calculatedAt");
        lockStore(source.storeId());
        String timezoneAtWrite = jdbcTemplate.queryForObject(
                "SELECT timezone FROM stores WHERE id = ?", String.class, source.storeId());
        if (!source.period().timezone().equals(timezoneAtWrite)) {
            throw new SellerWeeklySourceChangedException();
        }
        jdbcTemplate.update("""
                INSERT INTO store_analytics_source_state (store_id, revision)
                VALUES (?, 0) ON CONFLICT DO NOTHING
                """, source.storeId());
        Long revisionAtWrite = jdbcTemplate.queryForObject("""
                SELECT revision FROM store_analytics_source_state WHERE store_id = ? FOR UPDATE
                """, Long.class, source.storeId());
        if (revisionAtWrite == null || revisionAtWrite != source.sourceRevision()) {
            throw new SellerWeeklySourceChangedException();
        }
        Instant evaluatedAt = clock.instant();
        SellerWeeklyTemporalFence.verify(source, evaluatedAt);
        Optional<LatestHeader> latest = findLatestHeaderInternal(
                source.storeId(), source.period().current());
        int revision = latest.map(item -> item.revision() + 1).orElse(1);
        UUID snapshotId = UUID.randomUUID();
        Provenance provenance = new Provenance(snapshotId.toString(), revision, calculated,
                source.sourceDataUpdatedAt(), latest.isPresent(),
                latest.map(LatestHeader::createdAt).orElse(null));
        WeeklyReviewV3Response response = sellerAssembler.assemble(
                source, provenance, sourceIdentityHash, calculated);
        String contentHash = versionedCodec.contentHash(response);
        if (latest.isPresent() && latest.get().contractVersion() == 3
                && latest.get().contentHash().equals(contentHash)) {
            PersistedWeeklyReviewV3Snapshot reused = findV3ById(latest.get().id()).orElseThrow(() ->
                    new IllegalStateException("Latest v3 weekly review snapshot is missing"));
            checkpointV3(source, sourceIdentityHash, reused.id(), evaluatedAt, "REUSED");
            return reused;
        }
        jdbcTemplate.update(INSERT_V3_SQL,
                snapshotId, source.storeId(), source.period().current().start(),
                source.period().current().end(), source.period().timezone(), revision,
                latest.map(LatestHeader::id).orElse(null),
                response.versions().metricsPolicy(), response.versions().snapshotPolicy(),
                response.versions().qualityPolicy(), response.reportState().name(),
                timestamp(source.sourceDataUpdatedAt()), versionedCodec.serialize(response),
                contentHash, response.sourceIdentityHash());
        checkpointV3(source, sourceIdentityHash, snapshotId, evaluatedAt, "CREATED");
        return findV3ById(snapshotId).orElseThrow(() ->
                new IllegalStateException("Created v3 weekly review snapshot could not be read"));
    }

    private void checkpointV3(SellerWeeklyReviewFacts source, String identityHash,
                              UUID snapshotId, Instant evaluatedAt, String outcome) {
        jdbcTemplate.update(UPSERT_V3_GENERATION_STATE_SQL,
                source.storeId(), source.period().current().start(), source.period().current().end(),
                identityHash, source.sourceRevision(), snapshotId, Timestamp.from(evaluatedAt), outcome);
    }

    @Transactional(readOnly = true)
    public Optional<PersistedWeeklyReviewSnapshot> findLatest(
            UUID storeId,
            DateRange period
    ) {
        return findLatestV2Internal(
                requireNonNull(storeId, "storeId"),
                requireNonNull(period, "period")
        );
    }

    @Transactional(readOnly = true)
    public Optional<PersistedWeeklyReviewSnapshot> findById(UUID snapshotId) {
        return findByIdInternal(requireNonNull(snapshotId, "snapshotId"));
    }

    @Transactional(readOnly = true)
    public Optional<PersistedWeeklyReviewV3Snapshot> findLatestV3(UUID storeId, DateRange period) {
        UUID selectedStore = requireNonNull(storeId, "storeId");
        DateRange selectedPeriod = requireNonNull(period, "period");
        return single(jdbcTemplate.query(LATEST_V3_SQL, this::mapV3Row,
                selectedStore, selectedPeriod.start(), selectedPeriod.end()));
    }

    @Transactional(readOnly = true)
    public Optional<PersistedWeeklyReviewV3Snapshot> findV3ById(UUID snapshotId) {
        return single(jdbcTemplate.query(BY_ID_V3_SQL, this::mapV3Row,
                requireNonNull(snapshotId, "snapshotId")));
    }

    @Transactional(readOnly = true)
    Optional<V3GenerationState> findV3GenerationState(UUID storeId, DateRange period) {
        DateRange selectedPeriod = requireNonNull(period, "period");
        return single(jdbcTemplate.query(V3_GENERATION_STATE_SQL,
                (row, index) -> new V3GenerationState(
                        row.getString("last_evaluated_source_identity_hash"),
                        row.getLong("last_evaluated_source_revision"),
                        row.getObject("compatible_snapshot_id", UUID.class),
                        row.getTimestamp("evaluated_at").toInstant(),
                        row.getString("outcome"),
                        row.getObject("latest_snapshot_id", UUID.class)),
                requireNonNull(storeId, "storeId"), selectedPeriod.start(), selectedPeriod.end()));
    }

    record V3GenerationState(String identityHash, long sourceRevision, UUID snapshotId,
                             Instant evaluatedAt, String outcome, UUID latestSnapshotId) {
    }

    private Optional<PersistedWeeklyReviewSnapshot> findLatestV2Internal(
            UUID storeId,
            DateRange period
    ) {
        return single(jdbcTemplate.query(
                LATEST_V2_SQL,
                this::mapRow,
                storeId,
                period.start(),
                period.end()
        ));
    }

    private Optional<LatestHeader> findLatestHeaderInternal(UUID storeId, DateRange period) {
        return single(jdbcTemplate.query(LATEST_HEADER_SQL, (row, index) -> new LatestHeader(
                row.getObject("id", UUID.class), row.getInt("revision"),
                row.getInt("report_contract_version"), row.getString("content_hash"),
                row.getTimestamp("created_at").toInstant()),
                storeId, period.start(), period.end()));
    }

    private Optional<PersistedWeeklyReviewSnapshot> findByIdInternal(UUID snapshotId) {
        return single(jdbcTemplate.query(BY_ID_V2_SQL, this::mapRow, snapshotId));
    }

    private PersistedWeeklyReviewSnapshot mapRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        UUID id = resultSet.getObject("id", UUID.class);
        UUID storeId = resultSet.getObject("store_id", UUID.class);
        WeeklyReviewResponse response = codec.deserialize(
                resultSet.getString("report_payload")
        );
        String contentHash = resultSet.getString("content_hash");
        verifyIntegrity(resultSet, id, response, contentHash, codec.contentHash(response));
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

    private PersistedWeeklyReviewV3Snapshot mapV3Row(ResultSet resultSet, int rowNumber)
            throws SQLException {
        UUID id = resultSet.getObject("id", UUID.class);
        UUID storeId = resultSet.getObject("store_id", UUID.class);
        WeeklyReviewContract decoded = versionedCodec.deserialize(
                resultSet.getInt("report_contract_version"), resultSet.getString("report_payload"));
        if (!(decoded instanceof WeeklyReviewV3Response response)) {
            throw new IllegalStateException("V3 snapshot query returned a different contract");
        }
        String contentHash = resultSet.getString("content_hash");
        verifyIntegrity(resultSet, id, response, contentHash,
                versionedCodec.contentHash(response));
        if (!response.scope().equals(resultSet.getString("report_scope"))
                || !response.sourceIdentityHash().equals(
                        resultSet.getString("source_identity_hash"))) {
            throw new IllegalStateException("V3 weekly review scope or identity mismatch");
        }
        return new PersistedWeeklyReviewV3Snapshot(id, storeId,
                resultSet.getInt("revision"),
                resultSet.getObject("supersedes_snapshot_id", UUID.class),
                response, contentHash, resultSet.getTimestamp("created_at").toInstant());
    }

    private void verifyIntegrity(
            ResultSet row,
            UUID id,
            WeeklyReviewContract response,
            String expectedHash,
            String actualHash
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
        if (!headerMatches || !expectedHash.equals(actualHash)) {
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

    private record LatestHeader(
            UUID id,
            int revision,
            int contractVersion,
            String contentHash,
            Instant createdAt
    ) {
    }
}
