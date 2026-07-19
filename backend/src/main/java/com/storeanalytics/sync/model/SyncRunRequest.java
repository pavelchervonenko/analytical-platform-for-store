package com.storeanalytics.sync.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.store.model.Store;

public record SyncRunRequest(
        SourceSystem sourceSystem,
        IntegrationConnection connection,
        Store store,
        SyncTriggerType triggerType,
        SyncScope syncScope,
        SyncPeriod period,
        AppUser requestedBy
) {

    public SyncRunRequest {
        sourceSystem = requireNonNull(sourceSystem, "sourceSystem");
        triggerType = requireNonNull(triggerType, "triggerType");
        syncScope = requireNonNull(syncScope, "syncScope");
        require((sourceSystem == SourceSystem.MANUAL && connection == null)
                        || (sourceSystem != SourceSystem.MANUAL && connection != null),
                "connection presence must match sourceSystem");
        require(connection == null || connection.getSourceSystem() == sourceSystem,
                "connection sourceSystem must match sync sourceSystem");
    }
}
