package com.storeanalytics.notification.operations;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.common.idempotency.IdempotencyRequest;
import com.storeanalytics.common.idempotency.IdempotencyService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManualTelegramResendService {

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public ManualTelegramResendService(
            JdbcTemplate jdbcTemplate,
            IdempotencyService idempotencyService,
            AuditLogService auditLogService,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional
    public ManualTelegramResendView resend(
            UUID deliveryId,
            UUID actorId,
            String idempotencyKey,
            ManualTelegramResendRequest request
    ) {
        ValidatedRequest validated = validate(request);
        return idempotencyService.execute(
                actorId,
                idempotencyKey,
                new IdempotencyRequest(
                        "TELEGRAM_DELIVERY_RESEND",
                        "notification-delivery/" + deliveryId,
                        validated
                ),
                ManualTelegramResendView.class,
                () -> resendOnce(deliveryId, actorId, validated)
        );
    }

    private ManualTelegramResendView resendOnce(
            UUID deliveryId,
            UUID actorId,
            ValidatedRequest request
    ) {
        ResendSource source = source(deliveryId);
        Instant now = clock.instant();
        requireEligible(source, now);
        if (hasSuccessfulOrActiveChild(source.id())) {
            throw new TelegramDeliveryResendConflictException(
                    "Delivery already has an active or successful manual resend"
            );
        }

        UUID resendId = UUID.randomUUID();
        try {
            int inserted = jdbcTemplate.update(
                    """
                    INSERT INTO notification_deliveries (
                        id, event_id, delivery_kind, channel,
                        recipient_user_id, subscription_id, status,
                        render_version, rendered_text, rendered_markup, content_hash,
                        scheduled_at, next_attempt_at, expires_at,
                        attempt_count, max_attempts,
                        manual_resend_of, requested_by, resend_reason
                    )
                    SELECT ?, event_id, delivery_kind, channel,
                           recipient_user_id, subscription_id, 'PENDING',
                           render_version, rendered_text, rendered_markup, content_hash,
                           ?, ?, expires_at, 0, max_attempts, ?, ?, ?
                    FROM notification_deliveries
                    WHERE id = ?
                    """,
                    resendId,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    source.id(),
                    actorId,
                    request.reason(),
                    source.id()
            );
            if (inserted != 1) {
                throw new TelegramDeliveryNotFoundException(source.id());
            }
        } catch (DataIntegrityViolationException exception) {
            throw new TelegramDeliveryResendConflictException(
                    "Delivery eligibility changed while manual resend was created",
                    exception
            );
        }

        auditLogService.record(
                actorId,
                source.storeId(),
                AuditAction.TELEGRAM_DELIVERY_RESEND_REQUESTED,
                new AuditTarget(AuditEntityType.NOTIFICATION_DELIVERY, resendId),
                request.reason(),
                Map.of(
                        "sourceDeliveryId", source.id(),
                        "sourceStatus", source.status(),
                        "sourceErrorCode", source.errorCode() == null
                                ? "NONE" : source.errorCode()
                ),
                Map.of(
                        "deliveryId", resendId,
                        "status", "PENDING",
                        "duplicateRiskAccepted", true,
                        "expiresAt", source.expiresAt()
                )
        );
        return new ManualTelegramResendView(
                resendId,
                source.id(),
                "PENDING",
                now,
                source.expiresAt()
        );
    }

    private ResendSource source(UUID deliveryId) {
        List<ResendSource> rows = jdbcTemplate.query(
                """
                SELECT delivery.id, delivery.delivery_kind, delivery.status,
                       delivery.error_code, delivery.expires_at, event.store_id
                FROM notification_deliveries delivery
                LEFT JOIN notification_events event ON event.id = delivery.event_id
                WHERE delivery.id = ?
                FOR UPDATE OF delivery
                """,
                this::mapSource,
                deliveryId
        );
        if (rows.isEmpty()) {
            throw new TelegramDeliveryNotFoundException(deliveryId);
        }
        return rows.getFirst();
    }

    private boolean hasSuccessfulOrActiveChild(UUID sourceId) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM notification_deliveries
                    WHERE manual_resend_of = ?
                      AND status IN ('PENDING', 'RUNNING', 'WAITING_RETRY', 'SENT')
                )
                """,
                Boolean.class,
                sourceId
        );
        return Boolean.TRUE.equals(exists);
    }

    private void requireEligible(ResendSource source, Instant now) {
        if (!"NOTIFICATION".equals(source.deliveryKind())) {
            throw new TelegramDeliveryResendConflictException(
                    "Service link confirmations cannot be manually resent"
            );
        }
        if (!"PERMANENT_FAILED".equals(source.status())
                && !"UNKNOWN_OUTCOME".equals(source.status())) {
            throw new TelegramDeliveryResendConflictException(
                    "Only permanent or unknown delivery outcomes can be manually resent"
            );
        }
        if (!now.isBefore(source.expiresAt())) {
            throw new TelegramDeliveryResendConflictException(
                    "Expired notification content cannot be manually resent"
            );
        }
    }

    private ValidatedRequest validate(ManualTelegramResendRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.acknowledgeDuplicateRisk())) {
            throw new InvalidRequestException(
                    "Manual resend requires explicit duplicate-risk acknowledgement"
            );
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < 10 || reason.length() > 500) {
            throw new InvalidRequestException(
                    "Manual resend reason must contain 10 to 500 characters"
            );
        }
        return new ValidatedRequest(reason, true);
    }

    private ResendSource mapSource(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new ResendSource(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("delivery_kind"),
                resultSet.getString("status"),
                resultSet.getString("error_code"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getObject("store_id", UUID.class)
        );
    }

    private record ValidatedRequest(String reason, boolean acknowledgeDuplicateRisk) {
    }

    private record ResendSource(
            UUID id,
            String deliveryKind,
            String status,
            String errorCode,
            Instant expiresAt,
            UUID storeId
    ) {
    }
}
