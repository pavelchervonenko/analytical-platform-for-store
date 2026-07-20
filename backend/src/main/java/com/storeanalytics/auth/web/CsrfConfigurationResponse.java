package com.storeanalytics.auth.web;

public record CsrfConfigurationResponse(
        String headerName,
        String cookieName
) {
}
