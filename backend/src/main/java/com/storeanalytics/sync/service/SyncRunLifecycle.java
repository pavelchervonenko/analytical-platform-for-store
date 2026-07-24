package com.storeanalytics.sync.service;

import com.storeanalytics.sync.repository.SyncRunErrorRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
record SyncRunLifecycle(
        SyncRunRepository runs,
        SyncRunErrorRepository errors,
        Clock clock,
        SyncMetrics metrics
) {
}
