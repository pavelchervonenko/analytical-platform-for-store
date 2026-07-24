package com.storeanalytics.audit.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeanalytics.audit.model.AuditLog;
import com.storeanalytics.audit.repository.AuditLogRepository;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.store.model.Store;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private static final int MAX_SUMMARY_FIELDS = 100;
    private static final int MAX_COLLECTION_ITEMS = 500;
    private static final int MAX_TEXT_LENGTH = 2_000;
    private static final int MAX_METADATA_LENGTH = 32_000;
    private static final List<String> SENSITIVE_KEY_PARTS = List.of(
            "password", "secret", "token", "authorization", "cookie", "credential"
    );

    private final AuditLogRepository repository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public AuditLogService(
            AuditLogRepository repository,
            EntityManager entityManager,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(
            UUID actorUserId,
            UUID storeId,
            AuditAction action,
            AuditTarget target,
            String reason,
            Map<String, ?> before,
            Map<String, ?> after
    ) {
        UUID actorId = requireNonNull(actorUserId, "actorUserId");
        AuditTarget validatedTarget = requireNonNull(target, "target");
        AppUser actor = entityManager.getReference(AppUser.class, actorId);
        Store store = storeId == null
                ? null : entityManager.getReference(Store.class, storeId);
        repository.save(new AuditLog(
                actor,
                store,
                requireNonNull(action, "action").name(),
                requireText(validatedTarget.entityType(), "entityType"),
                requireNonNull(validatedTarget.entityId(), "entityId").toString(),
                metadata(reason, before, after)
        ));
    }

    @Transactional
    public void recordSystem(
            UUID storeId,
            AuditAction action,
            AuditTarget target,
            String reason,
            Map<String, ?> before,
            Map<String, ?> after
    ) {
        AuditTarget validatedTarget = requireNonNull(target, "target");
        Store store = storeId == null
                ? null : entityManager.getReference(Store.class, storeId);
        repository.save(new AuditLog(
                null,
                store,
                requireNonNull(action, "action").name(),
                requireText(validatedTarget.entityType(), "entityType"),
                requireNonNull(validatedTarget.entityId(), "entityId").toString(),
                metadata(reason, before, after)
        ));
    }

    private String metadata(
            String reason,
            Map<String, ?> before,
            Map<String, ?> after
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", 1);
        if (reason != null && !reason.isBlank()) {
            metadata.put("reason", safeText(reason));
        }
        if (before != null) {
            metadata.put("before", sanitizeMap(before));
        }
        if (after != null) {
            metadata.put("after", sanitizeMap(after));
        }
        try {
            String json = objectMapper.writeValueAsString(metadata);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_LENGTH) {
                throw new IllegalArgumentException("audit metadata is too large");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("audit metadata cannot be serialized", exception);
        }
    }

    private Map<String, Object> sanitizeMap(Map<String, ?> source) {
        if (source.size() > MAX_SUMMARY_FIELDS) {
            throw new IllegalArgumentException("audit summary contains too many fields");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String validatedKey = requireText(key, "audit summary key");
            result.put(
                    validatedKey,
                    isSensitive(validatedKey) ? "[REDACTED]" : sanitizeValue(value)
            );
        });
        return result;
    }

    private Object sanitizeValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence text) {
            return safeText(text.toString());
        }
        if (value instanceof UUID || value instanceof Enum<?> || value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> typed.put(String.valueOf(key), nestedValue));
            return sanitizeMap(typed);
        }
        if (value instanceof Collection<?> collection) {
            if (collection.size() > MAX_COLLECTION_ITEMS) {
                throw new IllegalArgumentException("audit summary collection is too large");
            }
            return collection.stream().map(this::sanitizeValue).toList();
        }
        throw new IllegalArgumentException(
                "unsupported audit summary value type: " + value.getClass().getName()
        );
    }

    private boolean isSensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    private String safeText(String value) {
        return value.length() <= MAX_TEXT_LENGTH
                ? value : value.substring(0, MAX_TEXT_LENGTH);
    }
}
