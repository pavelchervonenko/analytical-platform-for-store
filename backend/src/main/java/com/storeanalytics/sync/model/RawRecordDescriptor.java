package com.storeanalytics.sync.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.store.model.Store;
import java.time.Instant;

public record RawRecordDescriptor(
        IntegrationConnection connection,
        Store store,
        SourceSystem sourceSystem,
        String entityType,
        String externalId,
        Instant sourceUpdatedAt
) {

    public RawRecordDescriptor {
        sourceSystem = requireNonNull(sourceSystem, "sourceSystem");
        entityType = requireText(entityType, "entityType");
        externalId = requireText(externalId, "externalId");
        require((sourceSystem == SourceSystem.MANUAL && connection == null)
                        || (sourceSystem != SourceSystem.MANUAL && connection != null),
                "connection presence must match sourceSystem");
        require(connection == null || connection.getSourceSystem() == sourceSystem,
                "connection sourceSystem must match raw record sourceSystem");
        require(store == null || sourceSystem == SourceSystem.MANUAL
                        || store.isConnectedTo(connection),
                "store must belong to the raw record connection");
    }
}
