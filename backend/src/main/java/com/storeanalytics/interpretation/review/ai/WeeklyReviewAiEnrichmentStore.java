package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WeeklyReviewAiEnrichmentStore {

    private static final String LOCK_SNAPSHOT_SQL = """
            SELECT id
            FROM weekly_review_snapshots
            WHERE id = ?
            FOR UPDATE
            """;
    private static final String FIND_SQL = """
            SELECT *
            FROM weekly_review_ai_enrichments
            WHERE snapshot_id = ?
              AND prompt_version = ?
              AND content_schema_version = ?
            """;
    private static final String FIND_PUBLISHED_SQL = """
            SELECT *
            FROM weekly_review_ai_enrichments
            WHERE snapshot_id = ?
              AND prompt_version = ?
              AND content_schema_version = ?
              AND published_at <= ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO weekly_review_ai_enrichments (
                id, snapshot_id, prompt_version, content_schema_version,
                input_hash, content_payload, content_hash,
                validated_at, published_at
            ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final WeeklyReviewAiContentCodec codec;

    public WeeklyReviewAiEnrichmentStore(
            JdbcTemplate jdbcTemplate,
            WeeklyReviewAiContentCodec codec
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.codec = codec;
    }

    @Transactional
    public PersistedWeeklyReviewAiEnrichment persist(
            UUID snapshotId,
            WeeklyReviewAiInput input,
            WeeklyReviewAiValidationResult validation,
            Instant validatedAt,
            Instant publishedAt
    ) {
        UUID snapshot = requireNonNull(snapshotId, "snapshotId");
        WeeklyReviewAiInput source = requireNonNull(input, "input");
        WeeklyReviewAiValidationResult result = requireNonNull(
                validation, "validation"
        );
        Instant validated = requireNonNull(validatedAt, "validatedAt");
        Instant published = requireNonNull(publishedAt, "publishedAt");
        require(!published.isBefore(validated),
                "publishedAt must not precede validatedAt");
        require(result.semanticValidated() && result.content() != null,
                "only semantically valid AI enrichment may be persisted");

        String canonicalContent = codec.canonical(result.content());
        require(canonicalContent.equals(result.canonicalContent()),
                "AI enrichment canonical content mismatch");
        String inputHash = codec.hash(codec.canonical(source));
        String contentHash = codec.hash(canonicalContent);

        lockSnapshot(snapshot);
        Optional<PersistedWeeklyReviewAiEnrichment> existing = findInternal(
                snapshot,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION
        );
        if (existing.isPresent()) {
            PersistedWeeklyReviewAiEnrichment value = existing.get();
            if (value.inputHash().equals(inputHash)
                    && value.contentHash().equals(contentHash)) {
                return value;
            }
            throw new IllegalStateException(
                    "AI enrichment already exists with different content"
            );
        }

        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                INSERT_SQL,
                id,
                snapshot,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                inputHash,
                canonicalContent,
                contentHash,
                Timestamp.from(validated),
                Timestamp.from(published)
        );
        return findInternal(
                snapshot,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION
        ).orElseThrow(() -> new IllegalStateException(
                "Created AI enrichment could not be read"
        ));
    }

    @Transactional(readOnly = true)
    public Optional<PersistedWeeklyReviewAiEnrichment> findPublished(
            UUID snapshotId,
            Instant asOf
    ) {
        UUID snapshot = requireNonNull(snapshotId, "snapshotId");
        Instant publishedAsOf = requireNonNull(asOf, "asOf");
        Optional<PersistedWeeklyReviewAiEnrichment> active =
                findPublishedInternal(
                        snapshot,
                        WeeklyReviewAiContract.PROMPT_VERSION,
                        WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                        publishedAsOf
                );
        if (active.isPresent()) {
            return active;
        }
        Optional<PersistedWeeklyReviewAiEnrichment> previous =
                findPublishedInternal(
                        snapshot,
                        WeeklyReviewAiContract.PREVIOUS_PROMPT_VERSION,
                        WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                        publishedAsOf
                );
        return previous.isPresent()
                ? previous
                : findPublishedInternal(
                        snapshot,
                        WeeklyReviewAiContract.LEGACY_PROMPT_VERSION,
                        WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                        publishedAsOf
                );
    }

    private Optional<PersistedWeeklyReviewAiEnrichment> findPublishedInternal(
            UUID snapshotId,
            String promptVersion,
            int contentSchemaVersion,
            Instant asOf
    ) {
        return single(jdbcTemplate.query(
                FIND_PUBLISHED_SQL,
                (resultSet, rowNumber) -> mapRow(
                        resultSet, rowNumber, promptVersion, contentSchemaVersion
                ),
                snapshotId,
                promptVersion,
                contentSchemaVersion,
                Timestamp.from(asOf)
        ));
    }

    private void lockSnapshot(UUID snapshotId) {
        List<UUID> values = jdbcTemplate.query(
                LOCK_SNAPSHOT_SQL,
                (resultSet, rowNumber) ->
                        resultSet.getObject("id", UUID.class),
                snapshotId
        );
        require(!values.isEmpty(), "Weekly review snapshot does not exist");
    }

    private Optional<PersistedWeeklyReviewAiEnrichment> findInternal(
            UUID snapshotId,
            String promptVersion,
            int contentSchemaVersion
    ) {
        return single(jdbcTemplate.query(
                FIND_SQL,
                (resultSet, rowNumber) -> mapRow(
                        resultSet, rowNumber,
                        promptVersion, contentSchemaVersion
                ),
                snapshotId,
                promptVersion,
                contentSchemaVersion
        ));
    }

    private PersistedWeeklyReviewAiEnrichment mapRow(
            ResultSet resultSet,
            int rowNumber,
            String expectedPromptVersion,
            int expectedContentSchemaVersion
    ) throws SQLException {
        WeeklyReviewAiContent content = codec.deserialize(
                resultSet.getString("content_payload")
        );
        String canonicalContent = codec.canonical(content);
        String contentHash = resultSet.getString("content_hash");
        String promptVersion = resultSet.getString("prompt_version");
        int contentSchemaVersion = resultSet.getInt("content_schema_version");
        boolean headerMatches = promptVersion.equals(expectedPromptVersion)
                && contentSchemaVersion == expectedContentSchemaVersion
                && WeeklyReviewAiContract.isReadable(
                        promptVersion, contentSchemaVersion
                )
                && content.schemaVersion() == contentSchemaVersion;
        if (!headerMatches
                || !contentHash.equals(codec.hash(canonicalContent))) {
            throw new IllegalStateException(
                    "Weekly review AI enrichment integrity check failed"
            );
        }
        return new PersistedWeeklyReviewAiEnrichment(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("snapshot_id", UUID.class),
                resultSet.getString("prompt_version"),
                resultSet.getInt("content_schema_version"),
                resultSet.getString("input_hash"),
                content,
                canonicalContent,
                contentHash,
                resultSet.getTimestamp("validated_at").toInstant(),
                resultSet.getTimestamp("published_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private <T> Optional<T> single(List<T> values) {
        return values.isEmpty()
                ? Optional.empty()
                : Optional.of(values.getFirst());
    }
}
