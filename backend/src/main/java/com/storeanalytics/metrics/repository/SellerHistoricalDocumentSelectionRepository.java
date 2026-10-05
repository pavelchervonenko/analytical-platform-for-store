package com.storeanalytics.metrics.repository;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.repository.SellerHistoricalDocumentSelection.Bucket;
import com.storeanalytics.metrics.repository.SellerHistoricalDocumentSelection.Reason;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Resolves analytical seller membership per document, independently of payroll attribution. */
@Repository
public class SellerHistoricalDocumentSelectionRepository {

    private static final String QUERY = AnalyticalDocumentSql.PERIOD_DOCUMENTS_CTE + """
            , attributed AS (
                SELECT id, business_date, document_kind,
                       attach_source_employee_external_id AS return_employee_external_id,
                       analytical_employee_id AS effective_employee_id,
                       occurred_at AS membership_at
                FROM analytical_documents
            )
            SELECT attributed.id, attributed.business_date,
                   attributed.effective_employee_id, attributed.membership_at,
                   CASE
                       WHEN attributed.document_kind = 'RETURN'
                         AND attributed.effective_employee_id IS NULL
                           THEN 'UNKNOWN_EMPLOYEE_ATTRIBUTION'
                       WHEN attributed.effective_employee_id IS NULL
                           THEN 'KNOWN_OUTSIDE_SELLER_COHORT'
                       WHEN state.store_id IS NULL
                         OR attributed.membership_at < state.authoritative_from
                         OR interval.id IS NULL THEN 'UNKNOWN_MEMBERSHIP_HISTORY'
                       WHEN interval.employee_active AND interval.assignment_active
                         AND interval.participates_in_ranking THEN 'SELLER_ELIGIBLE'
                       ELSE 'KNOWN_OUTSIDE_SELLER_COHORT'
                   END AS bucket,
                   CASE
                       WHEN attributed.document_kind = 'RETURN'
                         AND attributed.effective_employee_id IS NULL
                         AND attributed.return_employee_external_id IS NULL
                           THEN 'UNATTRIBUTED_RETURN'
                       WHEN attributed.document_kind = 'RETURN'
                         AND attributed.effective_employee_id IS NULL
                           THEN 'UNRESOLVED_RETURN_EMPLOYEE'
                       WHEN attributed.effective_employee_id IS NULL
                         AND attributed.document_kind = 'SALE' THEN 'UNATTRIBUTED_SALE'
                       WHEN state.store_id IS NULL
                         OR attributed.membership_at < state.authoritative_from
                         OR interval.id IS NULL THEN 'HISTORY_UNKNOWN'
                       WHEN NOT (interval.employee_active AND interval.assignment_active
                         AND interval.participates_in_ranking)
                           THEN 'EXPLICITLY_INELIGIBLE_EMPLOYEE'
                       ELSE 'NONE'
                   END AS reason
            FROM attributed
            LEFT JOIN store_seller_membership_state state ON state.store_id = :storeId
            LEFT JOIN LATERAL (
                SELECT history.id, history.employee_active, history.assignment_active,
                       history.participates_in_ranking
                FROM seller_membership_history history
                WHERE history.store_id = :storeId
                  AND history.employee_id = attributed.effective_employee_id
                  AND tstzrange(history.valid_from, history.valid_to, '[)')
                      @> attributed.membership_at
            ) interval ON true
            ORDER BY attributed.business_date, attributed.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SellerHistoricalDocumentSelectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SellerHistoricalDocumentSelection> read(UUID storeId, LocalDate start, LocalDate end) {
        UUID store = requireNonNull(storeId, "storeId");
        LocalDate from = requireNonNull(start, "start");
        LocalDate through = requireNonNull(end, "end");
        if (from.isAfter(through)) {
            throw new IllegalArgumentException("start must not be after end");
        }
        return jdbc.query(QUERY, Map.of("storeId", store, "periodStart", from, "periodEnd", through),
                (row, index) -> new SellerHistoricalDocumentSelection(
                        row.getObject("id", UUID.class),
                        row.getObject("effective_employee_id", UUID.class),
                        row.getObject("business_date", LocalDate.class),
                        row.getTimestamp("membership_at") == null ? null
                                : row.getTimestamp("membership_at").toInstant(),
                        Bucket.valueOf(row.getString("bucket")), Reason.valueOf(row.getString("reason"))));
    }
}
