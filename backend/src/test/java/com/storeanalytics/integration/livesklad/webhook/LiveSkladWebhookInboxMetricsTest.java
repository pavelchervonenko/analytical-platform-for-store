package com.storeanalytics.integration.livesklad.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class LiveSkladWebhookInboxMetricsTest {

    @Test
    void refreshesCountsFromJdbcResult() throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("webhook_kind")).thenReturn("SALE_RETURN");
        when(resultSet.getDouble("received")).thenReturn(7.0);
        when(resultSet.getDouble("retrying")).thenReturn(2.0);
        when(resultSet.getDouble("terminal_failed")).thenReturn(1.0);
        when(resultSet.getDouble("expired_lease")).thenReturn(3.0);

        LiveSkladWebhookInboxMetrics metrics =
                new LiveSkladWebhookInboxMetrics(
                        jdbcTemplate(resultSet),
                        Clock.fixed(
                                Instant.parse("2026-08-19T12:00:00Z"),
                                ZoneOffset.UTC
                        )
                );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        metrics.refresh();

        assertThat(gauge(registry, "SALE_RETURN", "received"))
                .isEqualTo(7.0);
        assertThat(gauge(registry, "SALE_RETURN", "retrying"))
                .isEqualTo(2.0);
        assertThat(gauge(registry, "SALE_RETURN", "terminal_failed"))
                .isEqualTo(1.0);
        assertThat(gauge(registry, "SALE_RETURN", "expired_lease"))
                .isEqualTo(3.0);
        assertThat(gauge(registry, "ORDER_RETURN", "received"))
                .isZero();
    }

    private JdbcTemplate jdbcTemplate(ResultSet resultSet) {
        return new JdbcTemplate() {
            @Override
            public <T> T query(
                    String sql,
                    ResultSetExtractor<T> extractor,
                    Object... arguments
            ) {
                try {
                    return extractor.extractData(resultSet);
                } catch (SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
    }

    private double gauge(
            SimpleMeterRegistry registry,
            String kind,
            String state
    ) {
        return registry.get(LiveSkladWebhookInboxMetrics.RECEIPTS_METRIC)
                .tag("kind", kind)
                .tag("state", state)
                .gauge()
                .value();
    }
}
