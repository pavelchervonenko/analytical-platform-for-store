package com.storeanalytics.sync.exception;

public class ActiveSyncJobException extends RuntimeException {

    public ActiveSyncJobException() {
        super("An active synchronization job already exists");
    }
}
