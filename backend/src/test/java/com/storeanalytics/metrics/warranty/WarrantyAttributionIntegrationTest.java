package com.storeanalytics.metrics.warranty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.metrics.service.AttachRateEntry;
import com.storeanalytics.metrics.service.AttachRateService;
import com.storeanalytics.metrics.service.SellerPeriodAnalyticsService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "app.attach.attribution-enabled=true")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class WarrantyAttributionIntegrationTest {
    private static final LocalDate AUGUST = LocalDate.of(2026, 8, 10);
    private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 10);
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WarrantyService service;
    @Autowired
    private WarrantyRepository repository;
    @Autowired
    private AttachRateService rates;
    @Autowired
    private SellerPeriodAnalyticsService sellerAnalytics;
    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired
    private com.storeanalytics.auth.repository.AppUserRepository users;
    @Autowired
    private com.storeanalytics.interpretation.review.WeeklyReviewService reviews;
    @Autowired
    private com.storeanalytics.interpretation.review.WeeklyReviewSnapshotStore snapshots;
    private UUID connection;
    private UUID store;
    private UUID sync;
    private UUID actor;
    private UUID seller;
    private UUID processor;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void fixture() {
        connection = jdbc.queryForObject("SELECT id FROM integration_connections LIMIT 1", UUID.class);
        store = UUID.randomUUID();
        sync = UUID.randomUUID();
        actor = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO stores (id, connection_id, source_system, external_id, name)
                VALUES (?, ?, 'LIVESKLAD', ?, 'Warranty test')
                """, store, connection, store.toString());
        jdbc.update("""
                INSERT INTO sync_runs (id, connection_id, source_system, trigger_type, sync_scope, status,
                    started_at, finished_at) VALUES (?, ?, 'LIVESKLAD', 'MANUAL', 'SALES', 'SUCCESS', now(), now())
                """, sync, connection);
        jdbc.update("""
                INSERT INTO app_users (id, email, password_hash, display_name, role, password_change_required)
                VALUES (?, ?, 'not-a-login-hash', 'Test manager', 'ADMIN', false)
                """, actor, actor + "@example.com");
        seller = employee();
        processor = employee();
    }

    @Test
    void deviceTypeOverridesWarrantyNameAndCareNeverEntersWarrantyQueue() {
        UUID doc = sale(AUGUST);
        item(doc, "Used iPhone", "IPHONE_USED", "USED", "2", null);
        UUID warranty = item(doc, "Check new", "WARRANTY_GENERIC", "NOT_APPLICABLE", "3", null);
        item(doc, "FUTURE STORE Privilege  Care", "WARRANTY_GENERIC", "NOT_APPLICABLE", "1", null);
        assertThat(service.detail(store, warranty).warranty().state()).isEqualTo("RESOLVED_AUTO");
        assertRate("WARRANTY_GENERIC_USED", AUGUST, "3", "2", "150");
        assertRate("PREMIUM_PROTECTION", AUGUST, "1", "2", "50");
        assertThat(service.queue(store, "ALL", 0, 30).total()).isEqualTo(1);
    }

    @Test
    void separateWarrantyChangesOriginalPeriodOnlyAndLeavesFinancialFactsUntouched() {
        rank(seller);
        UUID origin = sale(AUGUST);
        UUID device = item(origin, "iPhone", "IPHONE_NEW_ASIS", "ASIS", "2", null);
        UUID warrantyDoc = sale(SEPTEMBER);
        jdbc.update("UPDATE sales_documents SET employee_id = ? WHERE id = ?", processor, warrantyDoc);
        UUID warranty = item(warrantyDoc, "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "1", null);
        List<Map<String, Object>> financial = financial();
        WarrantyViews.Detail result = decide(warranty, List.of(allocation(device, "1")), null);
        assertThat(result.warranty().state()).isEqualTo("RESOLVED_MANUAL");
        assertRate("WARRANTY_GENERIC_NEW", AUGUST, "1", "2", "50");
        assertThat(rate("WARRANTY_GENERIC_NEW", SEPTEMBER).numeratorQuantity()).isZero();
        assertThat(result.allocations().getFirst().employeeId()).isEqualTo(seller);
        assertThat(financial()).isEqualTo(financial);
        assertThat(result.history()).hasSize(1);
        var sellerFacts = sellerAnalytics.readComparison(store, month(AUGUST), month(SEPTEMBER));
        assertThat(sellerFacts.current().attachRates())
                .filteredOn(value -> value.metricCode().equals("WARRANTY_GENERIC_NEW"))
                .singleElement().satisfies(value -> {
                    assertThat(value.numeratorReceiptCount()).isEqualByComparingTo("1");
                    assertThat(value.denominatorReceiptCount()).isEqualByComparingTo("2");
                    assertThat(value.preliminary()).isFalse();
                });
        // September money belongs to the excluded warranty seller, August attach to the device seller.
        assertThat(sellerFacts.previous().metrics().totals().netRevenue()).isZero();
        assertThat(sellerFacts.previous().attachRates())
                .filteredOn(value -> value.metricCode().equals("WARRANTY_GENERIC_NEW"))
                .singleElement().satisfies(value -> assertThat(value.numeratorReceiptCount()).isZero());
    }

    @Test
    void mixedPartialReturnRequiresManualSelectionAndReducesOriginalPeriod() {
        UUID doc = sale(AUGUST);
        UUID used = item(doc, "Used", "IPHONE_USED", "USED", "2", null);
        UUID fresh = item(doc, "New", "SAMSUNG_NEW", "NEW", "1", null);
        UUID warranty = item(doc, "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "3", null);
        assertThat(rate("WARRANTY_GENERIC_USED", AUGUST).preliminary()).isTrue();
        decide(warranty, List.of(allocation(used, "2"), allocation(fresh, "1")), null);
        UUID refund = document("RETURN", SEPTEMBER, doc);
        UUID returned = item(refund, "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "1", warranty);
        assertThat(service.detail(store, returned).warranty().conflictCode()).isEqualTo("RETURN_ALLOCATION_REQUIRED");
        decide(returned, List.of(allocation(used, "1")), warranty);
        assertRate("WARRANTY_GENERIC_USED", AUGUST, "1", "2", "50");
        assertRate("WARRANTY_GENERIC_NEW", AUGUST, "1", "1", "100");
        assertThat(rate("WARRANTY_GENERIC_USED", AUGUST).preliminary()).isFalse();
    }

    @Test
    void fullReturnInheritsAllAllocationsAndDeviceReturnRestatesOnlyWarrantyBase() {
        UUID doc = sale(AUGUST);
        UUID device = item(doc, "New", "SAMSUNG_NEW", "NEW", "2", null);
        UUID warranty = item(doc, "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "2", null);
        UUID refund = document("RETURN", SEPTEMBER, doc);
        item(refund, "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "2", warranty);
        item(refund, "New", "SAMSUNG_NEW", "NEW", "1", device);
        assertRate("WARRANTY_GENERIC_NEW", AUGUST, "0", "1", "0");
        assertThat(rate("CASE_SAMSUNG", AUGUST).denominatorQuantity()).isEqualByComparingTo("2");
        assertThat(rate("CASE_SAMSUNG", SEPTEMBER).denominatorQuantity()).isEqualByComparingTo("-1");
    }

    @Test
    void noOpSyncPreservesDecisionButMaterialTargetChangeReopensIt() {
        UUID device = item(sale(AUGUST), "New", "SAMSUNG_NEW", "NEW", "2", null);
        UUID warranty = item(sale(SEPTEMBER), "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "1", null);
        WarrantyViews.Detail before = decide(warranty, List.of(allocation(device, "1")), null);
        jdbc.update("UPDATE sales_document_items SET updated_at = now(), version = version + 1 WHERE id = ?", device);
        assertThat(service.detail(store, warranty).warranty().state()).isEqualTo("RESOLVED_MANUAL");
        jdbc.update("UPDATE sales_document_items SET quantity = 3 WHERE id = ?", device);
        assertThat(service.detail(store, warranty).warranty().conflictCode()).isEqualTo("SOURCE_CHANGED");
        assertThat(WarrantyService.etag(service.detail(store, warranty).warranty()))
                .isNotEqualTo(WarrantyService.etag(before.warranty()));
        assertThat(rate("WARRANTY_GENERIC_NEW", AUGUST).numeratorQuantity()).isZero();
        assertThat(before.history()).hasSize(1);
    }

    @Test
    void validatesFullQuantityAndOptimisticVersionAndRetriesIdempotently() {
        UUID device = item(sale(AUGUST), "New", "SAMSUNG_NEW", "NEW", "2", null);
        UUID warranty = item(sale(SEPTEMBER), "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "2", null);
        assertThatThrownBy(() -> service.preview(store, warranty,
                request(List.of(allocation(device, "1")), null))).isInstanceOf(InvalidRequestException.class);
        WarrantyViews.Case source = repository.find(store, warranty);
        WarrantyDecisionRequest command = request(List.of(allocation(device, "2")), null);
        String key = UUID.randomUUID().toString();
        WarrantyViews.Detail first = service.decide(store, warranty, command, WarrantyService.etag(source), key, actor);
        WarrantyViews.Detail repeated = service.decide(store, warranty, command,
                WarrantyService.etag(source), key, actor);
        assertThat(repeated.warranty().revision()).isEqualTo(first.warranty().revision());
        assertThatThrownBy(() -> service.decide(store, warranty, command, WarrantyService.etag(source),
                UUID.randomUUID().toString(), actor)).isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void separateOrdinaryReturnUsesSourceEmployeeWithoutChangingStoredEmployee() {
        rank(processor);
        UUID doc = sale(AUGUST);
        UUID accessory = item(doc, "Case", "CASE_SAMSUNG", "NOT_APPLICABLE", "1", null);
        UUID refund = document("RETURN", SEPTEMBER, doc);
        item(refund, "Case", "CASE_SAMSUNG", "NOT_APPLICABLE", "1", accessory);
        jdbc.update("UPDATE sales_documents SET attach_source_employee_external_id = ? WHERE id = ?",
                processor.toString(), refund);
        UUID attributed = jdbc.queryForObject("""
                SELECT employee_id FROM attach_rate_item_facts_v4
                WHERE store_id = ? AND business_date = ? AND numerator_metric_code = 'CASE_SAMSUNG'
                """, UUID.class, store, SEPTEMBER);
        assertThat(attributed).isEqualTo(processor);
        assertThat(jdbc.queryForObject("SELECT employee_id FROM sales_documents WHERE id = ?", UUID.class, refund))
                .isEqualTo(seller);
        var selected = sellerAnalytics.readComparison(store, month(SEPTEMBER), month(AUGUST));
        assertThat(selected.current().metrics().totals().netRevenue()).isEqualByComparingTo("-100");
        assertThat(selected.current().attachRates())
                .filteredOn(value -> value.metricCode().equals("CASE_SAMSUNG"))
                .singleElement().satisfies(value ->
                        assertThat(value.numeratorReceiptCount()).isEqualByComparingTo("-1"));
    }

    @Test
    void sellerAttachKeepsUnknownAuthorQualityWithoutUsingFinancialAuthorOrStoreNumerator() {
        rank(seller);
        UUID origin = sale(AUGUST);
        UUID accessory = item(origin, "Case", "CASE_SAMSUNG", "NOT_APPLICABLE", "1", null);
        item(document("RETURN", SEPTEMBER, origin), "Case", "CASE_SAMSUNG",
                "NOT_APPLICABLE", "1", accessory);

        var facts = sellerAnalytics.readComparison(store, month(SEPTEMBER), month(AUGUST));

        assertThat(facts.current().metrics().totals().netRevenue()).isZero();
        assertThat(facts.current().returnAttribution().missingReturnEmployeeCount()).isOne();
        assertThat(facts.current().attachRates())
                .filteredOn(value -> value.metricCode().equals("CASE_SAMSUNG"))
                .singleElement().satisfies(value -> {
                    assertThat(value.numeratorReceiptCount()).isZero();
                    assertThat(value.unassignedMetricReturnItemCount()).isOne();
                    assertThat(value.preliminary()).isTrue();
                });
        assertThat(facts.current().attachRates())
                .filteredOn(value -> value.metricCode().equals("CHARGER_CABLE"))
                .singleElement().satisfies(value -> assertThat(value.preliminary()).isFalse());
        jdbc.update("UPDATE employee_store_assignments SET participates_in_ranking = false WHERE store_id = ?", store);
        var empty = sellerAnalytics.readComparison(store, month(SEPTEMBER), month(AUGUST));
        assertThat(empty.current().metrics().totals().netRevenue()).isZero();
        assertThat(empty.current().attachRates()).allSatisfy(value -> {
            assertThat(value.numeratorReceiptCount()).isZero();
            assertThat(value.denominatorReceiptCount()).isZero();
        });
    }

    @Test
    void returnOutsideRankingCannotContaminateSellerQuantityOrLookFinal() {
        rank(seller);
        UUID sellerSale = sale(SEPTEMBER);
        item(sellerSale, "Seller case", "CASE_SAMSUNG", "NOT_APPLICABLE", "1", null);
        UUID outsideSale = sale(AUGUST);
        jdbc.update("UPDATE sales_documents SET employee_id = ? WHERE id = ?", processor, outsideSale);
        UUID outsideItem = item(outsideSale, "Other case", "CASE_SAMSUNG", "NOT_APPLICABLE", "1", null);
        item(document("RETURN", SEPTEMBER, outsideSale), "Other case", "CASE_SAMSUNG",
                "NOT_APPLICABLE", "1", outsideItem);

        var facts = sellerAnalytics.readComparison(store, month(SEPTEMBER), month(AUGUST));

        assertThat(facts.current().attachRates())
                .filteredOn(value -> value.metricCode().equals("CASE_SAMSUNG"))
                .singleElement().satisfies(value -> {
                    assertThat(value.numeratorReceiptCount()).isEqualByComparingTo("1");
                    assertThat(value.unassignedMetricReturnItemCount()).isOne();
                    assertThat(value.preliminary()).isTrue();
                });
        assertThat(rate("CASE_SAMSUNG", SEPTEMBER).numeratorQuantity()).isZero();
    }

    private StoreKpiPeriod month(LocalDate date) {
        return new StoreKpiPeriod(date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()));
    }

    private void rank(UUID employee) {
        jdbc.update("""
                INSERT INTO employee_store_assignments (employee_id, store_id, is_active, participates_in_ranking)
                VALUES (?, ?, true, true)
                """, employee, store);
    }

    @Test
    void earpodsDoNotIncreaseAirpodsBaseAndUnsupportedDevicesStayInConflict() {
        UUID doc = sale(AUGUST);
        item(doc, "Apple EarPods", "PODS_WATCH_OTHER_DEVICE", "NEW", "1", null);
        UUID warranty = item(doc, "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "1", null);
        assertThat(rate("ACCESSORY_PODS_WATCH", AUGUST).denominatorQuantity()).isZero();
        assertThat(rate("PREMIUM_PROTECTION", AUGUST).denominatorQuantity()).isEqualByComparingTo("1");
        assertThat(service.detail(store, warranty).warranty().conflictCode()).isEqualTo("UNSUPPORTED_DEVICE");
    }

    @Test
    void fullyReturnedUnresolvedWarrantyHasNoPendingActiveQuantity() {
        UUID doc = sale(AUGUST);
        UUID warranty = item(doc, "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "2", null);
        item(document("RETURN", SEPTEMBER, doc), "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "2", warranty);
        assertThat(service.queue(store, "OPEN", 0, 30).unallocatedQuantity()).isZero();
        assertThat(rate("WARRANTY_GENERIC_NEW", AUGUST).preliminary()).isFalse();
    }

    @Test
    void exclusionDeferralAndManualOrphanReturnPreserveHistory() {
        UUID doc = sale(AUGUST);
        UUID device = item(doc, "New", "SAMSUNG_NEW", "NEW", "2", null);
        UUID warranty = item(doc, "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "2", null);
        UUID returned = item(document("RETURN", SEPTEMBER, null), "Check",
                "WARRANTY_GENERIC", "NOT_APPLICABLE", "1", null);
        WarrantyViews.Detail result = decide(returned, List.of(allocation(device, "1")), warranty);
        assertThat(result.warranty().state()).isEqualTo("RESOLVED_MANUAL");
        assertRate("WARRANTY_GENERIC_NEW", AUGUST, "1", "2", "50");
        for (WarrantyDecisionRequest.Action action : List.of(WarrantyDecisionRequest.Action.DEFER,
                WarrantyDecisionRequest.Action.EXCLUDE)) {
            WarrantyDecisionRequest command = new WarrantyDecisionRequest(action, "Проверка истории", null, List.of());
            result = service.decide(store, returned, command, WarrantyService.etag(result.warranty()),
                    UUID.randomUUID().toString(), actor);
        }
        assertThat(result.warranty().state()).isEqualTo("EXCLUDED");
        assertThat(result.history()).hasSize(3);
        assertThat(result.history().getLast().allocations()).hasSize(1);
        assertRate("WARRANTY_GENERIC_NEW", AUGUST, "2", "2", "100");
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    @Test
    void databaseRejectsIncompleteAllocationAtCommitBoundary() {
        UUID warranty = item(sale(AUGUST), "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "2", null);
        repository.save(repository.find(store, warranty), request(List.of(), null), actor);
        assertThatThrownBy(() -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void searchAndDecisionsRejectAnotherStoresDevice() {
        UUID other = UUID.randomUUID();
        UUID device = item(sale(AUGUST), "New", "SAMSUNG_NEW", "NEW", "2", null);
        assertThatThrownBy(() -> repository.device(other, device)).isInstanceOf(WarrantyCaseNotFoundException.class);
        assertThat(repository.search(other, "New")).isEmpty();
        UUID warranty = item(sale(AUGUST), "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "1", null);
        assertThatThrownBy(() -> service.detail(other, warranty)).isInstanceOf(WarrantyCaseNotFoundException.class);
    }

    @Test
    void managerWithoutOptionalFeaturesCanReviewOwnStoreButCannotReadOrWriteAnother() throws Exception {
        jdbc.update("UPDATE app_users SET role = 'MANAGER' WHERE id = ?", actor);
        jdbc.update("INSERT INTO user_store_access (user_id, store_id) VALUES (?, ?)", actor, store);
        var principal = com.storeanalytics.auth.security.AppUserPrincipal.from(users.findById(actor).orElseThrow());
        var authenticated = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user(principal);
        String base = "/api/stores/" + store + "/attach-rate/warranties";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base).with(authenticated))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        String other = "/api/stores/" + UUID.randomUUID() + "/attach-rate/warranties";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(other).with(authenticated))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        UUID source = item(sale(AUGUST), "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "1", null);
        String command = """
                {"action":"EXCLUDE","reason":"Неподходящий тип устройства","allocations":[]}
                """;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base + "/" + source
                        + "/decisions").with(authenticated)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .header("If-Match", WarrantyService.etag(repository.find(store, source)))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(command))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(other + "/" + source
                        + "/decisions").with(authenticated)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(command))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void allMetricsFollowQuantityBasesAndCareAndServiceBoundaries() {
        UUID newDoc = sale(AUGUST);
        item(newDoc, "iPhone ASIS+", "IPHONE_NEW_ASIS", "ASIS", "2", null);
        item(newDoc, "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "1", null);
        UUID usedDoc = sale(AUGUST);
        item(usedDoc, "iPhone used", "IPHONE_USED", "USED", "3", null);
        item(usedDoc, "Check new", "WARRANTY_GENERIC", "NOT_APPLICABLE", "1", null);
        item(sale(AUGUST), "Samsung", "SAMSUNG_NEW", "NEW", "5", null);
        item(sale(AUGUST), "Samsung used", "SAMSUNG_USED", "USED", "7", null);
        UUID doc = sale(AUGUST);
        item(doc, "iPad", "IPAD_MAC", "NEW", "13", null);
        item(doc, "MacBook", "IPAD_MAC", "NEW", "17", null);
        item(doc, "iMac", "IPAD_MAC", "NEW", "19", null);
        item(doc, "AirPods", "PODS_WATCH_OTHER_DEVICE", "NEW", "23", null);
        item(doc, "Apple Watch", "PODS_WATCH_OTHER_DEVICE", "NEW", "29", null);
        item(doc, "EarPods", "PODS_WATCH_OTHER_DEVICE", "NEW", "31", null);
        item(doc, "PlayStation", "PODS_WATCH_OTHER_DEVICE", "NEW", "37", null);
        item(doc, "Dyson", "PODS_WATCH_OTHER_DEVICE", "NEW", "41", null);
        for (String code : List.of("CASE_APPLE_IPHONE", "CASE_SAMSUNG", "GLASS_IPHONE", "GLASS_CAMERA_IPHONE",
                "GLASS_SAMSUNG", "GLASS_CAMERA_SAMSUNG", "CHARGER_CABLE", "FILM_PHONE", "ACCESSORY_PODS_WATCH")) {
            item(doc, code, code, "NOT_APPLICABLE", "1", null);
        }
        item(doc, "Apple Pencil", "ACCESSORY_IPAD_MAC", "NOT_APPLICABLE", "1", null);
        item(doc, "Настройка телефона", "SETUP_SERVICE", "NOT_APPLICABLE", "1", null);
        item(doc, "Ремонт телефона", "SETUP_SERVICE", "NOT_APPLICABLE", "17", null);
        item(doc, "Адаптер USB-C", "OTHER_ACCESSORY_PRODUCT", "NOT_APPLICABLE", "1", null);
        item(doc, "Privilege Care", "WARRANTY_GENERIC", "NOT_APPLICABLE", "1", null);
        item(doc, "Elite Care", "PREMIUM_PROTECTION", "NOT_APPLICABLE", "1", null);
        Map<String, String> bases = Map.ofEntries(
                Map.entry("CASE_APPLE_IPHONE", "5"), Map.entry("CASE_SAMSUNG", "12"),
                Map.entry("GLASS_IPHONE", "5"), Map.entry("GLASS_CAMERA_IPHONE", "5"),
                Map.entry("GLASS_SAMSUNG", "12"), Map.entry("GLASS_CAMERA_SAMSUNG", "12"),
                Map.entry("CHARGER_CABLE", "17"), Map.entry("POWER_BANK", "17"),
                Map.entry("FILM_PHONE", "17"),
                Map.entry("ACCESSORY_PODS_WATCH", "52"), Map.entry("ACCESSORY_IPAD", "13"),
                Map.entry("ACCESSORY_AIRPODS", "23"), Map.entry("ACCESSORY_APPLE_WATCH", "29"),
                Map.entry("SETUP_SERVICE", "71"), Map.entry("PREMIUM_PROTECTION", "186"),
                Map.entry("WARRANTY_GENERIC_NEW", "7"), Map.entry("WARRANTY_GENERIC_USED", "10"));
        List<AttachRateEntry> entries = rates.calculate(store, new StoreKpiPeriod(AUGUST.withDayOfMonth(1),
                AUGUST.withDayOfMonth(31))).rates();
        assertThat(entries).hasSize(17);
        for (AttachRateEntry entry : entries) {
            assertThat(entry.denominatorQuantity()).as(entry.metricCode())
                    .isEqualByComparingTo(bases.get(entry.metricCode()));
            String expectedNumerator = switch (entry.metricCode()) {
                // Before activation, legacy v4 also counts the generic USB-C adapter.
                // Boundary-specific exclusion is covered by CatalogChargerAdapterCutoverIntegrationTest.
                case "PREMIUM_PROTECTION", "CHARGER_CABLE" -> "2";
                case "POWER_BANK", "ACCESSORY_AIRPODS", "ACCESSORY_APPLE_WATCH" -> "0";
                default -> "1";
            };
            assertThat(entry.numeratorQuantity()).as(entry.metricCode())
                    .isEqualByComparingTo(expectedNumerator);
        }
    }

    @Test
    void boundedBackfillUsesOnlyMatchingRetainedEvidenceAndPreservesMoney() throws Exception {
        UUID doc = document("RETURN", SEPTEMBER, null);
        UUID raw = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO raw_record_versions (id, connection_id, store_id, source_system, entity_type,
                    external_id, payload, payload_hash, first_sync_run_id, last_sync_run_id)
                VALUES (?, ?, ?, 'LIVESKLAD', 'RETURN_DOCUMENT', ?,
                    jsonb_build_object('detail', jsonb_build_object('id', ?::text,
                        'customer', jsonb_build_object('id', ?::text))), repeat('a',64), ?, ?)
                """, raw, connection, store, doc.toString(), doc.toString(), processor.toString(), sync, sync);
        jdbc.update("UPDATE sales_documents SET raw_record_version_id = ? WHERE id = ?", raw, doc);
        List<Map<String, Object>> before = financial();
        String script = java.nio.file.Files.readString(
                java.nio.file.Path.of("../scripts/attach-source-employee-backfill.sql"))
                .replaceAll("(?m)^\\\\set.*$", "").replace("BEGIN;", "").replace("COMMIT;", "")
                .replace(":'store_id'", "'" + store + "'")
                .replace(":'period_start'", "'2026-09-01'").replace(":'period_end'", "'2026-09-30'")
                .replace(":'after_id'", "'00000000-0000-0000-0000-000000000000'");
        jdbc.execute(script.replace(":'apply'", "'false'"));
        assertThat(jdbc.queryForObject("SELECT attach_source_employee_external_id FROM sales_documents WHERE id = ?",
                String.class, doc)).isNull();
        jdbc.execute(script.replace(":'apply'", "'true'"));
        assertThat(jdbc.queryForObject("SELECT attach_source_employee_external_id FROM sales_documents WHERE id = ?",
                String.class, doc)).isEqualTo(processor.toString());
        jdbc.execute(script.replace(":'apply'", "'true'"));
        assertThat(financial()).isEqualTo(before);
    }

    @Test
    void aggregateRemainsBoundedForOneThousandAutomaticDocuments() {
        UUID doc = sale(AUGUST);
        UUID device = item(doc, "New", "SAMSUNG_NEW", "NEW", "1", null);
        UUID warranty = item(doc, "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "1", null);
        jdbc.update("""
                WITH documents AS (
                    INSERT INTO sales_documents (id, connection_id, external_id, store_id, employee_id,
                        document_kind, source_document_type, occurred_at, business_date, net_amount, cost_amount,
                        last_sync_run_id)
                    SELECT gen_random_uuid(), ?, gen_random_uuid()::text, ?, ?, 'SALE', 'sale', ?::date, ?, 100, 50, ?
                    FROM generate_series(1, 1000) RETURNING id
                )
                INSERT INTO sales_document_items (id, sales_document_id, external_id, product_id,
                    product_name_snapshot, analytics_category_id, condition_type_snapshot,
                    quantity, unit_price, gross_amount, net_amount, cost_amount, cost_quality)
                SELECT gen_random_uuid(), d.id, gen_random_uuid()::text, template.product_id,
                    template.product_name_snapshot, template.analytics_category_id, template.condition_type_snapshot,
                    1, 100, 100, 100, 50, 'KNOWN'
                FROM documents d CROSS JOIN sales_document_items template WHERE template.id IN (?, ?)
                """, connection, store, seller, AUGUST, AUGUST, sync, device, warranty);
        jdbc.execute("SET LOCAL statement_timeout = '10s'");
        assertRate("WARRANTY_GENERIC_NEW", AUGUST, "1001", "1001", "100");
    }

    @Test
    void decisionInvalidatesCurrentReviewAndUnchangedContentIsAcknowledged() {
        var first = reviews.generate(store);
        UUID device = item(sale(AUGUST), "New", "SAMSUNG_NEW", "NEW", "2", null);
        UUID warranty = item(sale(SEPTEMBER), "Check", "WARRANTY_GENERIC", "NOT_APPLICABLE", "1", null);
        decide(warranty, List.of(allocation(device, "1")), null);
        assertThat(snapshots.attributionChangedSince(store, first.id(),
                first.response().provenance().calculatedAt())).isTrue();
        var current = reviews.current(store).orElseThrow();
        assertThat(snapshots.currentAttributionPolicy(current.versions())).isTrue();
        assertThat(snapshots.attributionChangedSince(store, UUID.fromString(current.provenance().snapshotPublicId()),
                current.provenance().calculatedAt())).isFalse();
        assertThat(reviews.current(store).orElseThrow().provenance().snapshotPublicId())
                .isEqualTo(current.provenance().snapshotPublicId());
    }

    private WarrantyViews.Detail decide(UUID source, List<WarrantyDecisionRequest.Allocation> allocations,
                                        UUID original) {
        return service.decide(store, source, request(allocations, original),
                WarrantyService.etag(repository.find(store, source)), UUID.randomUUID().toString(), actor);
    }

    private WarrantyDecisionRequest request(List<WarrantyDecisionRequest.Allocation> allocations, UUID original) {
        return new WarrantyDecisionRequest(WarrantyDecisionRequest.Action.ALLOCATE,
                "Verified device", original, allocations);
    }

    private WarrantyDecisionRequest.Allocation allocation(UUID device, String quantity) {
        return new WarrantyDecisionRequest.Allocation(device, new BigDecimal(quantity),
                repository.device(store, device).fingerprint());
    }

    private AttachRateEntry rate(String code, LocalDate date) {
        return rates.calculate(store, new StoreKpiPeriod(
                date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth())))
                .rates().stream().filter(value -> value.metricCode().equals(code)).findFirst().orElseThrow();
    }

    private void assertRate(String code, LocalDate date, String numerator, String denominator, String percentage) {
        AttachRateEntry value = rate(code, date);
        assertThat(value.numeratorQuantity()).isEqualByComparingTo(numerator);
        assertThat(value.denominatorQuantity()).isEqualByComparingTo(denominator);
        assertThat(value.ratePerHundred()).isEqualByComparingTo(percentage);
    }

    private List<Map<String, Object>> financial() {
        return jdbc.queryForList("""
                SELECT d.id, d.business_date, d.employee_id, d.net_amount, d.cost_amount,
                       i.id AS item_id, i.analytics_category_id, i.net_amount AS item_net, i.cost_amount AS item_cost
                FROM sales_documents d JOIN sales_document_items i ON i.sales_document_id = d.id
                WHERE d.store_id = ? ORDER BY d.id, i.id
                """, store);
    }

    private UUID employee() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO employees (id, connection_id, external_id, full_name) VALUES (?, ?, ?, ?)",
                id, connection, id.toString(), "Synthetic employee");
        return id;
    }

    private UUID sale(LocalDate date) {
        return document("SALE", date, null);
    }

    private UUID document(String kind, LocalDate date, UUID original) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sales_documents (id, connection_id, external_id, store_id, employee_id,
                    original_document_id, document_kind, source_document_type, occurred_at, business_date,
                    net_amount, cost_amount, last_sync_run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'sale', ?::date, ?, 100, 50, ?)
                """, id, connection, id.toString(), store, seller, original, kind, date, date, sync);
        return id;
    }

    private UUID item(UUID doc, String name, String category, String condition, String quantity, UUID original) {
        UUID id = UUID.randomUUID();
        UUID product = original == null ? UUID.randomUUID()
                : jdbc.queryForObject("SELECT product_id FROM sales_document_items WHERE id = ?", UUID.class, original);
        if (original == null) {
            jdbc.update("INSERT INTO products (id, connection_id, external_id, name) VALUES (?, ?, ?, ?)",
                    product, connection, product.toString(), name);
        }
        jdbc.update("""
                INSERT INTO sales_document_items (id, sales_document_id, external_id, product_id,
                    original_item_id, product_name_snapshot, analytics_category_id, condition_type_snapshot,
                    quantity, unit_price, gross_amount, net_amount, cost_amount, cost_quality)
                SELECT ?, ?, ?, ?, ?, ?, id, ?, ?, 100, 100, 100, 50, 'KNOWN'
                FROM analytics_categories WHERE code = ?
                """, id, doc, id.toString(), product, original, name, condition, new BigDecimal(quantity), category);
        return id;
    }
}
