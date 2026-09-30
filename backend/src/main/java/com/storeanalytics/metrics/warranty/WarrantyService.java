package com.storeanalytics.metrics.warranty;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.common.exception.PreconditionRequiredException;
import com.storeanalytics.common.idempotency.IdempotencyRequest;
import com.storeanalytics.common.idempotency.IdempotencyService;
import com.storeanalytics.common.web.StrongEtag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class WarrantyService {
    private final WarrantyRepository repository;
    private final IdempotencyService idempotency;
    private final AuditLogService audit;
    private final AttachAttributionPolicy policy;

    public WarrantyService(WarrantyRepository repository, IdempotencyService idempotency,
                           AuditLogService audit, AttachAttributionPolicy policy) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.audit = audit;
        this.policy = policy;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WarrantyViews.Queue queue(UUID storeId, String state, int offset, int limit) {
        WarrantyViews.Queue queue = repository.queue(storeId, state, offset, limit);
        return new WarrantyViews.Queue(queue.items(), queue.total(), queue.documentCount(),
                queue.unallocatedQuantity(), queue.offset(), queue.limit(), policy.enabled());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WarrantyViews.Detail detail(UUID storeId, UUID sourceId) {
        WarrantyViews.Case source = repository.find(storeId, sourceId);
        List<WarrantyViews.Allocation> allocations = repository.allocations(sourceId);
        Set<UUID> documents = new LinkedHashSet<>();
        documents.add(source.documentId());
        allocations.forEach(a -> documents.add(a.deviceDocumentId()));
        if (source.originalWarrantyItemId() != null) {
            repository.allocations(source.originalWarrantyItemId()).forEach(a -> documents.add(a.deviceDocumentId()));
        }
        List<WarrantyViews.Device> candidates = documents.stream()
                .flatMap(id -> repository.candidates(storeId, id).stream()).distinct().toList();
        return new WarrantyViews.Detail(source, candidates, allocations,
                repository.history(sourceId), warnings(source, allocations, storeId));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<WarrantyViews.Device> search(UUID storeId, String query) {
        return repository.search(storeId, query);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<WarrantyViews.Case> searchOriginals(UUID storeId, String query) {
        return repository.searchOriginals(storeId, query);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WarrantyViews.Preview preview(UUID storeId, UUID sourceId, WarrantyDecisionRequest request) {
        WarrantyViews.Case source = repository.find(storeId, sourceId);
        return validate(storeId, source, request);
    }

    @Transactional
    public WarrantyViews.Detail decide(UUID storeId, UUID sourceId, WarrantyDecisionRequest request,
                                       String expected, String key, UUID actorId) {
        require(policy.enabled(), "Warranty attribution is not enabled; decisions are read-only");
        return idempotency.execute(actorId, key,
                new IdempotencyRequest("WARRANTY_DECISION", storeId + "/" + sourceId,
                        new Command(request, expected)), WarrantyViews.Detail.class, () -> {
                    repository.lock(storeId);
                    WarrantyViews.Case source = repository.find(storeId, sourceId);
                    requireVersion(source, expected);
                    validate(storeId, source, request);
                    UUID decisionId = repository.save(source, request, actorId);
                    for (WarrantyDecisionRequest.Allocation allocation : request.allocations()) {
                        WarrantyViews.Device target = repository.device(storeId, allocation.deviceItemId());
                        if (!target.fingerprint().equals(allocation.fingerprint())) {
                            throw new PreconditionFailedException("Selected device changed while saving");
                        }
                        repository.saveAllocation(decisionId, allocation, target);
                    }
                    audit.record(actorId, storeId, AuditAction.WARRANTY_ATTACH_DECIDED,
                            new AuditTarget("WARRANTY_ATTACH_DECISION", decisionId), request.reason().trim(),
                            Map.of("sourceItemId", sourceId, "revision", source.revision(), "state", source.state()),
                            Map.of("action", request.action().name(), "revision", source.revision() + 1,
                                    "allocationCount", request.allocations().size()));
                    WarrantyViews.Detail result = detail(storeId, sourceId);
                    if (request.action() == WarrantyDecisionRequest.Action.ALLOCATE
                            && !result.warranty().state().equals("RESOLVED_MANUAL")) {
                        throw new PreconditionFailedException("Warranty changed while saving; reload and review");
                    }
                    return result;
                });
    }

    public static String etag(WarrantyViews.Case source) {
        return StrongEtag.of("warranty", source.id(), source.revision(), source.fingerprint());
    }

    private void requireVersion(WarrantyViews.Case source, String expected) {
        if (expected == null || expected.isBlank()) {
            throw new PreconditionRequiredException("If-Match is required");
        }
        if (!etag(source).equals(expected)) {
            throw new PreconditionFailedException("Warranty source or decision changed; reload and review");
        }
    }

    private WarrantyViews.Preview validate(UUID storeId, WarrantyViews.Case source, WarrantyDecisionRequest request) {
        require(request != null && request.action() != null && request.allocations() != null, "Decision is required");
        require(request.reason() != null && !request.reason().isBlank() && request.reason().length() <= 1000,
                "Reason requires 1 to 1000 characters");
        require(request.allocations().size() <= 100, "Too many allocations");
        if (request.originalWarrantyItemId() != null) {
            WarrantyViews.Case original = repository.find(storeId, request.originalWarrantyItemId());
            require(source.documentKind().equals("RETURN") && original.documentKind().equals("SALE")
                    && repository.sameConnection(source.id(), original.id()), "Invalid original warranty link");
            require(repository.matchesProviderOriginal(source.id(), original.id()),
                    "Original warranty does not match the known source link");
        }
        if (request.action() != WarrantyDecisionRequest.Action.ALLOCATE) {
            require(request.allocations().isEmpty(), "Only an allocation decision can contain devices");
            return new WarrantyViews.Preview(source.id(), request.action().name(), List.of(),
                    repository.allocations(source.id()).stream().map(WarrantyViews.Allocation::businessDate)
                            .distinct().toList(), List.of());
        }
        Set<UUID> selected = new HashSet<>();
        List<WarrantyViews.Allocation> allocations = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (WarrantyDecisionRequest.Allocation allocation : request.allocations()) {
            require(allocation != null && allocation.deviceItemId() != null
                    && allocation.quantity() != null, "Device and quantity are required");
            require(selected.add(allocation.deviceItemId()), "Duplicate device allocation");
            require(allocation.quantity().signum() > 0 && allocation.quantity().scale() <= 3,
                    "Quantity must be positive with at most three decimal places");
            WarrantyViews.Device device = repository.device(storeId, allocation.deviceItemId());
            require(repository.sameConnection(source.id(), device.id()), "Source connection must match");
            requireDecision(!device.businessDate().isAfter(source.businessDate()),
                    BusinessErrorCode.WARRANTY_DEVICE_DATE_INVALID);
            if (!device.fingerprint().equals(allocation.fingerprint())) {
                throw new PreconditionFailedException("Selected device changed; review allocation");
            }
            allocations.add(new WarrantyViews.Allocation(device.id(), device.documentId(), device.deviceType(),
                    device.businessDate(), device.employeeId(), allocation.quantity()));
            sum = sum.add(allocation.quantity());
        }
        require(sum.compareTo(source.quantity()) == 0, "Allocate the complete source quantity");
        validateReturn(storeId, source, request, allocations);
        Set<LocalDate> dates = new LinkedHashSet<>();
        repository.allocations(source.id()).forEach(a -> dates.add(a.businessDate()));
        allocations.forEach(a -> dates.add(a.businessDate()));
        return new WarrantyViews.Preview(source.id(), request.action().name(), allocations,
                List.copyOf(dates), warnings(source, allocations, storeId));
    }

    private void validateReturn(UUID storeId, WarrantyViews.Case source, WarrantyDecisionRequest request,
                                List<WarrantyViews.Allocation> allocations) {
        if (!source.documentKind().equals("RETURN")) {
            require(request.originalWarrantyItemId() == null, "A sale cannot reference an original warranty");
            return;
        }
        UUID originalId = request.originalWarrantyItemId() == null
                ? source.originalWarrantyItemId() : request.originalWarrantyItemId();
        require(originalId != null, "Find the original warranty before allocating its return");
        require(repository.matchesProviderOriginal(source.id(), originalId),
                "Original warranty does not match the known source link");
        WarrantyViews.Case original = repository.find(storeId, originalId);
        require(original.documentKind().equals("SALE") && repository.sameConnection(source.id(), originalId),
                "Original must be a warranty sale in the same connection");
        require(original.businessDate().compareTo(source.businessDate()) <= 0, "Return predates original warranty");
        requireDecision(repository.otherReturned(originalId, source.id()).add(source.quantity())
                .compareTo(original.quantity()) <= 0, BusinessErrorCode.WARRANTY_RETURN_EXCEEDS_ALLOCATION);
        List<WarrantyViews.Allocation> originals = repository.allocations(originalId);
        requireDecision(!originals.isEmpty(), BusinessErrorCode.WARRANTY_ORIGINAL_UNRESOLVED);
        for (WarrantyViews.Allocation allocation : allocations) {
            BigDecimal available = originals.stream()
                    .filter(a -> Objects.equals(a.deviceItemId(), allocation.deviceItemId())
                            || a.deviceItemId() == null && a.deviceDocumentId().equals(allocation.deviceDocumentId()))
                    .map(WarrantyViews.Allocation::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal returned = repository.returnedToDevice(originalId, allocation.deviceItemId(), source.id());
            requireDecision(available.subtract(returned).compareTo(allocation.quantity()) >= 0,
                    BusinessErrorCode.WARRANTY_RETURN_EXCEEDS_ALLOCATION);
        }
    }

    private List<String> warnings(WarrantyViews.Case source, List<WarrantyViews.Allocation> allocations, UUID storeId) {
        Set<String> warnings = new LinkedHashSet<>(repository.productWarnings(source.id()));
        for (WarrantyViews.Allocation allocation : allocations) {
            long days = ChronoUnit.DAYS.between(allocation.businessDate(), source.businessDate());
            if (days > 0 && source.documentKind().equals("SALE")) {
                warnings.add("Гарантия продана через " + days + " дн. после устройства");
            }
            BigDecimal previous = repository.allocations(source.id()).stream()
                    .filter(a -> a.deviceDocumentId().equals(allocation.deviceDocumentId())
                            && a.deviceType().equals(allocation.deviceType()))
                    .map(WarrantyViews.Allocation::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal proposed = allocations.stream()
                    .filter(a -> a.deviceDocumentId().equals(allocation.deviceDocumentId())
                            && a.deviceType().equals(allocation.deviceType()))
                    .map(WarrantyViews.Allocation::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal delta = proposed.subtract(previous);
            warnings.addAll(repository.coverageWarnings(allocation.deviceDocumentId(), allocation.deviceType(),
                    source.documentKind().equals("RETURN") ? delta.negate() : delta));
            if (allocation.deviceItemId() != null) {
                WarrantyViews.Device device = repository.device(storeId, allocation.deviceItemId());
                if (allocation.quantity().compareTo(device.quantity()) > 0
                        || device.allocatedQuantity().compareTo(device.quantity()) > 0) {
                    warnings.add("Гарантий больше, чем устройств");
                }
            }
        }
        return List.copyOf(warnings);
    }

    private void requireDecision(boolean condition, BusinessErrorCode code) {
        if (!condition) {
            throw new WarrantyDecisionException(code);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidRequestException(message);
        }
    }

    private record Command(WarrantyDecisionRequest request, String expected) {
    }
}
