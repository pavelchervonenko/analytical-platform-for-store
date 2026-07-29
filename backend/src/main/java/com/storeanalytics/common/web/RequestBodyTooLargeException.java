package com.storeanalytics.common.web;

import java.io.IOException;

public final class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException() {
        super("Request body exceeds the configured limit");
    }
}
