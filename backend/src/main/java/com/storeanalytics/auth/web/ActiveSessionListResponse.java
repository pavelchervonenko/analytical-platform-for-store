package com.storeanalytics.auth.web;

import java.util.List;

public record ActiveSessionListResponse(List<ActiveSessionResponse> sessions) {

    public ActiveSessionListResponse {
        sessions = List.copyOf(sessions);
    }
}
