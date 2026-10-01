package com.storeanalytics.product.service;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.product.model.CategoryAssignmentDetails;
import com.storeanalytics.product.model.CategoryAssignmentSource;
import com.storeanalytics.product.model.AnalyticsCategory;
import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductCategoryAssignment;
import com.storeanalytics.product.repository.ProductCategoryAssignmentRepository;
import com.storeanalytics.salary.model.PayrollCategoryCode;
import com.storeanalytics.salary.service.PayrollConfigurationService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** First confirmation of a newly sold product; all assignments and sale reconciliation commit together. */
@Service
public class CatalogProductReviewDecisionService {
    private final JdbcTemplate jdbc;
    private final CatalogClassificationCutover cutover;
    private final EntityManager entityManager;
    private final ProductCategoryAssignmentRepository analyticsAssignments;
    private final PayrollConfigurationService payroll;
    private final ProductClassificationReconciliationService reconciliation;
    private final AuditLogService audit;

    public CatalogProductReviewDecisionService(
            JdbcTemplate jdbc, CatalogClassificationCutover cutover, EntityManager entityManager,
            ProductCategoryAssignmentRepository analyticsAssignments, PayrollConfigurationService payroll,
            ProductClassificationReconciliationService reconciliation, AuditLogService audit
    ) {
        this.jdbc = jdbc;
        this.cutover = cutover;
        this.entityManager = entityManager;
        this.analyticsAssignments = analyticsAssignments;
        this.payroll = payroll;
        this.reconciliation = reconciliation;
        this.audit = audit;
    }

