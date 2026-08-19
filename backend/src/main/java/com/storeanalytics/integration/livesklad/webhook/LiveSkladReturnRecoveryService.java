package com.storeanalytics.integration.livesklad.webhook;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.common.idempotency.IdempotencyKeyConflictException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiveSkladReturnRecoveryService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{7,99}"
    );
    private static final Pattern EXTERNAL_ID = Pattern.compile("[0-9a-f]{24}");
    private static final Pattern DOCUMENT_NUMBER = Pattern.compile("F[0-9]{6}");

    private final LiveSkladWebhookStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AuditLogService auditLogService;

    public LiveSkladReturnRecoveryService(
            LiveSkladWebhookStore store,
            ObjectMapper objectMapper,
            Clock clock,
            AuditLogService auditLogService
    ) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public LiveSkladReturnRecoveryView request(
            UUID requestedBy,
            String idempotencyKey,
            String externalId,
            String documentNumber,
            BigDecimal netAmount,
            int positionCount,
            String reason
    ) {
        String key = validateIdempotencyKey(idempotencyKey);
        String validatedExternalId = validateExternalId(externalId);
        String validatedDocumentNumber = validateDocumentNumber(documentNumber);
        BigDecimal validatedAmount = validateAmount(netAmount);
        int validatedPositionCount = validatePositionCount(positionCount);
        String validatedReason = validateReason(reason);

        Optional<LiveSkladReturnRecoveryView> existing =
                store.findRecoveryByRequesterAndKey(requestedBy, key);
        if (existing.isPresent()) {
            return matching(
                    existing.orElseThrow(),
                    validatedExternalId,
                    validatedDocumentNumber,
                    validatedAmount,
                    validatedPositionCount
            );
        }

        store.lockRecoveryCreation();
        existing = store.findRecoveryByRequesterAndKey(requestedBy, key);
        if (existing.isPresent()) {
            return matching(
                    existing.orElseThrow(),
                    validatedExternalId,
                    validatedDocumentNumber,
                    validatedAmount,
                    validatedPositionCount
            );
        }
        if (store.findRecoveryByExternalId(validatedExternalId).isPresent()) {
            throw new IdempotencyKeyConflictException();
        }

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String eventId = "manual-recovery-" + id;
        String payload = payload(eventId, validatedExternalId);
        LiveSkladReturnRecoveryView result = store.createRecovery(
                new LiveSkladReturnRecoveryRequest(
                        id,
                        requestedBy,
                        key,
                        validatedExternalId,
                        validatedDocumentNumber,
                        validatedAmount,
                        validatedPositionCount,
                        validatedReason,
                        eventId,
                        payload,
                        LiveSkladWebhookService.sha256(payload),
                        now
                )
        );
        auditLogService.record(
                requestedBy,
                null,
                AuditAction.RETURN_RECOVERY_REQUESTED,
                new AuditTarget(AuditEntityType.RETURN_DOCUMENT, id),
                validatedReason,
                null,
                Map.of(
                        "externalId", validatedExternalId,
                        "documentNumber", validatedDocumentNumber,
                        "netAmount", validatedAmount,
                        "positionCount", validatedPositionCount
                )
        );
        return result;
    }

    @Transactional(readOnly = true)
    public LiveSkladReturnRecoveryView get(UUID recoveryId) {
        return store.findRecoveryById(recoveryId)
                .orElseThrow(() ->
                        new LiveSkladReturnRecoveryNotFoundException(recoveryId));
    }

    private LiveSkladReturnRecoveryView matching(
            LiveSkladReturnRecoveryView existing,
            String externalId,
            String documentNumber,
            BigDecimal netAmount,
            int positionCount
    ) {
        if (!existing.externalId().equals(externalId)
                || !existing.expectedDocumentNumber().equals(documentNumber)
                || existing.expectedNetAmount().compareTo(netAmount) != 0
                || existing.expectedPositionCount() != positionCount) {
            throw new IdempotencyKeyConflictException();
        }
        return existing;
    }

    private String validateIdempotencyKey(String value) {
        String key = value == null ? "" : value.trim();
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new InvalidRequestException(
                    "Idempotency-Key must contain 8 to 100 safe characters"
            );
        }
        return key;
    }

    private String validateExternalId(String value) {
        String externalId = value == null ? "" : value.trim();
        if (!EXTERNAL_ID.matcher(externalId).matches()) {
            throw new InvalidRequestException(
                    "LiveSklad return externalId must contain 24 lowercase hex characters"
            );
        }
        return externalId;
    }

    private String validateDocumentNumber(String value) {
        String documentNumber = value == null ? "" : value.trim();
        if (!DOCUMENT_NUMBER.matcher(documentNumber).matches()) {
            throw new InvalidRequestException(
                    "LiveSklad return document number must match F followed by six digits"
            );
        }
        return documentNumber;
    }

    private BigDecimal validateAmount(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new InvalidRequestException(
                    "Expected return amount must be positive"
            );
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new InvalidRequestException(
                    "Expected return amount must have at most two decimals",
                    exception
            );
        }
    }

    private int validatePositionCount(int value) {
        if (value < 1 || value > 10_000) {
            throw new InvalidRequestException(
                    "Expected return position count must be between 1 and 10000"
            );
        }
        return value;
    }

    private String validateReason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.isEmpty() || reason.length() > 500) {
            throw new InvalidRequestException(
                    "Recovery reason must contain 1 to 500 characters"
            );
        }
        return reason;
    }

    private String payload(String eventId, String externalId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId);
        payload.putObject("data").put("id", externalId);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Manual recovery payload cannot be serialized",
                    exception
            );
        }
    }
}
