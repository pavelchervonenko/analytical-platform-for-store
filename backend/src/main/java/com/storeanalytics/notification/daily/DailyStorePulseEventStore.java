package com.storeanalytics.notification.daily;

import com.storeanalytics.interpretation.contract.CanonicalLlmJson;
import com.storeanalytics.interpretation.contract.LlmCanonicalJsonCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class DailyStorePulseEventStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final LlmCanonicalJsonCodec jsonCodec;

    public DailyStorePulseEventStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            LlmCanonicalJsonCodec jsonCodec
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.jsonCodec = jsonCodec;
    }

    public List<StoreTarget> activeStores() {
        return jdbcTemplate.query(
                """
                SELECT store.id, store.name, store.timezone,
                       sales.period_end AS sales_through_exclusive,
                       returns.period_end AS returns_through_exclusive,
                       orders.period_end AS orders_through_exclusive
                FROM stores store
                LEFT JOIN LATERAL (
                    SELECT run.period_end
                    FROM sync_runs run
                    WHERE (run.store_id = store.id
                           OR (run.store_id IS NULL
                               AND run.connection_id = store.connection_id))
                      AND run.sync_scope = 'SALES'
                      AND run.status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                      AND run.period_end IS NOT NULL
                    ORDER BY run.period_end DESC, run.finished_at DESC
                    LIMIT 1
                ) sales ON true
                LEFT JOIN LATERAL (
                    SELECT run.period_end
                    FROM sync_runs run
                    WHERE (run.store_id = store.id
                           OR (run.store_id IS NULL
                               AND run.connection_id = store.connection_id))
                      AND run.sync_scope = 'RETURNS'
                      AND run.status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                      AND run.period_end IS NOT NULL
                    ORDER BY run.period_end DESC, run.finished_at DESC
                    LIMIT 1
                ) returns ON true
                LEFT JOIN LATERAL (
                    SELECT run.period_end
                    FROM sync_runs run
                    WHERE (run.store_id = store.id
                           OR (run.store_id IS NULL
                               AND run.connection_id = store.connection_id))
                      AND run.sync_scope = 'ORDERS'
                      AND run.status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                      AND run.period_end IS NOT NULL
                    ORDER BY run.period_end DESC, run.finished_at DESC
                    LIMIT 1
                ) orders ON true
                WHERE store.is_active = true
                ORDER BY store.id
                """,
                this::mapTarget
        );
    }

    public boolean insert(
            StoreTarget store,
            DailyStorePulsePayload payload,
            String policyVersion,
            Instant notBefore,
            Instant expiresAt
    ) {
        CanonicalLlmJson canonical = canonical(payload);
        String deduplicationKey = "daily-store-pulse:"
                + store.storeId() + ":" + payload.businessDate() + ":" + policyVersion;
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO notification_events (
                    store_id, event_type, audience, deduplication_key,
                    notification_policy_version, priority, event_payload,
                    payload_hash, not_before, expires_at
                ) VALUES (
                    ?, 'DAILY_STORE_PULSE', 'MANAGER', ?, ?, 'NORMAL',
                    ?::jsonb, ?, ?, ?
                )
                ON CONFLICT (deduplication_key) DO NOTHING
                """,
                store.storeId(),
                deduplicationKey,
                policyVersion,
                canonical.canonicalJson(),
                canonical.contentHash(),
                Timestamp.from(notBefore),
                Timestamp.from(expiresAt)
        );
        return inserted == 1;
    }

    private CanonicalLlmJson canonical(DailyStorePulsePayload payload) {
        try {
            return jsonCodec.canonicalize(objectMapper.writeValueAsString(payload));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Daily store pulse cannot be encoded", exception);
        }
    }

    private StoreTarget mapTarget(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new StoreTarget(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("timezone"),
                instant(resultSet, "sales_through_exclusive"),
                instant(resultSet, "returns_through_exclusive"),
                instant(resultSet, "orders_through_exclusive")
        );
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record StoreTarget(
            UUID storeId,
            String storeName,
            String timezone,
            Instant salesThroughExclusive,
            Instant returnsThroughExclusive,
            Instant ordersThroughExclusive
    ) {

        public StoreTarget(
                UUID storeId,
                String storeName,
                String timezone,
                Instant salesThroughExclusive,
                Instant returnsThroughExclusive
        ) {
            this(
                    storeId, storeName, timezone,
                    salesThroughExclusive, returnsThroughExclusive,
                    returnsThroughExclusive
            );
        }
    }
}
