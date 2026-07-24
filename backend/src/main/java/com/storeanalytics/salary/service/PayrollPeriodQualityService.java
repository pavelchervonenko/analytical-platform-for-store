package com.storeanalytics.salary.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.repository.PayrollRunRepository;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayrollPeriodQualityService {

    private final PayrollReadinessService readinessService;
    private final PayrollRunRepository runRepository;
    private final PayrollFreshnessService freshnessService;

    public PayrollPeriodQualityService(
            PayrollReadinessService readinessService,
            PayrollRunRepository runRepository,
            PayrollFreshnessService freshnessService
    ) {
        this.readinessService = readinessService;
        this.runRepository = runRepository;
        this.freshnessService = freshnessService;
    }

    @Transactional(readOnly = true)
    public PayrollPeriodQualitySnapshot inspect(UUID storeId, YearMonth month) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        YearMonth validatedMonth = requireNonNull(month, "month");
        PayrollReadinessView readiness = readinessService.inspect(
                validatedStoreId, validatedMonth
        );
        PayrollRun run = runRepository
                .findFirstByStoreIdAndPeriodMonthOrderByRevisionDesc(
                        validatedStoreId, validatedMonth.atDay(1)
                )
                .orElse(null);
        return new PayrollPeriodQualitySnapshot(
                readiness,
                run != null,
                run == null ? null : run.getStatus(),
                run == null ? null : freshnessService.evaluate(run)
        );
    }
}
