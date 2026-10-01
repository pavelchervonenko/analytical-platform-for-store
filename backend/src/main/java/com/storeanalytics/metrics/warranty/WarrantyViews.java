package com.storeanalytics.metrics.warranty;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class WarrantyViews {
    private WarrantyViews() {
    }

    @Schema(name = "WarrantyCase")
    public record Case(
            UUID id, UUID documentId, String documentExternalId, String documentNumber,
            LocalDate businessDate, String documentKind, String name, BigDecimal quantity,
            String state, String conflictCode, String fingerprint, long revision,
            UUID originalWarrantyItemId, String financialEmployeeName
    ) {
    }

    @Schema(name = "WarrantyDevice")
    public record Device(
            UUID id, UUID documentId, String documentExternalId, String documentNumber,
            LocalDate businessDate, UUID employeeId, String employeeName, String name,
            String deviceType, BigDecimal quantity, String fingerprint,
            BigDecimal allocatedQuantity, BigDecimal returnedQuantity
    ) {
    }

    @Schema(name = "WarrantyAllocation")
    public record Allocation(
            UUID deviceItemId, UUID deviceDocumentId, String deviceType,
            LocalDate businessDate, UUID employeeId, BigDecimal quantity
    ) {
    }

    @Schema(name = "WarrantyHistory")
    public record History(
            UUID id, long revision, String action, UUID actorId, String actorName,
            String reason, Instant createdAt, List<Allocation> allocations
    ) {
    }

    @Schema(name = "WarrantyQueue")
    public record Queue(
            List<Case> items, long total, long documentCount, BigDecimal unallocatedQuantity,
            int offset, int limit, boolean enabled
    ) {
    }

    @Schema(name = "WarrantyDetail")
    public record Detail(
            Case warranty, List<Device> candidates, List<Allocation> allocations,
            List<History> history, List<String> warnings
    ) {
    }

    @Schema(name = "WarrantyPreview")
    public record Preview(
            UUID sourceItemId, String action, List<Allocation> allocations,
            List<LocalDate> affectedDates, List<String> warnings
    ) {
    }
}
