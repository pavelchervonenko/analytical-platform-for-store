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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        return request(
                requestedBy,
                idempotencyKey,
                new RecoverLiveSkladReturnRequest(
                        externalId,
                        documentNumber,
                        netAmount,
                        positionCount,
                        LiveSkladReturnRecoveryMode.MISSING_RETURN,
                        null,
                        null,
                        null,
                        List.of(),
                        reason
                )
        );
    }

    @Transactional
    public LiveSkladReturnRecoveryView request(
            UUID requestedBy,
            String idempotencyKey,
            RecoverLiveSkladReturnRequest request
    ) {
        String key = validateIdempotencyKey(idempotencyKey);
        String validatedExternalId = validateExternalId(request.externalId());
        String validatedDocumentNumber = validateDocumentNumber(
                request.expectedDocumentNumber()
        );
        BigDecimal validatedAmount = validateAmount(
                request.expectedNetAmount()
        );
        int validatedPositionCount = validatePositionCount(
                request.expectedPositionCount()
        );
        LiveSkladReturnRecoveryMode validatedMode = request.mode() == null
                ? LiveSkladReturnRecoveryMode.MISSING_RETURN : request.mode();
        OriginalExpectations original = validateOriginalExpectations(
                validatedMode,
                request.expectedCurrentEmployeeExternalId(),
                request.expectedOriginalSaleExternalId(),
                request.expectedOriginalEmployeeExternalId(),
                request.expectedOriginalLinks(),
                validatedPositionCount
        );
        String validatedReason = validateReason(request.reason());

        Optional<LiveSkladReturnRecoveryView> existing =
                store.findRecoveryByRequesterAndKey(requestedBy, key);
        if (existing.isPresent()) {
            return matching(
                    existing.orElseThrow(),
                    validatedExternalId,
                    validatedDocumentNumber,
                    validatedAmount,
                    validatedPositionCount,
                    validatedMode,
                    original
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
                    validatedPositionCount,
                    validatedMode,
                    original
            );
        }
        if (store.findRecoveryByExternalIdAndMode(
                validatedExternalId,
                validatedMode
        ).isPresent()) {
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
                        validatedMode,
                        original.currentEmployeeExternalId(),
                        original.saleExternalId(),
                        original.employeeExternalId(),
                        original.links(),
                        validatedReason,
                        eventId,
                        payload,
                        LiveSkladWebhookService.sha256(payload),
                        now
                )
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("externalId", validatedExternalId);
        metadata.put("documentNumber", validatedDocumentNumber);
        metadata.put("netAmount", validatedAmount);
        metadata.put("positionCount", validatedPositionCount);
        metadata.put("mode", validatedMode.name());
        if (original.currentEmployeeExternalId() != null) {
            metadata.put(
                    "currentEmployeeExternalId",
                    original.currentEmployeeExternalId()
            );
        }
        if (original.saleExternalId() != null) {
            metadata.put(
                    "originalSaleExternalId", original.saleExternalId()
            );
            metadata.put(
                    "originalEmployeeExternalId",
                    original.employeeExternalId()
            );
            metadata.put("originalLinkCount", original.links().size());
        }
        auditLogService.record(
                requestedBy,
                null,
                AuditAction.RETURN_RECOVERY_REQUESTED,
                new AuditTarget(AuditEntityType.RETURN_DOCUMENT, id),
                validatedReason,
                null,
                Map.copyOf(metadata)
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
            int positionCount,
            LiveSkladReturnRecoveryMode mode,
            OriginalExpectations original
    ) {
        if (!existing.externalId().equals(externalId)
                || !existing.expectedDocumentNumber().equals(documentNumber)
                || existing.expectedNetAmount().compareTo(netAmount) != 0
                || existing.expectedPositionCount() != positionCount
                || existing.mode() != mode
                || !java.util.Objects.equals(
                existing.expectedCurrentEmployeeExternalId(),
                original.currentEmployeeExternalId())
                || !java.util.Objects.equals(
                existing.expectedOriginalSaleExternalId(),
                original.saleExternalId())
                || !java.util.Objects.equals(
                existing.expectedOriginalEmployeeExternalId(),
                original.employeeExternalId())
                || !existing.expectedOriginalLinks().equals(
                original.links())) {
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
        return validateExternalId(value, "LiveSklad return externalId");
    }

    private String validateExternalId(String value, String label) {
        String externalId = value == null ? "" : value.trim();
        if (!EXTERNAL_ID.matcher(externalId).matches()) {
            throw new InvalidRequestException(
                    label + " must contain 24 lowercase hex characters"
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
        if (value == null || value.signum() < 0) {
            throw new InvalidRequestException(
                    "Expected return amount must not be negative"
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

    private OriginalExpectations validateOriginalExpectations(
            LiveSkladReturnRecoveryMode mode,
            String currentEmployeeExternalId,
            String saleExternalId,
            String employeeExternalId,
            List<RecoverLiveSkladReturnLinkExpectation> links,
            int positionCount
    ) {
        List<RecoverLiveSkladReturnLinkExpectation> suppliedLinks =
                links == null ? List.of() : links;
        if (mode == LiveSkladReturnRecoveryMode.MISSING_RETURN) {
            if (currentEmployeeExternalId != null
                    || hasText(saleExternalId)
                    || hasText(employeeExternalId)
                    || !suppliedLinks.isEmpty()) {
                throw new InvalidRequestException(
                        "Original-link expectations require EXISTING_ORPHAN_RELINK mode"
                );
            }
            return new OriginalExpectations(null, null, null, List.of());
        }
        String validatedCurrentEmployee = currentEmployeeExternalId == null
                ? null
                : validateExternalId(
                        currentEmployeeExternalId,
                        "Expected current return employee externalId"
                );
        String validatedSale = validateExternalId(
                saleExternalId,
                "Expected original sale externalId"
        );
        String validatedEmployee = validateExternalId(
                employeeExternalId,
                "Expected original employee externalId"
        );
        if (suppliedLinks.size() != positionCount) {
            throw new InvalidRequestException(
                    "Expected original links must match expected position count"
            );
        }
        Set<String> returnPositionIds = new HashSet<>();
        Set<String> originalPositionIds = new HashSet<>();
        List<RecoverLiveSkladReturnLinkExpectation> validatedLinks =
                new ArrayList<>();
        for (RecoverLiveSkladReturnLinkExpectation link : suppliedLinks) {
            if (link == null) {
                throw new InvalidRequestException(
                        "Expected original link cannot be null"
                );
            }
            String returnPositionId = validateExternalId(
                    link.returnPositionExternalId(),
                    "Expected return position externalId"
            );
            String originalPositionId = validateExternalId(
                    link.originalSalePositionExternalId(),
                    "Expected original sale position externalId"
            );
            String productId = validateExternalId(
                    link.productExternalId(),
                    "Expected product externalId"
            );
            if (!returnPositionIds.add(returnPositionId)
                    || !originalPositionIds.add(originalPositionId)) {
                throw new InvalidRequestException(
                        "Expected original links must be one-to-one"
                );
            }
            validatedLinks.add(new RecoverLiveSkladReturnLinkExpectation(
                    returnPositionId,
                    originalPositionId,
                    productId,
                    validateQuantity(link.expectedQuantity()),
                    validateNonNegativeAmount(
                            link.expectedNetAmount(),
                            "Expected position net amount",
                            false
                    ),
                    validateNonNegativeAmount(
                            link.expectedCostAmount(),
                            "Expected position cost amount",
                            true
                    )
            ));
        }
        validatedLinks.sort(Comparator.comparing(
                RecoverLiveSkladReturnLinkExpectation::returnPositionExternalId
        ));
        return new OriginalExpectations(
                validatedCurrentEmployee,
                validatedSale,
                validatedEmployee,
                List.copyOf(validatedLinks)
        );
    }

    private BigDecimal validateQuantity(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new InvalidRequestException(
                    "Expected position quantity must be positive"
            );
        }
        return scaled(value, 3, "Expected position quantity");
    }

    private BigDecimal validateNonNegativeAmount(
            BigDecimal value,
            String label,
            boolean nullable
    ) {
        if (value == null) {
            if (nullable) {
                return null;
            }
            throw new InvalidRequestException(label + " is required");
        }
        if (value.signum() < 0) {
            throw new InvalidRequestException(label + " must not be negative");
        }
        return scaled(value, 2, label);
    }

    private BigDecimal scaled(BigDecimal value, int scale, String label) {
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new InvalidRequestException(
                    label + " has unsupported precision",
                    exception
            );
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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

    private record OriginalExpectations(
            String currentEmployeeExternalId,
            String saleExternalId,
            String employeeExternalId,
            List<RecoverLiveSkladReturnLinkExpectation> links
    ) {
    }
}
