package com.storeanalytics.notification.linking;

import java.util.UUID;

record TelegramMembershipTransition(
        UUID subscriptionId,
        String previousStatus,
        String currentStatus,
        boolean stale
) {

    boolean stateChanged() {
        return !previousStatus.equals(currentStatus);
    }
}
