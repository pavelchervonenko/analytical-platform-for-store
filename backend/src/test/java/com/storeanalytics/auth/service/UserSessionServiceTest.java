package com.storeanalytics.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.auth.exception.CurrentSessionRequiresLogoutException;
import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.common.config.ApplicationSecurityProperties;
import com.storeanalytics.common.config.SecurityTelemetryProperties;
import com.storeanalytics.common.security.SecurityAuditLogger;
import com.storeanalytics.common.security.SecurityPseudonymizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;

class UserSessionServiceTest {

    @Test
    void exposesOnlyOpaqueReferencesAndRevokesAnotherSession() {
        SessionRegistry registry = new SessionRegistryImpl();
        SecurityAuditLogger auditLogger = mock(SecurityAuditLogger.class);
        UserSessionService service = service(registry, auditLogger, 3);
        AppUserPrincipal principal = principal();
        service.registerSession(principal, "raw-session-one");
        service.registerSession(principal, "raw-session-two");

        List<ActiveSessionView> sessions = service.listSessions(
                principal,
                "raw-session-two"
        );

        assertThat(sessions).hasSize(2);
        assertThat(sessions.getFirst().current()).isTrue();
        assertThat(sessions)
                .extracting(ActiveSessionView::sessionReference)
                .allMatch(reference -> reference.matches("h1_[0-9a-f]{24}"))
                .noneMatch(reference -> reference.contains("raw-session"));
        String otherReference = sessions.stream()
                .filter(session -> !session.current())
                .findFirst()
                .orElseThrow()
                .sessionReference();

        service.revokeSession(principal, "raw-session-two", otherReference);

        assertThat(registry.getAllSessions(principal, false))
                .extracting(session -> session.getSessionId())
                .containsExactly("raw-session-two");
        verify(auditLogger).sessionsRevoked(principal.getUserId(), "single", 1);
    }

    @Test
    void unknownReferenceIsIdempotentAndCurrentReferenceRequiresLogout() {
        SessionRegistry registry = new SessionRegistryImpl();
        SecurityAuditLogger auditLogger = mock(SecurityAuditLogger.class);
        UserSessionService service = service(registry, auditLogger, 3);
        AppUserPrincipal principal = principal();
        service.registerSession(principal, "current-session");
        String currentReference = service.listSessions(
                principal,
                "current-session"
        ).getFirst().sessionReference();

        service.revokeSession(
                principal,
                "current-session",
                "h1_000000000000000000000000"
        );
        service.revokeSession(principal, "current-session", "invalid-reference");

        verify(auditLogger, never()).sessionsRevoked(
                principal.getUserId(),
                "single",
                1
        );
        assertThatThrownBy(() -> service.revokeSession(
                principal,
                "current-session",
                currentReference
        ))
                .isInstanceOf(CurrentSessionRequiresLogoutException.class)
                .hasMessageContaining("logout endpoint");
        assertThat(registry.getAllSessions(principal, false)).hasSize(1);
    }

    @Test
    void revokesAllOtherSessionsButPreservesCurrentSession() {
        SessionRegistry registry = new SessionRegistryImpl();
        SecurityAuditLogger auditLogger = mock(SecurityAuditLogger.class);
        UserSessionService service = service(registry, auditLogger, 3);
        AppUserPrincipal principal = principal();
        service.registerSession(principal, "session-one");
        service.registerSession(principal, "session-two");
        service.registerSession(principal, "session-three");

        service.revokeOtherSessions(principal, "session-two");

        assertThat(registry.getAllSessions(principal, false))
                .extracting(session -> session.getSessionId())
                .containsExactly("session-two");
        verify(auditLogger).sessionsRevoked(
                principal.getUserId(),
                "all_other",
                2
        );
    }

    @Test
    void concurrentRegistrationCannotExceedConfiguredSessionLimit()
            throws Exception {
        SessionRegistry registry = new SessionRegistryImpl();
        UserSessionService service = service(
                registry,
                mock(SecurityAuditLogger.class),
                3
        );
        AppUserPrincipal principal = principal();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> registrations = new ArrayList<>();
        try {
            for (int index = 0; index < 32; index++) {
                String sessionId = "concurrent-session-" + index;
                registrations.add(executor.submit(() -> {
                    start.await();
                    service.registerSession(principal, sessionId);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> registration : registrations) {
                registration.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(registry.getAllSessions(principal, false)).hasSize(3);
    }

    private UserSessionService service(
            SessionRegistry registry,
            SecurityAuditLogger auditLogger,
            int maximumSessions
    ) {
        return new UserSessionService(
                registry,
                new ApplicationSecurityProperties(
                        List.of("http://localhost"),
                        false,
                        Duration.ofHours(12),
                        maximumSessions
                ),
                new SecurityPseudonymizer(new SecurityTelemetryProperties(
                        "01234567890123456789012345678901",
                        "test-v1"
                )),
                auditLogger
        );
    }

    private AppUserPrincipal principal() {
        AppUserPrincipal principal = mock(AppUserPrincipal.class);
        when(principal.getUserId()).thenReturn(UUID.randomUUID());
        return principal;
    }
}
