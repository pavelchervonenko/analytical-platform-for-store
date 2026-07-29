package com.storeanalytics.common.security;

import com.storeanalytics.common.observability.SiemAuditEvent;
import java.util.Set;

enum SecurityEventType {
    BOOTSTRAP_ADMIN_CREATED(
            "bootstrap_admin_created",
            SiemAuditEvent.Outcome.SUCCESS,
            SiemAuditEvent.Severity.WARN,
            Set.of("user_ref")
    ),
    BOOTSTRAP_ADMIN_SKIPPED(
            "bootstrap_admin_skipped",
            SiemAuditEvent.Outcome.UNKNOWN,
            SiemAuditEvent.Severity.WARN,
            Set.of("reason")
    ),
    BREAK_GLASS_LOGIN_SUCCEEDED(
            "break_glass_login_succeeded",
            SiemAuditEvent.Outcome.SUCCESS,
            SiemAuditEvent.Severity.WARN,
            Set.of("user_ref", "client_ref")
    ),
    LOGIN_FAILED(
            "login_failed",
            SiemAuditEvent.Outcome.FAILURE,
            SiemAuditEvent.Severity.WARN,
            Set.of("email_ref", "client_ref")
    ),
    LOGIN_SUCCEEDED(
            "login_succeeded",
            SiemAuditEvent.Outcome.SUCCESS,
            SiemAuditEvent.Severity.INFO,
            Set.of("user_ref", "client_ref")
    ),
    LOGIN_THROTTLED(
            "login_throttled",
            SiemAuditEvent.Outcome.FAILURE,
            SiemAuditEvent.Severity.WARN,
            Set.of("email_ref", "client_ref")
    ),
    AUTHENTICATION_REQUIRED(
            "authentication_required",
            SiemAuditEvent.Outcome.FAILURE,
            SiemAuditEvent.Severity.WARN,
            Set.of("client_ref")
    ),
    ACCESS_DENIED(
            "access_denied",
            SiemAuditEvent.Outcome.FAILURE,
            SiemAuditEvent.Severity.WARN,
            Set.of("client_ref"),
            Set.of("user_ref")
    ),
    CSRF_REJECTED(
            "csrf_rejected",
            SiemAuditEvent.Outcome.FAILURE,
            SiemAuditEvent.Severity.WARN,
            Set.of("client_ref"),
            Set.of("user_ref")
    ),
    SESSION_REJECTED(
            "session_rejected",
            SiemAuditEvent.Outcome.FAILURE,
            SiemAuditEvent.Severity.WARN,
            Set.of("user_ref", "reason")
    ),
    SESSION_EXPIRED(
            "session_expired",
            SiemAuditEvent.Outcome.FAILURE,
            SiemAuditEvent.Severity.WARN,
            Set.of("client_ref")
    ),
    SESSIONS_REVOKED(
            "sessions_revoked",
            SiemAuditEvent.Outcome.SUCCESS,
            SiemAuditEvent.Severity.INFO,
            Set.of("user_ref", "scope", "count")
    ),
    PASSWORD_CHANGED(
            "password_changed",
            SiemAuditEvent.Outcome.SUCCESS,
            SiemAuditEvent.Severity.INFO,
            Set.of("user_ref")
    ),
    USER_ADMINISTRATION(
            "user_administration",
            SiemAuditEvent.Outcome.SUCCESS,
            SiemAuditEvent.Severity.INFO,
            Set.of("action", "actor_ref", "subject_ref")
    );

    private final String tag;
    private final SiemAuditEvent.Definition definition;

    SecurityEventType(
            String tag,
            SiemAuditEvent.Outcome outcome,
            SiemAuditEvent.Severity severity,
            Set<String> requiredFields
    ) {
        this(tag, outcome, severity, requiredFields, Set.of());
    }

    SecurityEventType(
            String tag,
            SiemAuditEvent.Outcome outcome,
            SiemAuditEvent.Severity severity,
            Set<String> requiredFields,
            Set<String> optionalFields
    ) {
        this.tag = tag;
        this.definition = new SiemAuditEvent.Definition(
                SiemAuditEvent.Category.SECURITY,
                tag,
                outcome,
                severity,
                requiredFields,
                optionalFields
        );
    }

    String tag() {
        return tag;
    }

    SiemAuditEvent.Definition definition() {
        return definition;
    }
}
