package com.storeanalytics.interpretation.snapshot;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Limitation;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.LimitationImpact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Scope;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import com.storeanalytics.metrics.service.AttachRateDataQuality;
import com.storeanalytics.metrics.service.StoreKpiDataQuality;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Versioned deterministic boundaries; interpretation itself remains the LLM's responsibility. */
public class WeeklySnapshotPolicyV1 {

    public static final int MAX_EMPLOYEES = 10;
    public static final Versions VERSIONS = new Versions(
            1,
            "weekly-metrics-v1",
            "weekly-snapshot-v3",
            "weekly-quality-v1"
    );

    private static final BigDecimal MINIMUM_WORKED_HOURS = new BigDecimal("12.00");
    private static final BigDecimal LIMITED_COVERAGE = new BigDecimal("50.00");
    private static final BigDecimal SUFFICIENT_COVERAGE = new BigDecimal("75.00");
    private static final BigDecimal CLEAR_LEADER_ADVANTAGE_PERCENT = new BigDecimal("5.00");

    public Sufficiency workload(long shiftCount, BigDecimal workedHours) {
        if (shiftCount <= 0 || workedHours == null || workedHours.signum() <= 0) {
            return Sufficiency.INSUFFICIENT;
        }
        if (shiftCount == 1 || workedHours.compareTo(MINIMUM_WORKED_HOURS) < 0) {
            return Sufficiency.LIMITED;
        }
        return Sufficiency.SUFFICIENT;
    }

    public Sufficiency salesStructure(BigDecimal completedSales) {
        if (completedSales == null || completedSales.compareTo(BigDecimal.valueOf(3)) < 0) {
            return Sufficiency.INSUFFICIENT;
        }
        if (completedSales.compareTo(BigDecimal.valueOf(6)) < 0) {
            return Sufficiency.LIMITED;
        }
        return Sufficiency.SUFFICIENT;
    }

