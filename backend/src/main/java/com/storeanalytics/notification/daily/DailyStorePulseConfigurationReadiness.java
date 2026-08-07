package com.storeanalytics.notification.daily;

import com.storeanalytics.notification.config.TelegramNotificationProperties;

public class DailyStorePulseConfigurationReadiness {

    public DailyStorePulseConfigurationReadiness(
            DailyStorePulseProperties daily,
            TelegramNotificationProperties telegram
    ) {
        if (daily.enabled() && (!telegram.enabled() || !telegram.fanoutEnabled())) {
            throw new IllegalStateException(
                    "Daily store pulse requires Telegram notifications and fanout"
            );
        }
    }
}
