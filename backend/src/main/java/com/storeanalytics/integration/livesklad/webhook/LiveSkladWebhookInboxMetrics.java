package com.storeanalytics.integration.livesklad.webhook;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public class LiveSkladWebhookInboxMetrics implements MeterBinder {

    static final String RECEIPTS_METRIC =
            "storeanalytics.livesklad.webhook.receipts";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LiveSkladWebhookInboxMetrics.class
    );
    private static final String[] KINDS = {"SALE_RETURN", "ORDER_RETURN"};
    private static final Duration STALE_RECEIPT_AGE = Duration.ofHours(1);
    private static final String[] STATES = {
        "received", "retrying", "terminal_failed", "expired_lease",
        "payload_mismatch", "stale"
    };

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final AtomicReference<Map<Key, Double>> counts =
            new AtomicReference<>(unknown());

    public LiveSkladWebhookInboxMetrics(
            JdbcTemplate jdbcTemplate,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String kind : KINDS) {
            for (String state : STATES) {
                Key key = new Key(kind, state);
                Gauge.builder(
                        RECEIPTS_METRIC,
                        counts,
                        values -> values.get().getOrDefault(
                                key,
                                Double.NaN
                        )
                ).description(
                        "Current LiveSklad webhook inbox receipts"
                ).tag("kind", kind)
                        .tag("state", state)
                        .register(registry);
            }
        }
    }

    @Scheduled(
            initialDelayString = "${app.observability.state-initial-delay:30s}",
            fixedDelayString = "${app.observability.state-refresh-delay:1m}",
            scheduler = BackgroundSchedulingConfiguration.METRICS_SCHEDULER
    )
    public void refresh() {
        try {
            Map<Key, Double> refreshed = new HashMap<>(zero());
            jdbcTemplate.query(
                    """
                    SELECT webhook_kind,
                           count(*) FILTER (
                               WHERE processing_status = 'RECEIVED'
                           ) AS received,
                           count(*) FILTER (
                               WHERE processing_status = 'FAILED'
                                 AND terminal_failure = false
                           ) AS retrying,
                           count(*) FILTER (
                               WHERE processing_status = 'FAILED'
                                 AND terminal_failure = true
                           ) AS terminal_failed,
                           count(*) FILTER (
                               WHERE processing_status = 'PROCESSING'
                                 AND lease_until < ?
                           ) AS expired_lease,
                           count(*) FILTER (
                               WHERE payload_mismatch = true
                           ) AS payload_mismatch,
                           count(*) FILTER (
                               WHERE processing_status IN ('RECEIVED', 'FAILED')
                                 AND terminal_failure = false
                                 AND first_received_at < ?
                           ) AS stale
                    FROM livesklad_webhook_receipts
                    GROUP BY webhook_kind
                    """,
                    resultSet -> {
                        while (resultSet.next()) {
                            String kind = resultSet.getString("webhook_kind");
                            for (String state : STATES) {
                                refreshed.put(
                                        new Key(kind, state),
                                        resultSet.getDouble(state)
                                );
                            }
                        }
                        return null;
                    },
                    java.sql.Timestamp.from(clock.instant()),
                    java.sql.Timestamp.from(
                            clock.instant().minus(STALE_RECEIPT_AGE)
                    )
            );
            counts.set(Map.copyOf(refreshed));
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Failed to refresh LiveSklad webhook inbox metrics",
                    exception
            );
        }
    }

    private static Map<Key, Double> unknown() {
        return values(Double.NaN);
    }

    private static Map<Key, Double> zero() {
        return values(0.0);
    }

    private static Map<Key, Double> values(double value) {
        Map<Key, Double> result = new HashMap<>();
        for (String kind : KINDS) {
            for (String state : STATES) {
                result.put(new Key(kind, state), value);
            }
        }
        return Map.copyOf(result);
    }

    private record Key(String kind, String state) {
    }
}
