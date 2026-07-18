package com.storeanalytics.sales.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.employee.model.Employee;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncRun;

public record SalesDocumentIdentity(
        IntegrationConnection connection,
        SourceSystem sourceSystem,
        String externalId,
        Store store,
        Employee employee,
        SalesDocument originalDocument,
        SyncRun lastSyncRun
) {

    public SalesDocumentIdentity {
        sourceSystem = requireNonNull(sourceSystem, "sourceSystem");
        externalId = requireText(externalId, "externalId");
        store = requireNonNull(store, "store");
        lastSyncRun = requireNonNull(lastSyncRun, "lastSyncRun");
        require((sourceSystem == SourceSystem.MANUAL && connection == null)
                        || (sourceSystem == SourceSystem.LIVESKLAD && connection != null),
                "connection presence must match sales sourceSystem");
        require(connection == null || connection.getSourceSystem() == sourceSystem,
                "connection sourceSystem must match sales sourceSystem");
        require(sourceSystem == SourceSystem.MANUAL || store.isConnectedTo(connection),
                "sales document and store must belong to the same connection");
        require(employee == null || employee.isCompatibleWith(store),
                "sales employee must be compatible with the document store");
        require(lastSyncRun.getSourceSystem() == sourceSystem,
                "lastSyncRun sourceSystem must match sales sourceSystem");
        require(sourceSystem == SourceSystem.MANUAL
                        || lastSyncRun.getConnection() != null
                        && lastSyncRun.getConnection().getId().equals(connection.getId()),
                "lastSyncRun connection must match sales connection");
    }
}
