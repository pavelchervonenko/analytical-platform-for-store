package com.storeanalytics.notification.delivery;

import java.util.UUID;

public record NotificationDeliveryClaim(UUID deliveryId, String leaseOwner) {
}
