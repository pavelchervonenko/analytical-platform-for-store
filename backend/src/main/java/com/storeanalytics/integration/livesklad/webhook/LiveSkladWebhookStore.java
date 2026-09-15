package com.storeanalytics.integration.livesklad.webhook;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class LiveSkladWebhookStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    LiveSkladWebhookStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    void record(LiveSkladWebhookReceipt receipt) {
        jdbcTemplate.update(
                """
                INSERT INTO livesklad_webhook_receipts (
                    webhook_kind,
                    event_id,
                    action_id,
                    action_group_id,
                    action_name,
                    payload,
                    payload_sha256,
                    last_payload_sha256,
                    first_received_at,
                    last_received_at,
                    available_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                ON CONFLICT (webhook_kind, event_id) DO UPDATE
                SET delivery_count =
                        livesklad_webhook_receipts.delivery_count + 1,
                    last_received_at = GREATEST(
                        livesklad_webhook_receipts.last_received_at,
                        EXCLUDED.last_received_at
                    ),
                    last_payload_sha256 = EXCLUDED.last_payload_sha256,
                    payload_mismatch =
                        livesklad_webhook_receipts.payload_mismatch
                        OR livesklad_webhook_receipts.payload_sha256
                            <> EXCLUDED.payload_sha256
                """,
                receipt.kind().name(),
                receipt.eventId(),
                receipt.actionId(),
                receipt.actionGroupId(),
                receipt.actionName(),
                receipt.payload(),
                receipt.payloadSha256(),
                receipt.payloadSha256(),
                Timestamp.from(receipt.receivedAt()),
                Timestamp.from(receipt.receivedAt()),
                Timestamp.from(receipt.receivedAt())
        );
    }

    void lockRecoveryCreation() {
        jdbcTemplate.execute(
                "SELECT pg_advisory_xact_lock(1937006964, 20260820)"
        );
    }

    LiveSkladReturnRecoveryView createRecovery(
            LiveSkladReturnRecoveryRequest request
    ) {
        return jdbcTemplate.query(
                """
                INSERT INTO livesklad_webhook_receipts (
                    id,
                    webhook_kind,
                    event_id,
                    action_name,
                    payload,
                    payload_sha256,
                    last_payload_sha256,
                    source_document_id,
                    first_received_at,
                    last_received_at,
                    recovery_requested_by,
                    recovery_idempotency_key,
                    recovery_expected_document_number,
                    recovery_expected_net_amount,
                    recovery_expected_position_count,
                    recovery_mode,
                    recovery_expected_current_employee_external_id,
                    recovery_expected_original_sale_external_id,
                    recovery_expected_original_employee_external_id,
                    recovery_expected_original_links,
                    recovery_reason,
                    recovery_requested_at,
                    available_at
                ) VALUES (
                    ?,
                    'SALE_RETURN',
                    ?,
                    'manualRecovery',
                    ?::jsonb,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?::jsonb,
                    ?,
                    ?,
                    ?
                )
                RETURNING id,
                          source_document_id,
                          recovery_expected_document_number,
                          recovery_expected_net_amount,
                          recovery_expected_position_count,
                          recovery_mode,
                          recovery_expected_current_employee_external_id,
                          recovery_expected_original_sale_external_id,
                          recovery_expected_original_employee_external_id,
                          recovery_expected_original_links::text,
                          processing_status,
                          processing_attempt_count,
                          terminal_failure,
                          error_code,
                          recovery_requested_at,
                          processed_at
                """,
                this::mapRecovery,
                request.id(),
                request.eventId(),
                request.payload(),
                request.payloadSha256(),
                request.payloadSha256(),
                request.externalId(),
                Timestamp.from(request.requestedAt()),
                Timestamp.from(request.requestedAt()),
                request.requestedBy(),
                request.idempotencyKey(),
                request.documentNumber(),
                request.netAmount(),
                request.positionCount(),
                request.mode().name(),
                request.currentEmployeeExternalId(),
                request.originalSaleExternalId(),
                request.originalEmployeeExternalId(),
                originalLinksJson(request),
                request.reason(),
                Timestamp.from(request.requestedAt()),
                Timestamp.from(request.requestedAt())
        ).stream().findFirst().orElseThrow();
    }

    Optional<LiveSkladReturnRecoveryView> findRecoveryByRequesterAndKey(
            UUID requestedBy,
            String idempotencyKey
    ) {
        return recovery(
                """
                WHERE recovery_requested_by = ?
                  AND recovery_idempotency_key = ?
                """,
                requestedBy,
                idempotencyKey
        );
    }

    Optional<LiveSkladReturnRecoveryView> findRecoveryByExternalIdAndMode(
            String externalId,
            LiveSkladReturnRecoveryMode mode
    ) {
        return recovery(
                """
                WHERE recovery_requested_by IS NOT NULL
                  AND source_document_id = ?
                  AND recovery_mode = ?
                """,
                externalId,
                mode.name()
        );
    }

    Optional<LiveSkladReturnRecoveryView> findRecoveryById(UUID recoveryId) {
        return recovery(
                """
                WHERE recovery_requested_by IS NOT NULL
                  AND id = ?
                """,
                recoveryId
        );
    }

    private Optional<LiveSkladReturnRecoveryView> recovery(
            String predicate,
            Object... arguments
    ) {
        return jdbcTemplate.query(
                """
                SELECT id,
                       source_document_id,
                       recovery_expected_document_number,
                       recovery_expected_net_amount,
                       recovery_expected_position_count,
                       recovery_mode,
                       recovery_expected_current_employee_external_id,
                       recovery_expected_original_sale_external_id,
                       recovery_expected_original_employee_external_id,
                       recovery_expected_original_links::text,
                       processing_status,
                       processing_attempt_count,
                       terminal_failure,
                       error_code,
                       recovery_requested_at,
                       processed_at
                FROM livesklad_webhook_receipts
                """ + predicate,
                this::mapRecovery,
                arguments
        ).stream().findFirst();
    }

    private LiveSkladReturnRecoveryView mapRecovery(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        Timestamp processedAt = resultSet.getTimestamp("processed_at");
        return new LiveSkladReturnRecoveryView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source_document_id"),
                resultSet.getString("recovery_expected_document_number"),
                resultSet.getBigDecimal("recovery_expected_net_amount"),
                resultSet.getInt("recovery_expected_position_count"),
                LiveSkladReturnRecoveryMode.valueOf(
                        resultSet.getString("recovery_mode")
                ),
                resultSet.getString(
                        "recovery_expected_current_employee_external_id"
                ),
                resultSet.getString(
                        "recovery_expected_original_sale_external_id"
                ),
                resultSet.getString(
                        "recovery_expected_original_employee_external_id"
                ),
                originalLinks(resultSet),
                resultSet.getString("processing_status"),
                resultSet.getInt("processing_attempt_count"),
                resultSet.getBoolean("terminal_failure"),
                resultSet.getString("error_code"),
                resultSet.getTimestamp("recovery_requested_at").toInstant(),
                processedAt == null ? null : processedAt.toInstant()
        );
    }

    @Transactional
    Optional<LiveSkladWebhookClaim> claimNextSaleReturn(
            String workerId,
            Instant now,
            Duration leaseDuration,
            int maxAttempts
    ) {
        return claimNext(
                LiveSkladWebhookKind.SALE_RETURN,
                workerId,
                now,
                leaseDuration,
                maxAttempts
        );
    }

    @Transactional
    Optional<LiveSkladWebhookClaim> claimNextOrderReturn(
            String workerId,
            Instant now,
            Duration leaseDuration,
            int maxAttempts
    ) {
        return claimNext(
                LiveSkladWebhookKind.ORDER_RETURN,
                workerId,
                now,
                leaseDuration,
                maxAttempts
        );
    }

    private Optional<LiveSkladWebhookClaim> claimNext(
            LiveSkladWebhookKind kind,
            String workerId,
            Instant now,
            Duration leaseDuration,
            int maxAttempts
    ) {
        jdbcTemplate.update(
                """
                UPDATE livesklad_webhook_receipts
                SET processing_status = 'FAILED',
                    available_at = ?,
                    lease_owner = NULL,
                    lease_until = NULL,
                    terminal_failure =
                        processing_attempt_count >= ?,
                    error_code = CASE
                        WHEN processing_attempt_count >= ?
                            THEN 'RETRY_EXHAUSTED_LEASE_EXPIRED'
                        ELSE 'LEASE_EXPIRED'
                    END,
                    error_summary = 'Webhook processing lease expired'
                WHERE webhook_kind = ?
                  AND processing_status = 'PROCESSING'
                  AND lease_until < ?
                """,
                Timestamp.from(now),
                maxAttempts,
                maxAttempts,
                kind.name(),
                Timestamp.from(now)
        );
        List<LiveSkladWebhookClaim> claims = jdbcTemplate.query(
                """
                WITH candidate AS (
                    SELECT id
                    FROM livesklad_webhook_receipts
                    WHERE webhook_kind = ?
                      AND available_at <= ?
                      AND processing_attempt_count < ?
                      AND (
                          processing_status = 'RECEIVED'
                          OR (
                              processing_status = 'FAILED'
                              AND terminal_failure = false
                          )
                      )
                    ORDER BY available_at, first_received_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE livesklad_webhook_receipts receipt
                SET processing_status = 'PROCESSING',
                    processing_attempt_count =
                        receipt.processing_attempt_count + 1,
                    lease_owner = ?,
                    lease_until = ?,
                    error_code = NULL,
                    error_summary = NULL
                FROM candidate
                WHERE receipt.id = candidate.id
                RETURNING receipt.id,
                          receipt.event_id,
                          receipt.payload::text AS payload,
                          receipt.payload_mismatch,
                          receipt.processing_attempt_count,
                          receipt.source_document_id,
                          receipt.recovery_expected_document_number,
                          receipt.recovery_expected_net_amount,
                          receipt.recovery_expected_position_count,
                          receipt.recovery_mode,
                          receipt.recovery_expected_current_employee_external_id,
                          receipt.recovery_expected_original_sale_external_id,
                          receipt.recovery_expected_original_employee_external_id,
                          receipt.recovery_expected_original_links::text
                """,
                (resultSet, rowNumber) -> new LiveSkladWebhookClaim(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("event_id"),
                        resultSet.getString("payload"),
                        resultSet.getBoolean("payload_mismatch"),
                        resultSet.getInt("processing_attempt_count"),
                        resultSet.getString("source_document_id"),
                        resultSet.getString(
                                "recovery_expected_document_number"
                        ),
                        resultSet.getBigDecimal(
                                "recovery_expected_net_amount"
                        ),
                        resultSet.getObject(
                                "recovery_expected_position_count",
                                Integer.class
                        ),
                        recoveryMode(resultSet),
                        resultSet.getString(
                                "recovery_expected_current_employee_external_id"
                        ),
                        resultSet.getString(
                                "recovery_expected_original_sale_external_id"
                        ),
                        resultSet.getString(
                                "recovery_expected_original_employee_external_id"
                        ),
                        originalLinks(resultSet)
                ),
                kind.name(),
                Timestamp.from(now),
                maxAttempts,
                workerId,
                Timestamp.from(now.plus(leaseDuration))
        );
        return claims.stream().findFirst();
    }

    private String originalLinksJson(
            LiveSkladReturnRecoveryRequest request
    ) {
        if (request.mode() == LiveSkladReturnRecoveryMode.MISSING_RETURN) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(request.originalLinks());
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Return recovery link expectations cannot be serialized",
                    exception
            );
        }
    }

    private LiveSkladReturnRecoveryMode recoveryMode(
            ResultSet resultSet
    ) throws SQLException {
        String value = resultSet.getString("recovery_mode");
        return value == null ? null
                : LiveSkladReturnRecoveryMode.valueOf(value);
    }

    private List<RecoverLiveSkladReturnLinkExpectation> originalLinks(
            ResultSet resultSet
    ) throws SQLException {
        String value = resultSet.getString(
                "recovery_expected_original_links"
        );
        if (value == null) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root == null || !root.isArray()) {
                throw new SQLException(
                        "Return recovery link expectations are not an array"
                );
            }
            List<RecoverLiveSkladReturnLinkExpectation> result =
                    new ArrayList<>();
            for (JsonNode node : root) {
                result.add(canonicalLink(objectMapper.convertValue(
                        node, RecoverLiveSkladReturnLinkExpectation.class
                )));
            }
            return List.copyOf(result);
        } catch (JacksonException | IllegalArgumentException
                | ArithmeticException | NullPointerException exception) {
            throw new SQLException(
                    "Return recovery link expectations cannot be read",
                    exception
            );
        }
    }

    private RecoverLiveSkladReturnLinkExpectation canonicalLink(
            RecoverLiveSkladReturnLinkExpectation value
    ) {
        return new RecoverLiveSkladReturnLinkExpectation(
                value.returnPositionExternalId(),
                value.originalSalePositionExternalId(),
                value.productExternalId(),
                value.expectedQuantity().setScale(
                        3, RoundingMode.UNNECESSARY
                ),
                value.expectedNetAmount().setScale(
                        2, RoundingMode.UNNECESSARY
                ),
                value.expectedCostAmount() == null ? null
                        : value.expectedCostAmount().setScale(
                        2, RoundingMode.UNNECESSARY
                )
        );
    }

    void recordSourceDocument(
            UUID receiptId,
            String workerId,
            String sourceDocumentId
    ) {
        requireOwnedUpdate(jdbcTemplate.update(
                """
                UPDATE livesklad_webhook_receipts
                SET source_document_id = ?
                WHERE id = ?
                  AND processing_status = 'PROCESSING'
                  AND lease_owner = ?
                """,
                sourceDocumentId,
                receiptId,
                workerId
        ));
    }

    void complete(UUID receiptId, String workerId, Instant now) {
        requireOwnedUpdate(jdbcTemplate.update(
                """
                UPDATE livesklad_webhook_receipts
                SET processing_status = 'PROCESSED',
                    processed_at = ?,
                    lease_owner = NULL,
                    lease_until = NULL,
                    terminal_failure = false,
                    error_code = NULL,
                    error_summary = NULL
                WHERE id = ?
                  AND processing_status = 'PROCESSING'
                  AND lease_owner = ?
                """,
                Timestamp.from(now),
                receiptId,
                workerId
        ));
    }

    void retry(
            UUID receiptId,
            String workerId,
            Instant availableAt,
            String errorCode,
            String errorSummary
    ) {
        fail(receiptId, workerId, availableAt, false, errorCode, errorSummary);
    }

    void failPermanently(
            UUID receiptId,
            String workerId,
            Instant now,
            String errorCode,
            String errorSummary
    ) {
        fail(receiptId, workerId, now, true, errorCode, errorSummary);
    }

    private void fail(
            UUID receiptId,
            String workerId,
            Instant availableAt,
            boolean terminal,
            String errorCode,
            String errorSummary
    ) {
        requireOwnedUpdate(jdbcTemplate.update(
                """
                UPDATE livesklad_webhook_receipts
                SET processing_status = 'FAILED',
                    available_at = ?,
                    lease_owner = NULL,
                    lease_until = NULL,
                    terminal_failure = ?,
                    error_code = ?,
                    error_summary = ?
                WHERE id = ?
                  AND processing_status = 'PROCESSING'
                  AND lease_owner = ?
                """,
                Timestamp.from(availableAt),
                terminal,
                errorCode,
                errorSummary,
                receiptId,
                workerId
        ));
    }

    private void requireOwnedUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "LiveSklad webhook processing lease was lost"
            );
        }
    }
}
