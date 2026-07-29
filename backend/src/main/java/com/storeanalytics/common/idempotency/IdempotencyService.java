package com.storeanalytics.common.idempotency;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import com.storeanalytics.common.exception.InvalidRequestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

    private static final Pattern KEY = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{7,99}"
    );
    private static final Pattern ACTION = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");
    private static final int MAX_RESOURCE_LENGTH = 256;
    private static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(?, ?)";

    private final IdempotencyReceiptRepository repository;
    private final IdempotencyProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectWriter fingerprintWriter;
    private final Clock clock;

    public IdempotencyService(
            IdempotencyReceiptRepository repository,
            IdempotencyProperties properties,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.fingerprintWriter = JsonMapper.builder()
                .findAndAddModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build()
                .writer();
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(
            UUID actorId,
            String idempotencyKey,
            IdempotencyRequest request,
            Class<T> responseType,
            Supplier<T> command
    ) {
        UUID actor = Objects.requireNonNull(actorId, "actorId");
        String key = validateKey(idempotencyKey);
        ValidatedRequest validated = validateRequest(request);
        Class<T> type = Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(command, "command");
        String hash = fingerprint(validated.body());

        acquireLock(actor, key);
        Instant now = clock.instant();
        IdempotencyReceipt existing = repository
                .findByActorIdAndIdempotencyKey(actor, key)
                .orElse(null);
        if (existing != null && existing.isExpired(now)) {
            repository.delete(existing);
            repository.flush();
            existing = null;
        }
        if (existing != null) {
            requireMatching(existing, validated, hash, type);
            return decode(existing.getResponseBody(), type);
        }

        T result = Objects.requireNonNull(command.get(), "idempotent command result");
        String responseBody = encode(result);
        repository.saveAndFlush(new IdempotencyReceipt(
                actor,
                key,
                new IdempotencyReceiptContent(
                        validated.action(),
                        validated.resource(),
                        hash,
                        type.getName(),
                        responseBody
                ),
                now.plus(properties.ttl())
        ));
        return result;
    }

    private void acquireLock(UUID actorId, String key) {
        jdbcTemplate.execute(LOCK_SQL, (PreparedStatementCallback<Void>) statement -> {
            statement.setInt(1, actorId.hashCode());
            statement.setInt(2, key.hashCode());
            statement.execute();
            return null;
        });
    }

    private void requireMatching(
            IdempotencyReceipt receipt,
            ValidatedRequest request,
            String requestHash,
            Class<?> responseType
    ) {
        if (!receipt.getAction().equals(request.action())
                || !receipt.getResourceIdentity().equals(request.resource())
                || !receipt.getRequestHash().equals(requestHash)
                || !receipt.getResponseType().equals(responseType.getName())) {
            throw new IdempotencyKeyConflictException();
        }
    }

    private String validateKey(String value) {
        String key = value == null ? "" : value.trim();
        if (!KEY.matcher(key).matches()) {
            throw new InvalidRequestException(
                    "Idempotency-Key must contain 8 to 100 safe characters"
            );
        }
        return key;
    }

    private ValidatedRequest validateRequest(IdempotencyRequest request) {
        IdempotencyRequest value = Objects.requireNonNull(request, "request");
        String action = value.action() == null ? "" : value.action().trim();
        String resource = value.resource() == null ? "" : value.resource().trim();
        if (!ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("invalid idempotency action");
        }
        if (resource.isEmpty() || resource.length() > MAX_RESOURCE_LENGTH) {
            throw new IllegalArgumentException("invalid idempotency resource identity");
        }
        return new ValidatedRequest(action, resource, value.body());
    }

    private String fingerprint(Object body) {
        try {
            byte[] json = fingerprintWriter.writeValueAsBytes(body);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(json)
            );
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not fingerprint idempotent request", exception);
        }
    }

    private String encode(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not persist idempotent response", exception);
        }
    }

    private <T> T decode(String responseBody, Class<T> responseType) {
        try {
            return objectMapper.readValue(responseBody, responseType);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not restore idempotent response", exception);
        }
    }

    private record ValidatedRequest(String action, String resource, Object body) {
    }
}
