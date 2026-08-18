package com.storeanalytics.integration.livesklad.webhook;

record LiveSkladWebhookAcceptance(boolean accepted, String responseBody) {

    static LiveSkladWebhookAcceptance accepted(String responseBody) {
        return new LiveSkladWebhookAcceptance(true, responseBody);
    }

    static LiveSkladWebhookAcceptance rejected() {
        return new LiveSkladWebhookAcceptance(false, "INVALID");
    }
}
