package com.storeanalytics.report.service;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.reports",
        name = "annual-scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true
)
class AnnualReportScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnnualReportScheduler.class);

    private final StoreRepository storeRepository;
    private final AnnualReportFinalizationService finalizationService;
    private final Clock clock;

    AnnualReportScheduler(
            StoreRepository storeRepository,
            AnnualReportFinalizationService finalizationService,
            Clock clock
    ) {
        this.storeRepository = storeRepository;
        this.finalizationService = finalizationService;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${app.reports.annual-cron:0 30 4 * * *}",
            zone = "${app.reports.zone:Europe/Kaliningrad}",
            scheduler = BackgroundSchedulingConfiguration.ANNUAL_REPORT_SCHEDULER
    )
    void finalizeEligibleYears() {
        for (Store store : storeRepository.findAllByActiveTrue()) {
            try {
                finalizeStore(store);
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "Annual report maintenance failed for storeId={}",
                        store.getId(),
                        exception
                );
            }
        }
    }

    private void finalizeStore(Store store) {
        int firstYear = store.getReportingStartedOn().getYear();
        int currentYear = LocalDate.now(
                clock.withZone(ZoneId.of(store.getTimezone()))
        ).getYear();
        for (int year = firstYear; year < currentYear; year++) {
            finalizationService.finalizeYear(store.getId(), Year.of(year));
        }
    }
}
