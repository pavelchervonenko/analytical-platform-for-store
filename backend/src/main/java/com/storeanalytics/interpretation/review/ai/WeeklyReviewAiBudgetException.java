package com.storeanalytics.interpretation.review.ai;

final class WeeklyReviewAiBudgetException extends RuntimeException {

    private final String code;

    WeeklyReviewAiBudgetException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
