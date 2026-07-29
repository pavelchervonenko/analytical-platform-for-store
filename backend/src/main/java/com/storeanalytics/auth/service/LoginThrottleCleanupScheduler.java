package com.storeanalytics.auth.service;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
class LoginThrottleCleanupScheduler {

    private final LoginThrottleService loginThrottleService;

    LoginThrottleCleanupScheduler(LoginThrottleService loginThrottleService) {
        this.loginThrottleService = loginThrottleService;
    }

    @Scheduled(
            cron = "${app.security.login-throttle.cleanup-cron:0 0 4 * * *}",
            scheduler = BackgroundSchedulingConfiguration.CLEANUP_SCHEDULER
    )
    void removeExpiredEntries() {
        loginThrottleService.removeExpiredEntries();
    }
}
