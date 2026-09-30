package com.storeanalytics.product.service;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.common.exception.PreconditionRequiredException;
import com.storeanalytics.common.idempotency.IdempotencyRequest;
import com.storeanalytics.common.idempotency.IdempotencyService;
import com.storeanalytics.common.web.StrongEtag;
import com.storeanalytics.product.repository.CatalogCompatibilityRepository;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Profile;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Request;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Internal command only: no public endpoint, import extension or automatic history reclassification. */
@Service
public class CatalogCompatibilityService {
    private final CatalogCompatibilityRepository repository;
    private final CatalogCompatibilityAuthorizer authorizer;
    private final IdempotencyService idempotency;
    private final AuditLogService audit;
    private final Clock clock;
    private final boolean enabled;

    public CatalogCompatibilityService(CatalogCompatibilityRepository repository,
            CatalogCompatibilityAuthorizer authorizer, IdempotencyService idempotency,
            AuditLogService audit, Clock clock,
            @Value("${app.catalog-compatibility.confirmations-enabled:false}") boolean enabled) {
        this.repository = repository;
        this.authorizer = authorizer;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Profile preview(UUID productId) {
        authorizer.requireAdministrator();
        return profile(productId, false);
    }

    @Transactional
    public Profile decide(UUID productId, Request request, String expected, String key) {
        authorizer.requireAdministrator();
        Objects.requireNonNull(request, "request");
        if (request.origin() == CatalogCompatibilityRecords.Origin.LEGACY_ADOPTION) {
            throw new IllegalArgumentException("Legacy adoption requires verified artifact bytes");
        }
        return save(productId, request, expected, key, null);
    }

    @Transactional
    public Profile adoptLegacy(UUID productId, byte[] artifact, String expectedSha256,
                               String evidenceKey, String expected, String key) {
        authorizer.requireAdministrator();
        if (!enabled) {
            throw new IllegalStateException("Catalog compatibility commands are not enabled");
        }
        var verified = CatalogLegacyCompatibilityEvidence.verify(artifact, expectedSha256, evidenceKey);
        return save(productId, verified.request(), expected, key, verified);
    }

    private Profile save(UUID productId, Request request, String expected, String key,
                         CatalogLegacyCompatibilityEvidence.Verified verified) {
        UUID actor = authorizer.requireAdministrator();
        if (!enabled) {
            throw new IllegalStateException("Catalog compatibility commands are not enabled");
        }
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(request, "request");
        if (expected == null || expected.isBlank()) {
            throw new PreconditionRequiredException("If-Match is required");
        }
        return idempotency.execute(actor, key,
                new IdempotencyRequest("CATALOG_COMPATIBILITY_DECISION", productId.toString(),
                        new Command(request, expected)), Profile.class, () -> {
                    Profile previous = profile(productId, true);
                    if (!previous.etag().equals(expected)) {
                        throw new PreconditionFailedException(
                                "Catalog observation or revision changed; reload preview");
                    }
                    if (verified != null) {
                        verified.requireMatching(previous.observation());
                    }
                    request.validateFor(previous.observation(), actor, clock.instant());
                    long revision = previous.latest() == null ? 1 : Math.addExact(previous.latest().revision(), 1);
                    var decision = repository.append(previous.observation(), revision, request, actor);
                    audit.record(actor, null, AuditAction.CATALOG_COMPATIBILITY_DECIDED,
                            new AuditTarget("CATALOG_COMPATIBILITY_DECISION", decision.id()), request.reason(),
                            Map.of("productId", productId, "revision", revision - 1),
                            Map.of("productId", productId, "revision", revision, "action", request.action(),
                                    "coverage", request.coverage(), "targets", request.targets(),
                                    "origin", request.origin(), "prospectiveOnly", true));
                    return profile(productId, false);
                });
    }

    private Profile profile(UUID productId, boolean lock) {
        var observation = repository.observe(productId, lock);
        var latest = repository.latest(productId).orElse(null);
        String etag = StrongEtag.of("catalog-compatibility", productId,
                latest == null ? 0 : latest.revision(), observation.fingerprint());
        return new Profile(observation, latest, etag);
    }

    private record Command(Request request, String expected) { }
}
