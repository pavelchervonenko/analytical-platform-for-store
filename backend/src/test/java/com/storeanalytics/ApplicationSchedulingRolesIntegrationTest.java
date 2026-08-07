package com.storeanalytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ApplicationRuntimeProperties;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.interpretation.config.LlmAnalysisPlanningSchedulingConfiguration;
import com.storeanalytics.interpretation.config.WeeklySnapshotPlanningSchedulingConfiguration;
import com.storeanalytics.interpretation.config.WeeklySnapshotSchedulingConfiguration;
import com.storeanalytics.notification.config.NotificationFanoutSchedulingConfiguration;
import com.storeanalytics.report.config.ReportBackfillSchedulingConfiguration;
import com.storeanalytics.sync.config.SyncWorkerSchedulingConfiguration;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ApplicationSchedulingRolesIntegrationTest {

    private static final Set<String> EXPECTED_SCHEDULERS = Set.of(
            BackgroundSchedulingConfiguration.SYNC_CONTROL_SCHEDULER,
            BackgroundSchedulingConfiguration.LIVESKLAD_PROBE_SCHEDULER,
            BackgroundSchedulingConfiguration.ANNUAL_REPORT_SCHEDULER,
            BackgroundSchedulingConfiguration.RETENTION_SCHEDULER,
            BackgroundSchedulingConfiguration.METRICS_SCHEDULER,
            BackgroundSchedulingConfiguration.CLEANUP_SCHEDULER,
            ReportBackfillSchedulingConfiguration.REPORT_BACKFILL_SCHEDULER,
            LlmAnalysisPlanningSchedulingConfiguration
                    .LLM_ANALYSIS_PLANNING_SCHEDULER,
            WeeklySnapshotPlanningSchedulingConfiguration.SNAPSHOT_PLANNING_SCHEDULER,
            WeeklySnapshotSchedulingConfiguration.SNAPSHOT_WORKER_SCHEDULER,
            WeeklySnapshotSchedulingConfiguration.SNAPSHOT_HEARTBEAT_SCHEDULER,
            NotificationFanoutSchedulingConfiguration.NOTIFICATION_FANOUT_SCHEDULER,
            SyncWorkerSchedulingConfiguration.SYNC_WORKER_SCHEDULER
    );

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void workerAndCombinedRolesStartWithAllSchedulersEnabled() {
        migrateSchema();

        assertSchedulingContextStarts(ApplicationRole.WORKER);
        assertSchedulingContextStarts(ApplicationRole.COMBINED);
    }

    private void assertSchedulingContextStarts(ApplicationRole role) {
        try (ConfigurableApplicationContext context = startApplication(role)) {
            assertThat(context.getBean(ApplicationRuntimeProperties.class).role())
                    .isEqualTo(role);
            assertThat(context.getEnvironment().getProperty(
                    "spring.main.allow-bean-definition-overriding",
                    Boolean.class,
                    false
            )).isFalse();

            Object annualReportComponent = context.getBean(
                    "annualReportScheduler"
            );
            Object annualReportTaskScheduler = context.getBean(
                    BackgroundSchedulingConfiguration.ANNUAL_REPORT_SCHEDULER
            );
            assertThat(annualReportComponent)
                    .isNotInstanceOf(ThreadPoolTaskScheduler.class);
            assertThat(annualReportTaskScheduler)
                    .isInstanceOf(ThreadPoolTaskScheduler.class)
                    .isNotSameAs(annualReportComponent);

            Map<String, ThreadPoolTaskScheduler> schedulers =
                    context.getBeansOfType(ThreadPoolTaskScheduler.class);
            assertThat(schedulers.keySet()).containsAll(EXPECTED_SCHEDULERS);
            assertThat(schedulers.values())
                    .extracting(ThreadPoolTaskScheduler::getThreadNamePrefix)
                    .doesNotHaveDuplicates();
            assertThat(schedulers.values()).allSatisfy(scheduler ->
                    assertThat(scheduler.getScheduledThreadPoolExecutor()
                            .getCorePoolSize()).isEqualTo(1)
            );
        }
    }

    private ConfigurableApplicationContext startApplication(
            ApplicationRole role
    ) {
        WebApplicationType webApplicationType = role == ApplicationRole.WORKER
                ? WebApplicationType.NONE
                : WebApplicationType.SERVLET;
        return new SpringApplicationBuilder(StoreAnalyticsApplication.class)
                .web(webApplicationType)
                .registerShutdownHook(false)
                .run(
                        "--app.runtime.role=" + role,
                        "--spring.main.allow-bean-definition-overriding=false",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--server.port=0",
                        "--management.server.port=0",
                        "--app.sync.worker-enabled=true",
                        "--app.sync.schedule-enabled=true",
                        "--app.reports.annual-scheduling-enabled=true",
                        "--app.reports.backfill.worker-enabled=true",
                        "--app.interpretation.snapshot-enabled=true",
                        "--app.interpretation.snapshot-planner.enabled=true",
                        "--app.interpretation.snapshot-worker.enabled=true",
                        "--app.interpretation.generation-enabled=true",
                        "--app.interpretation.generation-planner.enabled=true",
                        "--app.notification.telegram.enabled=true",
                        "--app.notification.telegram.fanout-enabled=true",
                        "--app.llm.yandex.folder-id=startup-test-folder",
                        "--app.llm.yandex.api-key=startup-test-api-key",
                        "--app.llm.yandex.model-uri=gpt://startup-test-folder/yandexgpt-5.1",
                        "--app.maintenance.retention.scheduling-enabled=true",
                        "--app.observability.state-initial-delay=24h",
                        "--app.observability.livesklad-initial-delay=24h",
                        "--app.security.cors-allowed-origins="
                                + "http://127.0.0.1:5173",
                        "--app.security.telemetry.pseudonym-key="
                                + "startup-test-security-telemetry-key-00000000",
                        "--app.security.telemetry.pseudonym-key-id=startup-test"
                );
    }

    private void migrateSchema() {
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
