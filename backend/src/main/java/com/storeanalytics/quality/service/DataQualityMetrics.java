package com.storeanalytics.quality.service;

import com.storeanalytics.quality.model.DataQualityStatus;
import com.storeanalytics.quality.repository.DataQualityIssueRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DataQualityMetrics implements MeterBinder {

    static final String ISSUES_METRIC = "storeanalytics.quality.issues";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            DataQualityMetrics.class
    );

    private final DataQualityIssueRepository issueRepository;
    private final AtomicReference<Double> openIssues = new AtomicReference<>(
            Double.NaN
    );

    public DataQualityMetrics(DataQualityIssueRepository issueRepository) {
        this.issueRepository = issueRepository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(ISSUES_METRIC, openIssues, AtomicReference::get)
                .description("Current data quality issues by status")
                .tag("status", "open")
                .register(registry);
    }

    @Scheduled(
            initialDelayString = "${app.observability.state-initial-delay:30s}",
            fixedDelayString = "${app.observability.state-refresh-delay:1m}"
    )
    public void refresh() {
        try {
            openIssues.set((double) issueRepository.countByStatus(
                    DataQualityStatus.OPEN
            ));
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to refresh data quality metrics", exception);
        }
    }
}
