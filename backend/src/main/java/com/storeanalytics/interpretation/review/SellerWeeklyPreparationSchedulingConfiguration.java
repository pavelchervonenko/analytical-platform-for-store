package com.storeanalytics.interpretation.review;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(prefix = "app.interpretation.seller-weekly-preparation", name = "enabled", havingValue = "true")
class SellerWeeklyPreparationSchedulingConfiguration {
    static final String SCHEDULER = "sellerWeeklyPreparationTaskScheduler";

    @Bean(name = SCHEDULER)
    ThreadPoolTaskScheduler scheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("seller-weekly-free-preparation-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