    @Transactional
    public CatalogProductReviewDecisionResult decide(
            UUID productId, CatalogProductReviewDecision decision, UUID actorId
    ) {
        Objects.requireNonNull(decision, "decision");
        Instant boundary = cutover.activationBoundary().orElseThrow(() ->
                new InvalidRequestException("Catalog classification is not active"));
        if (!cutover.isBusinessDayBoundary()) {
            throw new InvalidRequestException("Catalog activation must start at business-day midnight");
        }
        LocalDate payrollStart = cutover.activationBusinessDate();
        if (decision.analyticsCategoryCode() == null || decision.conditionType() == null
                || decision.payrollCategoryCode() == null || decision.reason() == null
                || decision.reason().isBlank() || decision.reason().length() > 500) {
            throw new InvalidRequestException("Both categories, condition and reason are required");
        }
        if (decision.payrollCategoryCode() == PayrollCategoryCode.UNMAPPED) {
            throw new InvalidRequestException("Payroll category UNMAPPED cannot be confirmed");
        }
        var locked = jdbc.query("""
                SELECT connection_id, external_id, version, created_at
                FROM products WHERE id = ? AND source_system = 'LIVESKLAD' FOR UPDATE
                """, (row, number) -> new LockedProduct(
                row.getObject("connection_id", UUID.class), row.getString("external_id"),
                row.getLong("version"), row.getTimestamp("created_at").toInstant()
        ), productId).stream().findFirst().orElseThrow(() ->
                new InvalidRequestException("LiveSklad product does not exist"));
        if (locked.version() != decision.expectedProductVersion()) {
            throw new InvalidRequestException("Product changed; refresh the review before saving");
        }
        if (locked.createdAt().isBefore(boundary)) {
            throw new InvalidRequestException("Existing catalog products require a separate reviewed import");
        }
        var first = jdbc.query("""
                SELECT min(document.occurred_at) AS occurred_at,
                       (array_agg(document.business_date ORDER BY document.occurred_at, document.id))[1]
                           AS business_date
                FROM sales_document_items item
                JOIN sales_documents document ON document.id = item.sales_document_id
                WHERE item.product_id = ? AND document.document_kind = 'SALE'
                  AND document.occurred_at >= ? AND NOT document.is_deleted AND NOT item.is_deleted
                """, (row, number) -> new FirstSale(
                row.getTimestamp("occurred_at") == null ? null
                        : row.getTimestamp("occurred_at").toInstant(),
                row.getObject("business_date", LocalDate.class)
        ), productId, Timestamp.from(boundary)).getFirst();
        if (first.occurredAt() == null) {
            throw new InvalidRequestException("No eligible sale for this product");
        }
        List<ExistingAnalytics> priorAnalytics = jdbc.query("""
                SELECT category.code, assignment.condition_type, assignment.valid_from,
                       assignment.valid_to
                FROM product_category_assignments assignment
                JOIN analytics_categories category ON category.id = assignment.analytics_category_id
                WHERE assignment.product_id = ?
                """, (row, number) -> new ExistingAnalytics(
                row.getString("code"), row.getString("condition_type"),
                row.getTimestamp("valid_from").toInstant(),
                row.getTimestamp("valid_to") == null ? null
                        : row.getTimestamp("valid_to").toInstant()), productId);
        List<ExistingPayroll> priorPayroll = jdbc.query("""
                SELECT payroll_category_code, valid_from, valid_to
                FROM product_payroll_category_assignments WHERE product_id = ?
                """, (row, number) -> new ExistingPayroll(
                row.getString("payroll_category_code"),
                row.getObject("valid_from", LocalDate.class),
                row.getObject("valid_to", LocalDate.class)), productId);
        if (priorAnalytics.size() > 1 || priorPayroll.size() > 1) {
            throw new InvalidRequestException("Product assignment history needs a separate correction");
        }
        if (!priorAnalytics.isEmpty()) {
            ExistingAnalytics existing = priorAnalytics.getFirst();
            if (existing.validFrom().isAfter(first.occurredAt()) || existing.validTo() != null
                    || !existing.code().equals(decision.analyticsCategoryCode())
                    || !existing.conditionType().equals(decision.conditionType().name())) {
                throw new InvalidRequestException("Existing analytical assignment differs; correct it separately");
            }
        }
        if (!priorPayroll.isEmpty()) {
            ExistingPayroll existing = priorPayroll.getFirst();
            if (existing.validFrom().isAfter(first.businessDate()) || existing.validTo() != null
                    || !existing.code().equals(decision.payrollCategoryCode().name())) {
                throw new InvalidRequestException("Existing payroll assignment differs; correct it separately");
            }
        }
        Boolean closedPayroll = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM payroll_runs run
                    WHERE run.status IN ('APPROVED', 'PAID')
                      AND run.period_month >= date_trunc('month', ?::date)::date
                      AND run.store_id IN (
                          SELECT DISTINCT document.store_id
                          FROM sales_document_items item
                          JOIN sales_documents document ON document.id = item.sales_document_id
                          WHERE item.product_id = ? AND document.document_kind = 'SALE'
                            AND document.occurred_at >= ?
                            AND NOT document.is_deleted AND NOT item.is_deleted
                      )
                )
                """, Boolean.class, payrollStart, productId, Timestamp.from(boundary));
        if (Boolean.TRUE.equals(closedPayroll)) {
            throw new InvalidRequestException("An approved or paid payroll period needs a separate correction");
        }
        var category = entityManager.createQuery("""
                        SELECT category FROM AnalyticsCategory category
                        WHERE category.code = :code AND category.active = true
                        """, AnalyticsCategory.class)
                .setParameter("code", decision.analyticsCategoryCode())
                .getResultStream().filter(value -> !"UNMAPPED".equals(value.getCode()))
                .findFirst().orElseThrow(() ->
                        new InvalidRequestException("Active analytical category not found"));
        var product = entityManager.find(Product.class, productId);
        var actor = entityManager.find(AppUser.class, actorId);
        if (product == null || actor == null) {
            throw new InvalidRequestException("Product or actor does not exist");
        }
        if (priorAnalytics.isEmpty()) {
            var assignment = analyticsAssignments.saveAndFlush(new ProductCategoryAssignment(
                    product, category, new CategoryAssignmentDetails(
                            decision.conditionType(), CategoryAssignmentSource.MANUAL,
                            "manager-catalog-review-v1", boundary, null, actor,
                            decision.reason().trim())));
            audit.record(actorId, null, AuditAction.ANALYTICS_PRODUCT_CLASSIFIED,
                    new AuditTarget(AuditEntityType.PRODUCT_CATEGORY_ASSIGNMENT, assignment.getId()),
                    decision.reason(), null,
                    Map.of("productId", productId, "categoryCode", category.getCode(),
                            "validFrom", boundary));
        }
        if (priorPayroll.isEmpty()) {
            payroll.assignProduct(productId, decision.payrollCategoryCode(), payrollStart,
                    decision.reason().trim(), actorId);
        }
        var reconciled = reconciliation.reconcileImportedScope(
                locked.connectionId(), java.util.Set.of(locked.externalId()));
        return new CatalogProductReviewDecisionResult(productId, category.getCode(),
                decision.payrollCategoryCode().name(), reconciled.reclassifiedItems(),
                reconciled.affectedStoreIds());
    }

    private record LockedProduct(UUID connectionId, String externalId, long version, Instant createdAt) { }
    private record FirstSale(Instant occurredAt, LocalDate businessDate) { }
    private record ExistingAnalytics(String code, String conditionType, Instant validFrom, Instant validTo) { }
    private record ExistingPayroll(String code, LocalDate validFrom, LocalDate validTo) { }
}
