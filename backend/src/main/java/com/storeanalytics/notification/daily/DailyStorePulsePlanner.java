package com.storeanalytics.notification.daily;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyStorePulsePlanner {

    private final DailyStorePulseEventStore eventStore;
    private final DailyStorePulseProjection projection;
    private final DailyStorePulseProperties properties;
    private final DailyStorePulseMetrics metrics;
    private final Clock clock;

    public DailyStorePulsePlanner(
            DailyStorePulseEventStore eventStore,
            DailyStorePulseProjection projection,
            DailyStorePulseProperties properties,
            DailyStorePulseMetrics metrics,
            Clock clock
    ) {
        this.eventStore = eventStore;
        this.projection = projection;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public DailyStorePulsePlanningResult plan() {
        Instant now = clock.instant();
        int eligible = 0;
        int created = 0;
        for (DailyStorePulseEventStore.StoreTarget target : eventStore.activeStores()) {
            ZoneId zone = ZoneId.of(target.timezone());
            ZonedDateTime localNow = now.atZone(zone);
            LocalDate businessDate = localNow.toLocalDate().minusDays(1);
            if (!withinWindow(localNow.toLocalTime())
                    || !covers(target.salesThroughExclusive(), businessDate, zone)
                    || !covers(target.returnsThroughExclusive(), businessDate, zone)) {
                continue;
            }
            eligible++;
            Instant expiry = localNow.toLocalDate()
                    .atTime(properties.expiresAt()).atZone(zone).toInstant();
            DailyStorePulsePayload payload = projection.build(
                    target.storeId(), businessDate
            );
            if (eventStore.insert(
                    target,
                    payload,
                    properties.policyVersion(),
                    now,
                    expiry
            )) {
                created++;
                metrics.created(now);
            } else {
                metrics.existing();
            }
        }
        return new DailyStorePulsePlanningResult(eligible, created, eligible - created);
    }

    private boolean withinWindow(LocalTime value) {
        return !value.isBefore(properties.sendAfter())
                && value.isBefore(properties.expiresAt());
    }

    private boolean covers(Instant exclusiveEnd, LocalDate date, ZoneId zone) {
        return exclusiveEnd != null
                && !exclusiveEnd.minusNanos(1).atZone(zone).toLocalDate().isBefore(date);
    }
}
