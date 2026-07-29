package com.storeanalytics.common.security;

import com.storeanalytics.common.observability.SiemAuditEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SecurityAuditLogger {

    static final String EVENT_METRIC = "storeanalytics.security.events";
    private static final Logger LOGGER = LoggerFactory.getLogger("SECURITY_AUDIT");

    private final SecurityPseudonymizer pseudonymizer;
    private final Map<SecurityEventType, Counter> counters;

    public SecurityAuditLogger(
            SecurityPseudonymizer pseudonymizer,
            MeterRegistry meterRegistry
    ) {
        this.pseudonymizer = pseudonymizer;
        this.counters = new EnumMap<>(SecurityEventType.class);
        for (SecurityEventType type : SecurityEventType.values()) {
            counters.put(type, Counter.builder(EVENT_METRIC)
                    .description("Security-relevant application events")
                    .tag("type", type.tag())
                    .register(meterRegistry));
        }
    }

    public void loginFailed(String email, ClientAddress clientAddress) {
        commit(SecurityEventType.LOGIN_FAILED, Map.of(
                "email_ref", pseudonymizer.reference(
                        "email",
                        normalizeEmail(email)
                ),
                "client_ref", clientReference(clientAddress)
        ));
    }

    public void loginSucceeded(UUID userId, ClientAddress clientAddress) {
        commit(SecurityEventType.LOGIN_SUCCEEDED, Map.of(
                "user_ref", userReference(userId),
                "client_ref", clientReference(clientAddress)
        ));
    }

    public void bootstrapAdministratorCreated(UUID userId) {
        commit(SecurityEventType.BOOTSTRAP_ADMIN_CREATED, Map.of(
                "user_ref", userReference(userId)
        ));
    }

    public void bootstrapAdministratorSkipped() {
        commit(SecurityEventType.BOOTSTRAP_ADMIN_SKIPPED, Map.of(
                "reason", "users_exist"
        ));
    }

    public void breakGlassLoginSucceeded(
            UUID userId,
            ClientAddress clientAddress
    ) {
        commit(SecurityEventType.BREAK_GLASS_LOGIN_SUCCEEDED, Map.of(
                "user_ref", userReference(userId),
                "client_ref", clientReference(clientAddress)
        ));
    }

    public void loginThrottled(String email, ClientAddress clientAddress) {
        commit(SecurityEventType.LOGIN_THROTTLED, Map.of(
                "email_ref", pseudonymizer.reference(
                        "email",
                        normalizeEmail(email)
                ),
                "client_ref", clientReference(clientAddress)
        ));
    }

    public void authenticationRequired(ClientAddress clientAddress) {
        commit(SecurityEventType.AUTHENTICATION_REQUIRED, Map.of(
                "client_ref", clientReference(clientAddress)
        ));
    }

    public void accessDenied(UUID userId, ClientAddress clientAddress) {
        commit(
                SecurityEventType.ACCESS_DENIED,
                fieldsWithOptionalUser(userId, clientAddress)
        );
    }

    public void csrfRejected(UUID userId, ClientAddress clientAddress) {
        commit(
                SecurityEventType.CSRF_REJECTED,
                fieldsWithOptionalUser(userId, clientAddress)
        );
    }

    public void passwordChanged(UUID userId) {
        commit(SecurityEventType.PASSWORD_CHANGED, Map.of(
                "user_ref", userReference(userId)
        ));
    }

    public void sessionRejected(UUID userId, String reason) {
        commit(SecurityEventType.SESSION_REJECTED, Map.of(
                "user_ref", userReference(userId),
                "reason", safeSessionReason(reason)
        ));
    }

    public void sessionExpired(ClientAddress clientAddress) {
        commit(SecurityEventType.SESSION_EXPIRED, Map.of(
                "client_ref", clientReference(clientAddress)
        ));
    }

    public void sessionsRevoked(UUID userId, String scope, int count) {
        if (count < 1) {
            throw new IllegalArgumentException(
                    "revoked session count must be positive"
            );
        }
        commit(SecurityEventType.SESSIONS_REVOKED, Map.of(
                "user_ref", userReference(userId),
                "scope", safeSessionRevocationScope(scope),
                "count", count
        ));
    }

    public void userAdministration(
            String action,
            UUID actorId,
            UUID subjectId
    ) {
        commit(SecurityEventType.USER_ADMINISTRATION, Map.of(
                "action", safeAdministrationAction(action),
                "actor_ref", userReference(actorId),
                "subject_ref", userReference(subjectId)
        ));
    }

    private Map<String, Object> fieldsWithOptionalUser(
            UUID userId,
            ClientAddress clientAddress
    ) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("client_ref", clientReference(clientAddress));
        if (userId != null) {
            fields.put("user_ref", userReference(userId));
        }
        return fields;
    }

    private void commit(
            SecurityEventType type,
            Map<String, ?> fields
    ) {
        SiemAuditEvent.Event event = type.definition().create(
                pseudonymizer.keyId(),
                fields
        );
        counters.get(type).increment();
        event.log(LOGGER, "Security event");
    }

    private String userReference(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        return pseudonymizer.reference("user", userId.toString());
    }

    private String clientReference(ClientAddress clientAddress) {
        if (clientAddress == null) {
            throw new IllegalArgumentException("clientAddress is required");
        }
        return pseudonymizer.reference(
                "client",
                clientAddress.canonicalAddress()
        );
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String safeSessionReason(String reason) {
        return switch (reason) {
            case "absolute_timeout" -> "absolute_timeout";
            case "user_missing" -> "user_missing";
            case "user_disabled" -> "user_disabled";
            case "security_version_changed" -> "security_version_changed";
            default -> "unknown";
        };
    }

    private String safeSessionRevocationScope(String scope) {
        return switch (scope) {
            case "single" -> "single";
            case "all_other" -> "all_other";
            default -> "unknown";
        };
    }

    private String safeAdministrationAction(String action) {
        return switch (action) {
            case "create" -> "create";
            case "update" -> "update";
            case "replace_store_access" -> "replace_store_access";
            case "reset_password" -> "reset_password";
            default -> "unknown";
        };
    }
}
