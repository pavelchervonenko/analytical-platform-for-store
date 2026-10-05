package com.storeanalytics.metrics.service;

import com.storeanalytics.metrics.repository.SellerAttachRateRepository;
import com.storeanalytics.metrics.repository.SellerReturnAttributionRepository;
import com.storeanalytics.metrics.service.SellerHistoricalFinancialComparison.FinancialPeriod;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Dormant period-specific preparation; no public endpoint, scheduler, publication or provider call. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SellerHistoricalFactsService {
    private final SellerHistoricalFinancialFactsService financial;
    private final SellerAttachRateRepository attach;
    private final SellerReturnAttributionRepository returnAttribution;

    public SellerHistoricalFactsService(SellerHistoricalFinancialFactsService financial,
            SellerAttachRateRepository attach, SellerReturnAttributionRepository returnAttribution) {
        this.financial = financial;
        this.attach = attach;
        this.returnAttribution = returnAttribution;
    }

    public SellerHistoricalComparisonFacts read(UUID storeId, StoreKpiPeriod current, StoreKpiPeriod previous,
            ZoneId timezone, Instant now) {
        SellerHistoricalFinancialComparison selected = financial.read(storeId, current, previous, timezone, now);
        return new SellerHistoricalComparisonFacts(new SellerPeriodComparisonFacts(
                period(selected.current()), period(selected.previous())), selected.currentActionEmployeeIds());
    }

    private SellerPeriodFacts period(FinancialPeriod selected) {
        SellerPeriodMetrics metrics = selected.metrics();
        return new SellerPeriodFacts(metrics, selected.documents(),
                attach.readHistorical(metrics.cohort(), metrics.period()),
                attach.historicalFormulaVersion(),
                returnAttribution.read(metrics.cohort().storeId(), metrics.period()));
    }
}
