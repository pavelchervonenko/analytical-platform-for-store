package com.storeanalytics.notification.daily;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DailyStorePulseOperationalStateStore {

    private final JdbcTemplate jdbcTemplate;

    public DailyStorePulseOperationalStateStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Instant lastCreatedAt() {
        Timestamp value = jdbcTemplate.queryForObject(
                "SELECT max(created_at) FROM notification_events "
                        + "WHERE event_type = 'DAILY_STORE_PULSE'",
                Timestamp.class
        );
        return value == null ? null : value.toInstant();
    }
}
