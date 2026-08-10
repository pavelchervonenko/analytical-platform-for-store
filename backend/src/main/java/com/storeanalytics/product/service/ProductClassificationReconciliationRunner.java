package com.storeanalytics.product.service;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        name = "app.product-classification.reconciliation.enabled",
        havingValue = "true"
)
public class ProductClassificationReconciliationRunner
        implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ProductClassificationReconciliationRunner.class
    );

    private final ProductClassificationReconciliationService service;
    private final ProductClassificationReconciliationProperties properties;

    public ProductClassificationReconciliationRunner(
            ProductClassificationReconciliationService service,
            ProductClassificationReconciliationProperties properties
    ) {
        this.service = service;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        ProductClassificationReconciliationResult result =
                service.reconcileApprovedScope(
                        properties.externalProductIds(),
                        properties.expectedItemCount()
                );
        LOGGER.info(
                "Approved product classification reconciliation completed; "
                        + "inspectedItems={}, reclassifiedItems={}, "
                        + "unresolvedItems={}, resolvedQualityIssues={}",
                result.inspectedItems(),
                result.reclassifiedItems(),
                result.unresolvedItems(),
                result.resolvedQualityIssues()
        );
    }
}
