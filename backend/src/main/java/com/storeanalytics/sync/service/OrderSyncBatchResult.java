package com.storeanalytics.sync.service;

record OrderSyncBatchResult(
        int documentsCreated,
        int documentsUpdated,
        int documentsSkipped,
        int documentsDeleted,
        int productsCreated,
        int productsUpdated,
        int itemsCreated,
        int itemsUpdated,
        int itemsDeleted,
        int qualityIssuesOpened,
        int qualityIssuesResolved
) {
}
