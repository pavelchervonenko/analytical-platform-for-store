package com.storeanalytics.quality.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.model.StoreSchedule;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "app.reports.backfill.worker-enabled=false",
        "app.reports.annual-scheduling-enabled=false",
        "app.sync.worker-enabled=false",
        "app.sync.schedule-enabled=false",
        "app.maintenance.retention.scheduling-enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StorePeriodQualityTransactionIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private StorePeriodQualityService service;

    @Autowired
    private StoreRepository storeRepository;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void reportsMissingPlanWithoutRollingBackQualityInspection() {
        Store store = Store.manual(
                "quality-no-plan-" + UUID.randomUUID(),
                "Quality store without plan",
                null,
                new StoreSchedule(
                        "Europe/Moscow",
                        LocalTime.MIDNIGHT,
                        LocalTime.of(10, 0),
                        LocalTime.of(21, 0)
                )
        );
        storeRepository.saveAndFlush(store);

        StorePeriodQualityView result = service.inspect(
                store.getId(),
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 7, 20)
        );

        assertThat(result.storePlan().planPresent()).isFalse();
        assertThat(result.issues())
                .extracting(PeriodQualityIssueView::code)
                .contains("STORE_PLAN_MISSING");
    }
}
