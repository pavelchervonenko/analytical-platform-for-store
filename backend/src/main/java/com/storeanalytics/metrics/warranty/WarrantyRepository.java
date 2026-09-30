package com.storeanalytics.metrics.warranty;

import com.storeanalytics.common.exception.InvalidRequestException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WarrantyRepository {
    private static final String DEVICE_QUERY = """
            SELECT i.*, e.full_name AS employee_name,
                md5(concat_ws(':', i.fingerprint, context.fingerprint)) AS target_fingerprint,
                COALESCE((SELECT sum(a.net_quantity) FROM warranty_attach_effective_allocations a
                    WHERE a.device_item_id = i.id), 0) AS allocated_quantity,
                COALESCE((SELECT sum(r.quantity) FROM warranty_attach_items r
                    WHERE r.original_item_id = i.id AND r.active AND r.document_kind = 'RETURN'), 0)
                    AS returned_quantity
            FROM warranty_attach_items i
            JOIN warranty_attach_document_context context ON context.document_id = i.document_id
            LEFT JOIN employees e ON e.id = i.employee_id
            WHERE i.store_id = ? AND i.active AND i.document_kind = 'SALE'
              AND i.device_type IS NOT NULL
            """;
    private final JdbcTemplate jdbc;

    public WarrantyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public WarrantyViews.Case find(UUID storeId, UUID sourceId) {
        return jdbc.query("SELECT * FROM warranty_attach_cases WHERE store_id = ? AND id = ?",
                this::caseRow, storeId, sourceId).stream().findFirst()
                .orElseThrow(WarrantyCaseNotFoundException::new);
    }

    public WarrantyViews.Queue queue(UUID storeId, String state, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new InvalidRequestException("Invalid pagination");
        }
        String condition = switch (state) {
            case "OPEN" -> " AND state IN ('CONFLICT','DEFERRED')";
            case "ALL" -> "";
            case "WARNINGS" -> " AND id IN (SELECT source_item_id FROM warranty_attach_warning_sources)";
            case "RESOLVED" -> " AND state IN ('RESOLVED_AUTO','RESOLVED_MANUAL','EXCLUDED')";
            default -> throw new InvalidRequestException("Unknown warranty state filter");
        };
        List<WarrantyViews.Case> items = jdbc.query(
                "SELECT * FROM warranty_attach_cases WHERE store_id = ?" + condition
                        + " ORDER BY business_date, document_id, id LIMIT ? OFFSET ?",
                this::caseRow, storeId, limit, offset);
        return jdbc.queryForObject("""
                SELECT count(*) AS total, count(DISTINCT document_id) AS documents,
                       COALESCE(sum(pending_quantity)
                           FILTER (WHERE state IN ('CONFLICT','DEFERRED')),0) AS units
                FROM warranty_attach_cases WHERE store_id = ?
                """ + condition, (rs, row) -> new WarrantyViews.Queue(items,
                        rs.getLong("total"), rs.getLong("documents"), rs.getBigDecimal("units"),
                        offset, limit, false), storeId);
    }

    public List<WarrantyViews.Device> candidates(UUID storeId, UUID documentId) {
        return jdbc.query(DEVICE_QUERY + " AND i.document_id = ? ORDER BY i.id LIMIT 100",
                this::deviceRow, storeId, documentId);
    }

    public List<WarrantyViews.Device> search(UUID storeId, String query) {
        String term = searchTerm(query);
        return jdbc.query(DEVICE_QUERY + """
                 AND (i.document_external_id = ? OR i.document_number ILIKE ? OR i.name ILIKE ?)
                 ORDER BY i.business_date DESC, i.id LIMIT 50
                """, this::deviceRow, storeId, query.trim(), "%" + term + "%", "%" + term + "%");
    }

    public List<WarrantyViews.Case> searchOriginals(UUID storeId, String query) {
        String term = searchTerm(query);
        return jdbc.query("""
                SELECT * FROM warranty_attach_cases WHERE store_id = ? AND document_kind = 'SALE'
                  AND (document_external_id = ? OR document_number ILIKE ? OR name ILIKE ?)
                ORDER BY business_date DESC, id LIMIT 50
                """, this::caseRow, storeId, query.trim(), "%" + term + "%", "%" + term + "%");
    }

    public WarrantyViews.Device device(UUID storeId, UUID id) {
        return jdbc.query(DEVICE_QUERY + " AND i.id = ?", this::deviceRow, storeId, id)
                .stream().findFirst().orElseThrow(WarrantyCaseNotFoundException::new);
    }

    public List<WarrantyViews.Allocation> allocations(UUID sourceId) {
        return jdbc.query("""
                SELECT device_item_id, device_document_id, device_type, business_date,
                       employee_id, abs(net_quantity) AS quantity
                FROM warranty_attach_effective_allocations WHERE source_item_id = ?
                ORDER BY device_document_id, device_item_id
                """, this::allocationRow, sourceId);
    }

    public List<WarrantyViews.History> history(UUID sourceId) {
        Map<UUID, List<WarrantyViews.Allocation>> allocations = new java.util.HashMap<>();
        jdbc.query("""
                SELECT a.* FROM warranty_attach_allocations a
                JOIN (SELECT id FROM warranty_attach_decisions WHERE source_item_id = ?
                      ORDER BY revision DESC LIMIT 100) d ON d.id = a.decision_id
                ORDER BY a.decision_id, a.device_item_id
                """, rs -> {
                    allocations.computeIfAbsent(rs.getObject("decision_id", UUID.class),
                            key -> new java.util.ArrayList<>()).add(allocationRow(rs, 0));
                }, sourceId);
        return jdbc.query("""
                SELECT d.*, actor.display_name AS actor_name
                FROM warranty_attach_decisions d JOIN app_users actor ON actor.id = d.actor_id
                WHERE d.source_item_id = ? ORDER BY revision DESC LIMIT 100
                """, (rs, row) -> new WarrantyViews.History(
                        rs.getObject("id", UUID.class), rs.getLong("revision"), rs.getString("action"),
                        rs.getObject("actor_id", UUID.class), rs.getString("actor_name"), rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant(),
                        List.copyOf(allocations.getOrDefault(rs.getObject("id", UUID.class), List.of()))
                ), sourceId);
    }

    public boolean matchesProviderOriginal(UUID sourceId, UUID originalId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT original_item_id IS NULL OR original_item_id = ?
                FROM sales_document_items WHERE id = ?
                """, Boolean.class, originalId, sourceId));
    }

    public List<String> productWarnings(UUID sourceId) {
        return jdbc.query("""
                SELECT count(*) FILTER (WHERE a.device_type = 'NEW') AS new_count,
                       count(*) FILTER (WHERE a.device_type = 'USED') AS used_count
                FROM warranty_attach_sale_allocations a
                JOIN warranty_attach_items i ON i.id = a.source_item_id
                JOIN warranty_attach_items source ON source.id = ?
                WHERE i.product_id = source.product_id AND i.store_id = source.store_id
                """, (rs, row) -> rs.getLong("new_count") > 0 && rs.getLong("used_count") > 0
                        ? List.of("Этот товар гарантии связан с новыми устройствами в " + rs.getLong("new_count")
                                + " строках, с Б/У — в "
                                + rs.getLong("used_count") + " строках за всю историю магазина")
                        : List.<String>of(), sourceId).getFirst();
    }

    public List<String> coverageWarnings(UUID documentId, String type, BigDecimal delta) {
        return jdbc.query("""
                SELECT sold, returned, warranties FROM warranty_attach_coverage
                WHERE document_id = ? AND device_type = ?
                """, (rs, row) -> {
                    BigDecimal active = rs.getBigDecimal("warranties").add(delta);
                    BigDecimal sold = rs.getBigDecimal("sold");
                    BigDecimal returned = rs.getBigDecimal("returned");
                    List<String> warnings = new java.util.ArrayList<>();
                    if (active.compareTo(sold) > 0) {
                        warnings.add("Гарантий больше, чем устройств в исходной продаже");
                    }
                    BigDecimal remaining = sold.subtract(returned);
                    if (active.signum() > 0 && remaining.signum() > 0 && active.compareTo(remaining) > 0) {
                        warnings.add("Покрытие устройств исходной продажи превышает 100%");
                    }
                    if (active.signum() > 0 && returned.signum() > 0) {
                        warnings.add("Устройство возвращено, а связанная гарантия остаётся активной");
                    }
                    return List.copyOf(warnings);
                }, documentId, type).stream().findFirst().orElse(List.of());
    }

    public void lock(UUID storeId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 74771))",
                (rs, row) -> null, storeId.toString());
    }

    public UUID save(WarrantyViews.Case source, WarrantyDecisionRequest request, UUID actorId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO warranty_attach_decisions
                    (id, source_item_id, revision, action, source_fingerprint,
                     original_warranty_item_id, actor_id, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, source.id(), source.revision() + 1, request.action().name(),
                decisionFingerprint(source.id(), request.originalWarrantyItemId()),
                request.originalWarrantyItemId() == null ? source.originalWarrantyItemId()
                        : request.originalWarrantyItemId(), actorId, request.reason().trim());
        return id;
    }

    private String decisionFingerprint(UUID sourceId, UUID selectedOriginal) {
        return jdbc.queryForObject("""
                SELECT md5(concat_ws(':', source.fingerprint, context.fingerprint,
                    original.fingerprint, od.id))
                FROM warranty_attach_items source
                JOIN warranty_attach_document_context context ON context.document_id = source.document_id
                LEFT JOIN warranty_attach_latest_decisions latest ON latest.source_item_id = source.id
                LEFT JOIN warranty_attach_items original ON original.id =
                    COALESCE(source.original_item_id, ?::uuid, latest.original_warranty_item_id)
                LEFT JOIN warranty_attach_latest_decisions od ON od.source_item_id = original.id
                WHERE source.id = ?
                """, String.class, selectedOriginal, sourceId);
    }

    public void saveAllocation(UUID decisionId, WarrantyDecisionRequest.Allocation allocation,
                               WarrantyViews.Device device) {
        jdbc.update("""
                INSERT INTO warranty_attach_allocations
                    (decision_id, device_item_id, quantity, target_fingerprint,
                     device_document_id, device_type, business_date, employee_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, decisionId, device.id(), allocation.quantity(), device.fingerprint(),
                device.documentId(), device.deviceType(), device.businessDate(), device.employeeId());
    }

    public BigDecimal otherReturned(UUID originalId, UUID exceptSource) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity),0) FROM warranty_attach_sources
                WHERE warranty_original_id = ? AND id <> ? AND document_kind = 'RETURN'
                  AND NOT (COALESCE(decision_valid,false) AND action = 'EXCLUDE')
                """, BigDecimal.class, originalId, exceptSource);
    }

    public BigDecimal returnedToDevice(UUID originalId, UUID deviceId, UUID exceptSource) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(quantity),0) FROM warranty_attach_return_allocations
                WHERE original_warranty_item_id = ? AND device_item_id = ? AND source_item_id <> ?
                """, BigDecimal.class, originalId, deviceId, exceptSource);
    }

    public boolean sameConnection(UUID sourceId, UUID targetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT a.connection_id IS NOT DISTINCT FROM b.connection_id
                FROM warranty_attach_items a CROSS JOIN warranty_attach_items b WHERE a.id = ? AND b.id = ?
                """, Boolean.class, sourceId, targetId));
    }

    private String searchTerm(String query) {
        String term = query == null ? "" : query.trim();
        if (term.length() < 2 || term.length() > 150) {
            throw new InvalidRequestException("Search requires 2 to 150 characters");
        }
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private WarrantyViews.Case caseRow(ResultSet rs, int row) throws SQLException {
        return new WarrantyViews.Case(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getString("document_external_id"), rs.getString("document_number"),
                rs.getObject("business_date", LocalDate.class), rs.getString("document_kind"),
                rs.getString("name"), rs.getBigDecimal("quantity"), rs.getString("state"),
                rs.getString("conflict_code"), rs.getString("review_fingerprint"), rs.getLong("revision"),
                rs.getObject("warranty_original_id", UUID.class), rs.getString("financial_employee_name"));
    }

    private WarrantyViews.Device deviceRow(ResultSet rs, int row) throws SQLException {
        return new WarrantyViews.Device(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getString("document_external_id"), rs.getString("document_number"),
                rs.getObject("business_date", LocalDate.class), rs.getObject("employee_id", UUID.class),
                rs.getString("employee_name"), rs.getString("name"), rs.getString("device_type"),
                rs.getBigDecimal("quantity"), rs.getString("target_fingerprint"),
                rs.getBigDecimal("allocated_quantity"), rs.getBigDecimal("returned_quantity"));
    }

    private WarrantyViews.Allocation allocationRow(ResultSet rs, int row) throws SQLException {
        return new WarrantyViews.Allocation(rs.getObject("device_item_id", UUID.class),
                rs.getObject("device_document_id", UUID.class), rs.getString("device_type"),
                rs.getObject("business_date", LocalDate.class), rs.getObject("employee_id", UUID.class),
                rs.getBigDecimal("quantity"));
    }
}
