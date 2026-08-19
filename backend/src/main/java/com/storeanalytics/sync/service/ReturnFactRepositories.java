package com.storeanalytics.sync.service;

import com.storeanalytics.quality.repository.DataQualityIssueRepository;
import com.storeanalytics.sales.repository.SalesDocumentItemRepository;
import com.storeanalytics.sales.repository.SalesDocumentRepository;
import com.storeanalytics.sales.repository.SalesPaymentRepository;
import com.storeanalytics.sync.repository.RawRecordVersionRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
record ReturnFactRepositories(
        SalesDocumentRepository documents,
        SalesDocumentItemRepository items,
        SalesPaymentRepository payments,
        DataQualityIssueRepository qualityIssues,
        SyncRunRepository syncRuns,
        RawRecordVersionRepository rawRecords,
        JdbcTemplate jdbcTemplate
) {
}
