package com.storeanalytics.notification.config;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.notification.daily.DailyStorePulseConfigurationReadiness;
import com.storeanalytics.notification.daily.DailyStorePulseProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.notification.daily-pulse",
        name = "enabled",
        havingValue = "true"
)
public class DailyStorePulseSchedulingConfiguration {

    public static final String DAILY_STORE_PULSE_SCHEDULER =
            "dailyStorePulseScheduler";

    @Bean
    DailyStorePulseConfigurationReadiness dailyStorePulseConfigurationReadiness(
            DailyStorePulseProperties daily,
            TelegramNotificationProperties telegram
    ) {
        return new DailyStorePulseConfigurationReadiness(daily, telegram);
    }

    @Bean(name = DAILY_STORE_PULSE_SCHEDULER)
    public ThreadPoolTaskScheduler dailyStorePulseScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("daily-store-pulse-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