    public Sufficiency attach(BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.valueOf(3)) < 0) {
            return Sufficiency.INSUFFICIENT;
        }
        if (denominator.compareTo(BigDecimal.valueOf(5)) < 0) {
            return Sufficiency.LIMITED;
        }
        return Sufficiency.SUFFICIENT;
    }

    public Sufficiency overall(
            BigDecimal coveragePercent,
            Sufficiency workload,
            boolean criticalQualityIssue
    ) {
        if (criticalQualityIssue || coveragePercent == null
                || coveragePercent.compareTo(LIMITED_COVERAGE) < 0
                || workload == Sufficiency.INSUFFICIENT) {
            return Sufficiency.INSUFFICIENT;
        }
        if (coveragePercent.compareTo(SUFFICIENT_COVERAGE) < 0
                || workload == Sufficiency.LIMITED) {
            return Sufficiency.LIMITED;
        }
        return Sufficiency.SUFFICIENT;
    }

    public Sufficiency dynamics(Sufficiency current, Sufficiency previous) {
        if (current == Sufficiency.INSUFFICIENT || previous == Sufficiency.INSUFFICIENT) {
            return Sufficiency.INSUFFICIENT;
        }
        if (current == Sufficiency.LIMITED || previous == Sufficiency.LIMITED) {
            return Sufficiency.LIMITED;
        }
        return Sufficiency.SUFFICIENT;
    }

    public Sufficiency mostRestrictive(Sufficiency first, Sufficiency second) {
        java.util.Objects.requireNonNull(first, "first");
        java.util.Objects.requireNonNull(second, "second");
        if (first == Sufficiency.INSUFFICIENT || second == Sufficiency.INSUFFICIENT) {
            return Sufficiency.INSUFFICIENT;
        }
        if (first == Sufficiency.LIMITED || second == Sufficiency.LIMITED) {
            return Sufficiency.LIMITED;
        }
        return Sufficiency.SUFFICIENT;
    }

    public boolean teamBenchmarkAllowed(long sufficientEmployeeCount) {
        return sufficientEmployeeCount >= 3;
    }

    public boolean clearLeader(BigDecimal bestValue, BigDecimal nextValue) {
        if (bestValue == null || nextValue == null || nextValue.signum() <= 0) {
            return false;
        }
        BigDecimal advantage = bestValue.subtract(nextValue)
                .multiply(BigDecimal.valueOf(100))
                .divide(nextValue, 4, java.math.RoundingMode.HALF_UP);
        return advantage.compareTo(CLEAR_LEADER_ADVANTAGE_PERCENT) >= 0;
    }

    public SnapshotQualityDecision quality(
            StoreDataStatusView source,
            StoreKpiDataQuality storeQuality,
            AttachRateDataQuality attachQuality,
            LocalDate periodEnd
    ) {
        List<EvidenceIndexEntry> unavailable = new ArrayList<>();
        List<Limitation> limitations = new ArrayList<>();
        if (source.status() == StoreDataFreshnessStatus.NOT_SYNCED
                || source.status() == StoreDataFreshnessStatus.ERROR
                || source.dataThroughDate() == null
                || source.dataThroughDate().isBefore(periodEnd)) {
            String evidenceRef = "STORE.DATA_COVERAGE.STATUS";
            unavailable.add(unavailable(evidenceRef));
            limitations.add(limitation(
                    "SOURCE_DATA_INCOMPLETE",
                    LimitationImpact.UNAVAILABLE,
                    List.of("RESULT", "DYNAMICS", "EMPLOYEES"),
                    evidenceRef
            ));
            return new SnapshotQualityDecision(
                    QualityStatus.BLOCKED,
                    unavailable,
                    limitations
            );
        }

        if (!storeQuality.completeCostData()) {
            String evidenceRef = "STORE.GROSS_PROFIT.CURRENT";
            unavailable.add(unavailable(evidenceRef));
            limitations.add(limitation(
                    "COST_DATA_INCOMPLETE",
                    LimitationImpact.UNAVAILABLE,
                    List.of("PROFIT", "MARGIN"),
                    evidenceRef
            ));
        }
        if (storeQuality.unmappedItemCount() > 0 || source.openQualityIssueCount() > 0) {
            String evidenceRef = "STORE.CLASSIFICATION_QUALITY.STATUS";
            unavailable.add(unavailable(evidenceRef));
            limitations.add(limitation(
                    "CLASSIFICATION_QUALITY_LIMITED",
                    LimitationImpact.REDUCED_CONFIDENCE,
                    List.of("CATEGORIES", "ADDITIONAL_SALES"),
                    evidenceRef
            ));
        }
        if (attachQuality.unmatchedNumeratorItemCount() > 0
                || attachQuality.ambiguousWarrantyItemCount() > 0
                || attachQuality.unknownDeviceConditionItemCount() > 0) {
            String evidenceRef = "STORE.ATTACH_DATA_QUALITY.STATUS";
            unavailable.add(unavailable(evidenceRef));
            limitations.add(limitation(
                    "ATTACH_QUALITY_LIMITED",
                    LimitationImpact.REDUCED_CONFIDENCE,
                    List.of("ATTACH"),
                    evidenceRef
            ));
        }
        QualityStatus status = limitations.isEmpty()
                ? QualityStatus.READY
                : QualityStatus.PARTIAL;
        return new SnapshotQualityDecision(status, unavailable, limitations);
    }

    private static EvidenceIndexEntry unavailable(String evidenceRef) {
        return new EvidenceIndexEntry(evidenceRef, Scope.STORE, null, false);
    }

    private static Limitation limitation(
            String code,
            LimitationImpact impact,
            List<String> sections,
            String evidenceRef
    ) {
        return new Limitation(
                code,
                Scope.STORE,
                null,
                null,
                impact,
                sections,
                List.of(evidenceRef)
        );
    }
}
