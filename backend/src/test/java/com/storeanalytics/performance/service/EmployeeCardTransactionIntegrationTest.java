package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import(EmployeeCardTransactionIntegrationTest.TestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmployeeCardTransactionIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private EmployeeCardService service;

    @Autowired
    @Qualifier("employeeCardRatingService")
    private EmployeeRatingQueryService ratingService;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void fullMonthCardCommitsWhenPayrollHasNotBeenCalculated() {
        UUID storeId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        StoreKpiPeriod currentPeriod = new StoreKpiPeriod(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)
        );
        StoreKpiPeriod previousPeriod = new StoreKpiPeriod(
                LocalDate.of(2026, 5, 31), LocalDate.of(2026, 6, 30)
        );
        EmployeeRatingEntry employee = employee(employeeId);
        when(ratingService.get(storeId, currentPeriod))
                .thenReturn(result(storeId, currentPeriod, employee));
        when(ratingService.get(storeId, previousPeriod))
                .thenReturn(result(storeId, previousPeriod));

        EmployeeCardView card = service.card(storeId, employeeId, currentPeriod);

        assertThat(card.payroll()).isNull();
        assertThat(card.current().employeeId()).isEqualTo(employeeId);
    }

    private EmployeeRatingResult result(
            UUID storeId,
            StoreKpiPeriod period,
            EmployeeRatingEntry... employees
    ) {
        return new EmployeeRatingResult(
                storeId,
                period.start(),
                period.end(),
                mock(RatingFormulaView.class),
                mock(RatingPlanContext.class),
                List.of(employees),
                EmployeeRatingHistoryView.live()
        );
    }

    private EmployeeRatingEntry employee(UUID employeeId) {
        EmployeeRatingEntry employee = mock(EmployeeRatingEntry.class);
        RatingScoreBreakdown scores = mock(RatingScoreBreakdown.class);
        when(employee.employeeId()).thenReturn(employeeId);
        when(employee.displayName()).thenReturn("Алина");
        when(employee.rank()).thenReturn(1);
        when(employee.scores()).thenReturn(scores);
        when(scores.overallScore()).thenReturn(new BigDecimal("100.00"));
        when(employee.attachRates()).thenReturn(List.of());
        return employee;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        EmployeeRatingQueryService employeeCardRatingService() {
            return mock(EmployeeRatingQueryService.class);
        }
    }
}
