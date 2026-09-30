package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The revision belongs to the same MVCC snapshot as the seller facts. */
@Repository
class SellerWeeklySourceRevisionRepository {

    private final JdbcTemplate jdbc;

    SellerWeeklySourceRevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long read(UUID storeId) {
        return jdbc.queryForObject("""
                SELECT COALESCE((SELECT revision FROM store_analytics_source_state
                                 WHERE store_id = ?), 0)
                """, Long.class, requireNonNull(storeId, "storeId"));
    }
}
