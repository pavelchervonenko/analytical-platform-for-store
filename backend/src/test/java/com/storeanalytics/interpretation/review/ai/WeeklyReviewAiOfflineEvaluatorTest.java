package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WeeklyReviewAiOfflineEvaluatorTest {

    @Test
    void passesEveryVersionedSemanticCase() {
        var results = new WeeklyReviewAiOfflineEvaluator().evaluate();

        assertThat(results).hasSize(17);
        assertThat(results)
                .as("corpus %s", WeeklyReviewAiEvaluationCorpus.VERSION)
                .allMatch(WeeklyReviewAiOfflineEvaluator.EvaluationResult::passed);
    }
}
