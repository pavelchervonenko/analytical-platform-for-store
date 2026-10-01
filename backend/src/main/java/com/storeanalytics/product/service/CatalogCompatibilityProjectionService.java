package com.storeanalytics.product.service;

import com.storeanalytics.product.model.CatalogCompatibilityEvidence;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Context;
import com.storeanalytics.product.repository.CatalogCompatibilityRepository;
import com.storeanalytics.product.service.CatalogAccessoryAttachPolicy.Decision;
import com.storeanalytics.product.service.CatalogAccessoryAttachPolicy.Outcome;
import com.storeanalytics.product.service.CatalogAccessoryAttachPolicy.Reason;
import com.storeanalytics.product.service.CatalogCompatibilityRecords.Action;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Dated read bridge for sale snapshots; never re-project a linked return from today's catalog. */
@Service
public class CatalogCompatibilityProjectionService {
    private final CatalogCompatibilityRepository repository;
    private final CatalogAccessoryAttachPolicy policy = new CatalogAccessoryAttachPolicy();

    public CatalogCompatibilityProjectionService(CatalogCompatibilityRepository repository) {
        this.repository = repository;
    }

    public record Projection(Decision decision, UUID confirmationId) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Decision forNewSaleFact(UUID productId, String monetaryCategory, Context factContext) {
        return project(productId, monetaryCategory, factContext).decision();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Projection project(UUID productId, String monetaryCategory, Context factContext) {
        var confirmation = repository.effective(productId, factContext.occurredAt()).orElse(null);
        if (confirmation == null) {
            return new Projection(policy.evaluate(monetaryCategory,
                    CatalogCompatibilityEvidence.unknown(), factContext), null);
        }
        if (confirmation.action() == Action.REVOKE) {
            return new Projection(new Decision(monetaryCategory, Outcome.REVIEW_PRODUCT,
                    null, Reason.CONFIRMATION_REVOKED), confirmation.id());
        }
        return new Projection(policy.evaluate(monetaryCategory, confirmation.evidence(), factContext),
                confirmation.id());
    }
}
