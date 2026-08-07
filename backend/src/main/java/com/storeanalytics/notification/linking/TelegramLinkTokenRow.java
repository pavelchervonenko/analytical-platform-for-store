package com.storeanalytics.notification.linking;

import java.time.Instant;
import java.util.UUID;

record TelegramLinkTokenRow(UUID id, UUID userId, Instant expiresAt) {
}
