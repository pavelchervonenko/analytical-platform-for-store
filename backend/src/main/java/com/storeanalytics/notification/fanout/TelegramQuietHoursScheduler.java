package com.storeanalytics.notification.fanout;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

@Component
public class TelegramQuietHoursScheduler {

    public TelegramDeliverySchedule schedule(
            Instant earliest,
            Instant expiresAt,
            TelegramNotificationRecipient recipient
    ) {
        Instant candidate = requireNonNull(earliest, "earliest");
        Instant expiry = requireNonNull(expiresAt, "expiresAt");
        TelegramNotificationRecipient destination = requireNonNull(
                recipient,
                "recipient"
        );
        if (!destination.quietHoursEnabled()) {
            return result(candidate, expiry, candidate);
        }

        ZonedDateTime local = candidate.atZone(destination.deliveryZone());
        LocalTime currentTime = local.toLocalTime();
        LocalTime start = destination.quietHoursStart();
        LocalTime end = destination.quietHoursEnd();
        LocalDate allowedDate = null;

        if (start.equals(end)) {
            allowedDate = local.toLocalDate().plusDays(1);
        } else if (start.isBefore(end)) {
            if (!currentTime.isBefore(start) && currentTime.isBefore(end)) {
                allowedDate = local.toLocalDate();
            }
        } else if (!currentTime.isBefore(start)) {
            allowedDate = local.toLocalDate().plusDays(1);
        } else if (currentTime.isBefore(end)) {
            allowedDate = local.toLocalDate();
        }

        if (allowedDate != null) {
            candidate = LocalDateTime.of(allowedDate, end)
                    .atZone(destination.deliveryZone())
                    .toInstant();
        }
        return result(candidate, expiry, earliest);
    }

    private TelegramDeliverySchedule result(
            Instant candidate,
            Instant expiry,
            Instant earliest
    ) {
        return new TelegramDeliverySchedule(
                candidate.isBefore(expiry) ? candidate : earliest,
                !candidate.isBefore(expiry)
        );
    }
}
