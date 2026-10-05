package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiGenerationProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SellerWeeklyPreparationConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultsNeverActivatePreparationOrScheduler() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(SellerWeeklyPreparationProperties.class).enabled()).isFalse();
            assertThat(application).doesNotHaveBean(SellerWeeklyPreparationScheduler.class);
            assertThat(application).doesNotHaveBean(SellerWeeklyPreparationSchedulingConfiguration.SCHEDULER);
        });
    }

    @Test
    void enabledPreparationRequiresBothParentFeatures() {
        context.withPropertyValues("app.interpretation.seller-weekly-preparation.enabled=true")
                .run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("app.interpretation.seller-weekly-preparation.enabled=true",
                        "app.interpretation.weekly-review.enabled=true")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void workerGetsDedicatedSerialSchedulerButNotCompetingCurrentRosterPlanner() {
        enabled().withPropertyValues("app.runtime.role=WORKER",
                "app.interpretation.weekly-review-snapshot-planner.enabled=true").run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application).hasSingleBean(SellerWeeklyPreparationScheduler.class);
                    assertThat(application).doesNotHaveBean(SellerWeeklyReviewSnapshotPlanner.class);
                    var scheduler = application.getBean(SellerWeeklyPreparationSchedulingConfiguration.SCHEDULER,
                            ThreadPoolTaskScheduler.class);
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
                    assertThat(scheduler.getThreadNamePrefix()).isEqualTo("seller-weekly-free-preparation-");
                });
    }

    @Test
    void apiAndMigrationNeverOwnBackgroundPreparation() {
        for (String role : new String[]{"API", "MIGRATION"}) {
            enabled().withPropertyValues("app.runtime.role=" + role).run(application -> {
                assertThat(application).hasNotFailed();
                assertThat(application).doesNotHaveBean(SellerWeeklyPreparationScheduler.class);
                assertThat(application).doesNotHaveBean(SellerWeeklyPreparationSchedulingConfiguration.SCHEDULER);
            });
        }
    }

    @Test
    void historicalPreparationNeverSilentlyUsesLegacyAutomaticPaidPlanner() {
        enabled().withPropertyValues("app.interpretation.weekly-review-ai.planner-enabled=true")
                .run(application -> assertThat(application).hasFailed());
        enabled().withPropertyValues("app.runtime.role=WORKER",
                "app.interpretation.weekly-review-ai.enabled=true",
                "app.interpretation.weekly-review-ai.planner-enabled=true").run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application).doesNotHaveBean(SellerWeeklyReviewSnapshotPlanner.class);
                    assertThat(application).hasSingleBean(SellerWeeklyPreparationScheduler.class);
                });
    }

    @Test
    void disabledPreparationPreservesLegacyPlannerOwnership() {
        context.withPropertyValues("app.runtime.role=WORKER", "app.interpretation.weekly-review.enabled=true",
                "app.interpretation.seller-weekly-review.enabled=true",
                "app.interpretation.weekly-review-snapshot-planner.enabled=true").run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application).hasSingleBean(SellerWeeklyReviewSnapshotPlanner.class);
                    assertThat(application).doesNotHaveBean(SellerWeeklyPreparationScheduler.class);
                });
    }

    @Test
    void unsafeBatchAndDurationSettingsRejectStartup() {
        for (String invalid : new String[]{"scan-delay=0s", "scan-delay=9s", "scan-delay=2h",
                "store-batch-size=0", "store-batch-size=101", "discovery-weeks=0", "discovery-weeks=53",
                "refresh-batch-size=0", "refresh-batch-size=101", "preparation-batch-size=0",
                "preparation-batch-size=11", "time-budget=0s", "time-budget=6m"}) {
            context.withPropertyValues("app.interpretation.seller-weekly-preparation." + invalid)
                    .run(application -> assertThat(application).hasFailed());
        }
        assertThatThrownBy(() -> new SellerWeeklyPreparationProperties(false, null, 1, 1, 1, 1,
                Duration.ofSeconds(1))).isInstanceOf(NullPointerException.class);
    }

    private ApplicationContextRunner enabled() {
        return context.withPropertyValues("app.interpretation.seller-weekly-preparation.enabled=true",
                "app.interpretation.weekly-review.enabled=true",
                "app.interpretation.seller-weekly-review.enabled=true");
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = SellerWeeklyPreparationScheduler.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = SellerWeeklyPreparationScheduler.class))
    @EnableConfigurationProperties({SellerWeeklyPreparationProperties.class, SellerWeeklyReviewProperties.class,
        WeeklyReviewProperties.class, WeeklyReviewSnapshotPlannerProperties.class,
        WeeklyReviewAiGenerationProperties.class})
    @Import({SellerWeeklyPreparationConfiguration.class,
        SellerWeeklyPreparationSchedulingConfiguration.class, SellerWeeklyReviewSnapshotPlanner.class})
    static class TestConfiguration {
        @Bean
        SellerWeeklyPreparationBatchService preparationBatch() {
            return mock(SellerWeeklyPreparationBatchService.class);
        }

        @Bean
        SellerWeeklyV3BatchPlanningService legacyBatch() {
            return mock(SellerWeeklyV3BatchPlanningService.class);
        }
    }
}
