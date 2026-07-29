package com.storeanalytics.auth.service;

import com.storeanalytics.auth.exception.CurrentSessionRequiresLogoutException;
import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.common.config.ApplicationSecurityProperties;
import com.storeanalytics.common.security.SecurityAuditLogger;
import com.storeanalytics.common.security.SecurityPseudonymizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

@Service
public class UserSessionService {

    private static final int LOCK_STRIPES = 64;
    private static final Pattern SESSION_REFERENCE = Pattern.compile(
            "h1_[0-9a-f]{24}"
    );

    private final SessionRegistry sessionRegistry;
    private final ApplicationSecurityProperties securityProperties;
    private final SecurityPseudonymizer pseudonymizer;
    private final SecurityAuditLogger securityAuditLogger;
    private final Object[] userLocks;

    public UserSessionService(
            SessionRegistry sessionRegistry,
            ApplicationSecurityProperties securityProperties,
            SecurityPseudonymizer pseudonymizer,
            SecurityAuditLogger securityAuditLogger
    ) {
        this.sessionRegistry = sessionRegistry;
        this.securityProperties = securityProperties;
        this.pseudonymizer = pseudonymizer;
        this.securityAuditLogger = securityAuditLogger;
        this.userLocks = new Object[LOCK_STRIPES];
        Arrays.setAll(userLocks, ignored -> new Object());
    }

    public void registerSession(
            AppUserPrincipal principal,
            String sessionId
    ) {
        synchronized (lock(principal.getUserId())) {
            List<SessionInformation> existing = activeSessions(principal).stream()
                    .sorted(Comparator.comparing(SessionInformation::getLastRequest))
                    .toList();
            int sessionsToExpire = existing.size()
                    - securityProperties.maxConcurrentSessions()
                    + 1;
            for (int index = 0; index < sessionsToExpire; index++) {
                existing.get(index).expireNow();
            }
            sessionRegistry.registerNewSession(sessionId, principal);
        }
    }

    public List<ActiveSessionView> listSessions(
            AppUserPrincipal principal,
            String currentSessionId
    ) {
        Comparator<ActiveSessionView> ordering = Comparator
                .comparing(ActiveSessionView::current)
                .reversed()
                .thenComparing(
                        ActiveSessionView::lastSeenAt,
                        Comparator.reverseOrder()
                )
                .thenComparing(ActiveSessionView::sessionReference);
        return activeSessions(principal).stream()
                .map(session -> view(session, currentSessionId))
                .sorted(ordering)
                .toList();
    }

    public void revokeSession(
            AppUserPrincipal principal,
            String currentSessionId,
            String sessionReference
    ) {
        if (!SESSION_REFERENCE.matcher(sessionReference).matches()) {
            return;
        }
        synchronized (lock(principal.getUserId())) {
            for (SessionInformation session : activeSessions(principal)) {
                if (!referenceMatches(session.getSessionId(), sessionReference)) {
                    continue;
                }
                if (session.getSessionId().equals(currentSessionId)) {
                    throw new CurrentSessionRequiresLogoutException();
                }
                session.expireNow();
                securityAuditLogger.sessionsRevoked(
                        principal.getUserId(),
                        "single",
                        1
                );
                return;
            }
        }
    }

    public void revokeOtherSessions(
            AppUserPrincipal principal,
            String currentSessionId
    ) {
        int revoked = 0;
        synchronized (lock(principal.getUserId())) {
            for (SessionInformation session : activeSessions(principal)) {
                if (!session.getSessionId().equals(currentSessionId)) {
                    session.expireNow();
                    revoked++;
                }
            }
        }
        if (revoked > 0) {
            securityAuditLogger.sessionsRevoked(
                    principal.getUserId(),
                    "all_other",
                    revoked
            );
        }
    }

    private List<SessionInformation> activeSessions(AppUserPrincipal principal) {
        return sessionRegistry.getAllSessions(principal, false);
    }

    private ActiveSessionView view(
            SessionInformation session,
            String currentSessionId
    ) {
        return new ActiveSessionView(
                reference(session.getSessionId()),
                Instant.ofEpochMilli(session.getLastRequest().getTime()),
                session.getSessionId().equals(currentSessionId)
        );
    }

    private boolean referenceMatches(String sessionId, String candidate) {
        return MessageDigest.isEqual(
                reference(sessionId).getBytes(StandardCharsets.US_ASCII),
                candidate.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private String reference(String sessionId) {
        return pseudonymizer.reference("browser_session", sessionId);
    }

    private Object lock(UUID userId) {
        return userLocks[Math.floorMod(userId.hashCode(), userLocks.length)];
    }
}
