package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.integration.livesklad.dto.LiveSkladEmployeePayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladStorePayload;
import com.storeanalytics.metrics.repository.SellerMembershipHistoryRepository;
import com.storeanalytics.metrics.repository.SellerMembershipHistoryRepository.Eligibility;
import com.storeanalytics.metrics.repository.SellerMembershipHistoryWriter;
import com.storeanalytics.store.repository.StoreRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Import(StoreSyncIntegrationTest.FakeClientConfiguration.class)
class EmployeeSyncMembershipHistoryIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private StoreSyncService stores;
    @Autowired
    private EmployeeSyncService employees;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private StoreSyncIntegrationTest.FakeLiveSkladClient fake;
    @Autowired
    private SellerMembershipHistoryWriter writer;
    @Autowired
    private SellerMembershipHistoryRepository history;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void publishedHistoryTracksDepartureRehireAndNoOpSync() throws Exception {
        String externalStore = "membership-history-fixture";
        String externalEmployee = "membership-history-employee";
        fake.setStores(List.of(new LiveSkladStorePayload(externalStore, "Synthetic store",
                null, null, json.readTree("{}"))));
        var employee = new LiveSkladEmployeePayload(externalEmployee, "Synthetic employee",
                json.readTree("{\"id\":\"membership-history-employee\"}"));
        fake.setEmployees(Map.of(externalStore, List.of(employee)));
        stores.synchronize();
        employees.synchronize();
        UUID storeId = storeRepository.findAll().getFirst().getId();
        UUID employeeId = jdbc.queryForObject("""
                SELECT id FROM employees WHERE external_id = ?
                """, UUID.class, externalEmployee);
        jdbc.update("""
                UPDATE employee_store_assignments SET participates_in_ranking = true
                WHERE store_id = ? AND employee_id = ?
                """, storeId, employeeId);
        var transaction = new TransactionTemplate(transactionManager);
        assertThat((Boolean) transaction.execute(status ->
                writer.bootstrapStore(storeId, "VERIFIED_TEST_BASELINE"))).isTrue();
        Timestamp originalFrom = jdbc.queryForObject("""
                SELECT valid_from FROM seller_membership_history
                WHERE store_id = ? AND employee_id = ? AND valid_to IS NULL
                """, Timestamp.class, storeId, employeeId);
        assertThat(history.eligibilityAt(storeId, employeeId, originalFrom.toInstant()))
                .isEqualTo(Eligibility.ELIGIBLE);

        fake.setEmployees(Map.of(externalStore, List.of()));
        employees.synchronize();
        Timestamp departedFrom = jdbc.queryForObject("""
                SELECT valid_from FROM seller_membership_history
                WHERE store_id = ? AND employee_id = ? AND valid_to IS NULL
                """, Timestamp.class, storeId, employeeId);
        assertThat(history.eligibilityAt(storeId, employeeId, originalFrom.toInstant()))
                .isEqualTo(Eligibility.ELIGIBLE);
        assertThat(history.eligibilityAt(storeId, employeeId, departedFrom.toInstant()))
                .isEqualTo(Eligibility.NOT_ELIGIBLE);

        fake.setEmployees(Map.of(externalStore, List.of(employee)));
        employees.synchronize();
        Timestamp rehiredFrom = jdbc.queryForObject("""
                SELECT valid_from FROM seller_membership_history
                WHERE store_id = ? AND employee_id = ? AND valid_to IS NULL
                """, Timestamp.class, storeId, employeeId);
        assertThat(history.eligibilityAt(storeId, employeeId, rehiredFrom.toInstant()))
                .isEqualTo(Eligibility.ELIGIBLE);
        assertThat(jdbc.queryForObject("""
                SELECT membership_revision FROM store_seller_membership_state WHERE store_id = ?
                """, Long.class, storeId)).isEqualTo(2);
        employees.synchronize();
        assertThat(jdbc.queryForObject("""
                SELECT membership_revision FROM store_seller_membership_state WHERE store_id = ?
                """, Long.class, storeId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM seller_membership_history WHERE store_id = ? AND employee_id = ?
                """, Integer.class, storeId, employeeId)).isEqualTo(3);
    }
}
