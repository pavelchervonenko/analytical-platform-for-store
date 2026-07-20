package com.storeanalytics.sync.exception;

import java.util.UUID;

public class SyncJobNotFoundException extends RuntimeException {

    public SyncJobNotFoundException(UUID jobId) {
        super("Synchronization job not found: " + jobId);
    }
}
