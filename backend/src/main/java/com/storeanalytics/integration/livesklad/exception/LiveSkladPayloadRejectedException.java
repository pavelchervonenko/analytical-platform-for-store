package com.storeanalytics.integration.livesklad.exception;

public class LiveSkladPayloadRejectedException extends LiveSkladException {

    private final Reason reason;

    public LiveSkladPayloadRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public LiveSkladPayloadRejectedException(
            Reason reason,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        RESPONSE_TOO_LARGE,
        JSON_COMPLEXITY,
        UNSUPPORTED_CONTENT_TYPE,
        UNSUPPORTED_CONTENT_ENCODING,
        COLLECTION_RECORD_COUNT,
        DOCUMENT_POSITION_COUNT,
        RAW_PAYLOAD_TOO_LARGE
    }
}
