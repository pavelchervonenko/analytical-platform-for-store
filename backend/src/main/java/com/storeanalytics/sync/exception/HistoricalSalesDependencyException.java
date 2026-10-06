package com.storeanalytics.sync.exception;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Internal durable dependency identities; exception messages never expose source data. */
public final class HistoricalSalesDependencyException extends RuntimeException {
    private final Instant repairStart;
    private final Instant repairEnd;
    private final boolean backfillRepairAllowed;
    private final List<LinkedReturn> linkedReturns;

    public HistoricalSalesDependencyException(Instant repairStart, Instant repairEnd, boolean backfillRepairAllowed,
                                             List<LinkedReturn> linkedReturns) {
        super("Historical SALE correction requires a covering SALE/RETURN backfill");
        this.repairStart = Objects.requireNonNull(repairStart);
        this.repairEnd = Objects.requireNonNull(repairEnd);
        this.backfillRepairAllowed = backfillRepairAllowed;
        this.linkedReturns = List.copyOf(linkedReturns);
        if (this.linkedReturns.isEmpty()
                || this.linkedReturns.stream().map(LinkedReturn::returnId).distinct().count()
                    != this.linkedReturns.size()) {
            throw new IllegalArgumentException("Historical dependency identities are invalid");
        }
        if (!repairEnd.isAfter(repairStart)) {
            throw new IllegalArgumentException("Historical dependency repair interval is invalid");
        }
    }

    public Instant repairStart() {
        return repairStart;
    }

    public Instant repairEnd() {
        return repairEnd;
    }

    public boolean backfillRepairAllowed() {
        return backfillRepairAllowed;
    }

    public List<LinkedReturn> linkedReturns() {
        return linkedReturns;
    }

    public record LinkedReturn(UUID returnId, UUID parentId) {
        public LinkedReturn {
            Objects.requireNonNull(returnId);
            Objects.requireNonNull(parentId);
        }
    }
}
