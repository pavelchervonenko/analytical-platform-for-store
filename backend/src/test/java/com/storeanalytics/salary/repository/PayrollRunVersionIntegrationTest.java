package com.storeanalytics.salary.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.salary.model.PayrollPlanResult;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollRunDefinition;
import com.storeanalytics.salary.model.PayrollRunQuality;
import com.storeanalytics.salary.model.PayrollScheme;
import com.storeanalytics.salary.model.PayrollSourceFingerprint;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PayrollRunVersionIntegrationTest {

    private static final UUID STORE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002101"
    );
    private static final UUID ACTOR_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002102"
    );
    private static final LocalDate MONTH = LocalDate.of(2026, 7, 1);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @org.springframework.test.context.DynamicPropertySource
    static void configurePostgres(
            org.springframework.test.context.DynamicPropertyRegistry registry
    ) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PayrollSchemeRepository schemeRepository;

    @Autowired
    private PayrollRunRepository runRepository;

    @Test
    @Transactional
    @Rollback
    void unchangedRecalculationAdvancesJpaOptimisticVersion() {
        jdbcTemplate.update(
                """
                INSERT INTO stores (id, source_system, external_id, name)
                VALUES (?, 'MANUAL', 'payroll-version-test', 'Payroll version test')
                """,
                STORE_ID
        );
        jdbcTemplate.update(
                """
                INSERT INTO app_users (
                    id, email, password_hash, display_name, role,
                    password_change_required
                ) VALUES (?, 'payroll-version@example.invalid',
                          '{bcrypt}test', 'Payroll version actor', 'ADMIN', false)
                """,
                ACTOR_ID
        );
        Store store = storeRepository.findById(STORE_ID).orElseThrow();
        AppUser actor = userRepository.findById(ACTOR_ID).orElseThrow();
        PayrollScheme scheme = schemeRepository
                .findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(MONTH)
                .orElseThrow();
        PayrollRunDefinition definition = definition(store, actor, scheme);
        PayrollRun run = runRepository.saveAndFlush(new PayrollRun(definition));

        assertThat(run.getVersion()).isZero();
        assertThat(run.getCalculationGeneration()).isEqualTo(1);

        run.recalculate(definition);
        run = runRepository.saveAndFlush(run);

        assertThat(run.getVersion()).isEqualTo(1);
        assertThat(run.getCalculationGeneration()).isEqualTo(2);
    }

    private PayrollRunDefinition definition(
            Store store,
            AppUser actor,
            PayrollScheme scheme
    ) {
        return new PayrollRunDefinition(
                store,
                MONTH,
                1,
                null,
                null,
                scheme,
                new PayrollPlanResult(
                        new BigDecimal("1000.00"),
                        new BigDecimal("1000.00"),
                        true,
                        new BigDecimal("3.90"),
                        new BigDecimal("39.00"),
                        new BigDecimal("3.90"),
                        true,
                        new BigDecimal("3.00"),
                        new BigDecimal("30.00"),
                        new BigDecimal("3.00"),
                        true
                ),
                new PayrollRunQuality(true, 0, 0, 0),
                new PayrollSourceFingerprint(
                        PayrollSourceFingerprint.CURRENT_VERSION,
                        "a".repeat(64),
                        "b".repeat(64),
                        "c".repeat(64),
                        "d".repeat(64),
                        "e".repeat(64)
                ),
                actor
        );
    }
}
