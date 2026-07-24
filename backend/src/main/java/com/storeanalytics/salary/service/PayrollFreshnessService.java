package com.storeanalytics.salary.service;

import com.storeanalytics.salary.exception.PayrollSourceDataChangedException;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollSourceFingerprint;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PayrollFreshnessService {

    private final PayrollCalculationSource source;
    private final PayrollSourceFingerprintService fingerprintService;
    private final Clock clock;

    public PayrollFreshnessService(
            PayrollCalculationSource source,
            PayrollSourceFingerprintService fingerprintService,
            Clock clock
    ) {
        this.source = source;
        this.fingerprintService = fingerprintService;
        this.clock = clock;
    }

    public PayrollFreshnessView evaluate(PayrollRun run) {
        PayrollSourceFingerprint stored = run.getSourceFingerprint();
        if (stored == null) {
            return new PayrollFreshnessView(
                    PayrollFreshnessStatus.UNKNOWN,
                    true,
                    List.of(PayrollStaleReason.SOURCE_FINGERPRINT_MISSING),
                    clock.instant()
            );
        }
        PayrollSourceFingerprint current = fingerprintService.capture(source.load(
                run.getStore().getId(), YearMonth.from(run.getPeriodMonth())
        ));
        List<PayrollStaleReason> reasons = differences(stored, current);
        PayrollFreshnessStatus status = reasons.isEmpty()
                ? PayrollFreshnessStatus.CURRENT : PayrollFreshnessStatus.STALE;
        return new PayrollFreshnessView(
                status, !reasons.isEmpty(), List.copyOf(reasons), clock.instant()
        );
    }

    void requireCurrent(PayrollRun run) {
        PayrollFreshnessView freshness = evaluate(run);
        if (freshness.requiresRecalculation()) {
            throw new PayrollSourceDataChangedException(freshness.reasons());
        }
    }

    private List<PayrollStaleReason> differences(
            PayrollSourceFingerprint stored,
            PayrollSourceFingerprint current
    ) {
        List<PayrollStaleReason> reasons = new ArrayList<>();
        if (!stored.salesHash().equals(current.salesHash())) {
            reasons.add(PayrollStaleReason.SALES_DATA_CHANGED);
        }
        if (!stored.shiftsHash().equals(current.shiftsHash())) {
            reasons.add(PayrollStaleReason.WORK_SHIFTS_CHANGED);
        }
        if (!stored.planHash().equals(current.planHash())) {
            reasons.add(PayrollStaleReason.STORE_PLAN_CHANGED);
        }
        if (!stored.classificationHash().equals(current.classificationHash())) {
            reasons.add(PayrollStaleReason.PRODUCT_CLASSIFICATION_CHANGED);
        }
        if (!stored.schemeHash().equals(current.schemeHash())) {
            reasons.add(PayrollStaleReason.PAYROLL_SCHEME_CHANGED);
        }
        return reasons;
    }
}
