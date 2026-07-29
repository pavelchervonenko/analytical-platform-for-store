package com.storeanalytics.auth.service;

import com.storeanalytics.auth.exception.LoginThrottledException;
import com.storeanalytics.common.config.LoginThrottleProperties;
import com.storeanalytics.common.security.ClientAddress;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginThrottleService {

    private static final String EMAIL_SCOPE = "EMAIL";
    private static final String IP_SCOPE = "IP";

    private static final String BLOCKED_UNTIL_SQL = """
            SELECT blocked_until
            FROM auth_login_throttles
            WHERE scope = :scope
              AND identifier_hash = :identifierHash
              AND blocked_until > :now
            """;

    private static final String RECORD_FAILURE_SQL = """
            INSERT INTO auth_login_throttles (
                scope,
                identifier_hash,
                failure_count,
                window_started_at,
                blocked_until,
                last_failure_at
            )
            VALUES (
                :scope,
                :identifierHash,
                1,
                :now,
                NULL,
                :now
            )
            ON CONFLICT (scope, identifier_hash) DO UPDATE SET
                failure_count = CASE
                    WHEN auth_login_throttles.blocked_until <= :now
                      OR auth_login_throttles.window_started_at <= :windowCutoff
                        THEN 1
                    ELSE auth_login_throttles.failure_count + 1
                END,
                window_started_at = CASE
                    WHEN auth_login_throttles.blocked_until <= :now
                      OR auth_login_throttles.window_started_at <= :windowCutoff
                        THEN :now
                    ELSE auth_login_throttles.window_started_at
                END,
                blocked_until = CASE
                    WHEN auth_login_throttles.blocked_until <= :now
                      OR auth_login_throttles.window_started_at <= :windowCutoff
                        THEN NULL
                    WHEN auth_login_throttles.failure_count + 1 >= :threshold
                        THEN :newBlockedUntil
                    ELSE auth_login_throttles.blocked_until
                END,
                last_failure_at = :now
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final LoginThrottleProperties properties;
    private final Clock clock;

    public LoginThrottleService(
            NamedParameterJdbcTemplate jdbcTemplate,
            LoginThrottleProperties properties,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public void checkAllowed(String email, ClientAddress clientAddress) {
        Instant now = Instant.now(clock);
        Instant blockedUntil = List.of(
                        blockedUntil(EMAIL_SCOPE, emailKey(email), now),
                        blockedUntil(IP_SCOPE, ipKey(clientAddress), now)
                ).stream()
                .flatMap(java.util.Optional::stream)
                .max(Instant::compareTo)
                .orElse(null);
        if (blockedUntil != null) {
            Duration retryAfter = Duration.between(now, blockedUntil);
            throw new LoginThrottledException(retryAfter.isNegative() ? Duration.ZERO : retryAfter);
        }
    }

    @Transactional
    public void recordFailure(String email, ClientAddress clientAddress) {
        Instant now = Instant.now(clock);
        recordFailure(
                EMAIL_SCOPE,
                emailKey(email),
                properties.emailMaxFailures(),
                now
        );
        recordFailure(
                IP_SCOPE,
                ipKey(clientAddress),
                properties.ipMaxFailures(),
                now
        );
    }

    @Transactional
    public void recordSuccess(String email) {
        jdbcTemplate.update(
                """
                DELETE FROM auth_login_throttles
                WHERE scope = :scope AND identifier_hash = :identifierHash
                """,
                Map.of("scope", EMAIL_SCOPE, "identifierHash", emailKey(email))
        );
    }

    @Transactional
    public void removeExpiredEntries() {
        Instant cutoff = Instant.now(clock).minus(properties.retention());
        jdbcTemplate.update(
                "DELETE FROM auth_login_throttles WHERE last_failure_at < :cutoff",
                Map.of("cutoff", Timestamp.from(cutoff))
        );
    }

    private java.util.Optional<Instant> blockedUntil(
            String scope,
            String identifierHash,
            Instant now
    ) {
        List<Instant> values = jdbcTemplate.query(
                BLOCKED_UNTIL_SQL,
                Map.of(
                        "scope", scope,
                        "identifierHash", identifierHash,
                        "now", Timestamp.from(now)
                ),
                (resultSet, rowNumber) -> resultSet.getTimestamp("blocked_until").toInstant()
        );
        return values.stream().findFirst();
    }

    private void recordFailure(
            String scope,
            String identifierHash,
            int threshold,
            Instant now
    ) {
        jdbcTemplate.update(
                RECORD_FAILURE_SQL,
                Map.of(
                        "scope", scope,
                        "identifierHash", identifierHash,
                        "now", Timestamp.from(now),
                        "windowCutoff", Timestamp.from(now.minus(properties.window())),
                        "threshold", threshold,
                        "newBlockedUntil", Timestamp.from(now.plus(properties.blockDuration()))
                )
        );
    }

    private String emailKey(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return LoginThrottleKeyHasher.hash("email", normalized);
    }

    private String ipKey(ClientAddress clientAddress) {
        return LoginThrottleKeyHasher.hash(
                "ip", clientAddress.throttleKey()
        );
    }
}
