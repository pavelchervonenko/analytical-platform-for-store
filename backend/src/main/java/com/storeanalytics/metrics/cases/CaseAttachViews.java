package com.storeanalytics.metrics.cases;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CaseAttachViews {
    private CaseAttachViews() { }

    public record Case(
            UUID id, UUID productId, String productCode, String name,
            UUID documentId, String documentNumber, LocalDate businessDate,
            BigDecimal quantity, String proposedTarget, boolean hasIphone,
            boolean hasSamsung, String decisionTarget, boolean decisionCurrent,
            long revision, String fingerprint, String categoryCode, List<String> allowedTargets
    ) { }

    public record History(
            UUID id, long revision, String targetCode, String reason,
            UUID actorId, Instant createdAt
    ) { }

    public record Queue(
            List<Case> items, long total, long openCount, long conflictCount,
            BigDecimal openQuantity, int offset, int limit
    ) { }

    public record Detail(Case item, List<History> history, List<LocalDate> affectedDates) { }

    public record Preview(
            String previousTarget, String nextTarget, BigDecimal netQuantity,
            List<LocalDate> affectedDates, List<String> warnings
    ) { }

    public record Estimate(
            String metricCode, BigDecimal confirmedQuantity,
            BigDecimal inferredQuantity, BigDecimal denominatorQuantity,
            BigDecimal confirmedRatePerHundred,
            BigDecimal indicativeRatePerHundred
    ) { }

    public record EstimateResult(
            LocalDate periodStart, LocalDate periodEnd, List<Estimate> rates,
            long conflictCount, long unresolvedCount, long unresolvedReturnCount
    ) { }
}
