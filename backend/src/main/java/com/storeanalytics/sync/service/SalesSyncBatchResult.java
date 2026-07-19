package com.storeanalytics.sync.service;

record SalesSyncBatchResult(
        int documentsCreated,
        int documentsUpdated,
        int documentsSkipped,
        int documentsDeleted,
        int productsCreated,
        int productsUpdated,
        int itemsCreated,
        int itemsUpdated,
        int itemsDeleted,
        int paymentsCreated,
        int paymentsUpdated,
        int paymentsDeleted,
        int qualityIssuesOpened,
        int qualityIssuesResolved
) {
}
