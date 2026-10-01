package com.storeanalytics.metrics.cases;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.common.exception.PreconditionRequiredException;
import com.storeanalytics.common.idempotency.IdempotencyRequest;
import com.storeanalytics.common.idempotency.IdempotencyService;
import com.storeanalytics.common.web.StrongEtag;
import com.storeanalytics.metrics.service.AttachRateEntry;
import com.storeanalytics.metrics.service.AttachRateService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CaseAttachService {
    private static final List<String> TARGETS = List.of(
            "CASE_APPLE_IPHONE", "CASE_SAMSUNG", "CASE_OTHER_DEVICE",
            "GLASS_IPHONE", "GLASS_SAMSUNG", "GLASS_OTHER", "FILM_PHONE", "FILM_NON_PHONE", "DEFER",
            "CHARGER_CABLE", "ACCESSORY_AIRPODS", "ACCESSORY_APPLE_WATCH", "NO_ATTACH");
    private final CaseAttachRepository repository;
    private final AttachRateService rates;
    private final IdempotencyService idempotency;
    private final AuditLogService audit;

    public CaseAttachService(CaseAttachRepository repository, AttachRateService rates,
                             IdempotencyService idempotency, AuditLogService audit) {
        this.repository = repository;
        this.rates = rates;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CaseAttachViews.Queue queue(UUID storeId, String state, int offset, int limit) {
        return repository.queue(storeId, state, offset, limit);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CaseAttachViews.Detail detail(UUID storeId, UUID sourceId) {
        return new CaseAttachViews.Detail(source(storeId, sourceId),
                repository.history(storeId, sourceId), repository.affectedDates(storeId, sourceId));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CaseAttachViews.Preview preview(UUID storeId, UUID sourceId,
                                           CaseAttachDecisionRequest request) {
        validate(request);
        CaseAttachViews.Case source = source(storeId, sourceId);
        validateTarget(source, request.targetCode());
        return new CaseAttachViews.Preview(
                source.decisionCurrent() ? source.decisionTarget() : null,
                request.targetCode(), repository.netQuantity(storeId, sourceId),
                repository.affectedDates(storeId, sourceId),
                List.of("CHARGER_CABLE", "ACCESSORY_AIRPODS", "ACCESSORY_APPLE_WATCH").contains(source.categoryCode())
                        ? List.of("Решение изменит только attach-rate этой продажи и связанных возвратов. "
                                + "Денежная категория и постоянная совместимость товара сохранятся.")
                        : source.proposedTarget().equals("CONFLICT")
                        ? List.of("В чеке есть iPhone и Samsung. Проверьте назначение самого аксессуара.")
                        : List.of("Телефон в чеке — подсказка, не доказательство совместимости."));
    }

    @Transactional
    public CaseAttachViews.Detail decide(UUID storeId, UUID sourceId,
                                         CaseAttachDecisionRequest request,
                                         String expected, String key, UUID actorId) {
        validate(request);
        return idempotency.execute(actorId, key,
                new IdempotencyRequest("CASE_ATTACH_DECISION", storeId + "/" + sourceId,
                        new Command(request, expected)), CaseAttachViews.Detail.class, () -> {
                    repository.lock(storeId);
                    CaseAttachViews.Case source = source(storeId, sourceId);
                    if (expected == null || expected.isBlank()) {
                        throw new PreconditionRequiredException("If-Match is required");
                    }
                    if (!etag(source).equals(expected)) {
                        throw new PreconditionFailedException(
                                "Case source or decision changed; reload and review");
                    }
                    validateTarget(source, request.targetCode());
                    UUID decisionId = repository.save(source, request, actorId, storeId);
                    audit.record(actorId, storeId, AuditAction.CASE_ATTACH_DECIDED,
                            new AuditTarget("CASE_ATTACH_DECISION", decisionId), request.reason().trim(),
                            Map.of("sourceItemId", sourceId.toString(),
                                    "previousTarget", source.decisionTarget() == null
                                            ? "UNRESOLVED" : source.decisionTarget()),
                            Map.of("target", request.targetCode(), "revision", source.revision() + 1));
                    return detail(storeId, sourceId);
                });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CaseAttachViews.EstimateResult estimates(UUID storeId, StoreKpiPeriod period) {
        var confirmed = rates.calculate(storeId, period).rates();
        Map<String, BigDecimal> inferred = repository.inferredUnits(storeId, period.start(), period.end());
        List<CaseAttachViews.Estimate> rows = List.of(
                estimate("CASE_APPLE_IPHONE", "IPHONE", confirmed, inferred),
                estimate("CASE_SAMSUNG", "SAMSUNG", confirmed, inferred));
        return new CaseAttachViews.EstimateResult(period.start(), period.end(), rows,
                repository.periodCount(storeId, period.start(), period.end(), "CONFLICT"),
                repository.periodCount(storeId, period.start(), period.end(), "NONE"),
                repository.unresolvedReturnCount(storeId, period.start(), period.end()));
    }

    public static String etag(CaseAttachViews.Case source) {
        return StrongEtag.of("case-attach", source.id(), source.revision(),
                source.fingerprint() + ":" + source.proposedTarget());
    }

    private CaseAttachViews.Case source(UUID storeId, UUID sourceId) {
        CaseAttachViews.Case source = repository.find(storeId, sourceId);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case sale not found");
        }
        return source;
    }

    private void validateTarget(CaseAttachViews.Case source, String target) {
        if (!source.allowedTargets().contains(target)) {
            throw new InvalidRequestException("Target does not match the reviewed accessory type");
        }
    }

    private void validate(CaseAttachDecisionRequest request) {
        if (request == null || !TARGETS.contains(request.targetCode())
                || request.reason() == null || request.reason().trim().isBlank()
                || request.reason().length() > 1000) {
            throw new InvalidRequestException("Target and a reason up to 1000 characters are required");
        }
    }

    private CaseAttachViews.Estimate estimate(String metric, String proposal,
            List<AttachRateEntry> confirmed, Map<String, BigDecimal> inferred) {
        AttachRateEntry official = confirmed.stream().filter(rate -> metric.equals(rate.metricCode()))
                .findFirst().orElseThrow();
        BigDecimal guess = inferred.getOrDefault(proposal, BigDecimal.ZERO).setScale(3);
        BigDecimal denominator = official.denominatorQuantity();
        BigDecimal indicative = denominator.signum() <= 0 ? null
                : official.numeratorQuantity().add(guess).max(BigDecimal.ZERO)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(denominator, 2, RoundingMode.HALF_UP);
        return new CaseAttachViews.Estimate(metric, official.numeratorQuantity(), guess,
                denominator, official.ratePerHundred(), indicative);
    }

    private record Command(CaseAttachDecisionRequest decision, String expected) { }
}
