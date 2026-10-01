package com.storeanalytics.metrics.repository;

import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Uses the current Overview SELLERS predicate, with no period-dependent membership. */
@Repository
public class SellerCohortRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SellerCohortRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SellerCohortSnapshot read(UUID storeId) {
        return new SellerCohortSnapshot(storeId, jdbcTemplate.query("""
                SELECT assignment.employee_id
                FROM employee_store_assignments assignment
                JOIN employees employee ON employee.id = assignment.employee_id
                WHERE assignment.store_id = :storeId
                  AND assignment.is_active
                  AND assignment.participates_in_ranking
                  AND employee.is_active
                ORDER BY assignment.employee_id
                """, Map.of("storeId", storeId),
                (row, index) -> row.getObject("employee_id", UUID.class)));
    }
}
