package com.storeanalytics.integration.livesklad.exception;

public class LiveSkladException extends RuntimeException {

    public LiveSkladException(String message) {
        super(message);
    }

    public LiveSkladException(String message, Throwable cause) {
        super(message, cause);
    }
}
