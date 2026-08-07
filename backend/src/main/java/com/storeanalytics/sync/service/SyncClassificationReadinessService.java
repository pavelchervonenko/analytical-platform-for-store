package com.storeanalytics.sync.service;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.product.repository.ProductCategoryAssignmentRepository;
import com.storeanalytics.product.repository.ProductRepository;
import com.storeanalytics.sales.repository.SalesDocumentItemRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncClassificationReadinessService {

    private final ProductCategoryAssignmentRepository assignmentRepository;
    private final ProductRepository productRepository;
    private final SalesDocumentItemRepository salesItemRepository;

    public SyncClassificationReadinessService(
            ProductCategoryAssignmentRepository assignmentRepository,
            ProductRepository productRepository,
            SalesDocumentItemRepository salesItemRepository
    ) {
        this.assignmentRepository = assignmentRepository;
        this.productRepository = productRepository;
        this.salesItemRepository = salesItemRepository;
    }

    @Transactional(readOnly = true)
    public SyncClassificationReadinessView inspect(
            IntegrationConnection connection,
            Instant periodStart
    ) {
        UUID connectionId = connection.getId();
        long effectiveAssignments = assignmentRepository
                .countEffectiveByConnectionId(connectionId, periodStart);
        return new SyncClassificationReadinessView(
                connection.getConnectionKey(),
                periodStart,
                effectiveAssignments > 0,
                effectiveAssignments,
                assignmentRepository.countByConnectionId(connectionId),
                productRepository.countByConnectionId(connectionId),
                salesItemRepository.countActiveUnmappedByConnectionId(connectionId)
        );
    }
}
