package com.storeanalytics.interpretation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiGenerationApproval;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiJobStatus;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiJobView;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiOperatorService;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiPreflightView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiOperationsControllerTest {

    @Test
    void delegatesPreflightApprovedGenerationAndJobRead() {
        UUID snapshotId = UUID.randomUUID();
        WeeklyReviewAiOperatorService service = mock(
                WeeklyReviewAiOperatorService.class
        );
        WeeklyReviewAiGenerationApproval approval =
                new WeeklyReviewAiGenerationApproval(
                        "a".repeat(64),
                        "b".repeat(64),
                        "c".repeat(64),
                        1,
                        new BigDecimal("2.500000"),
                        "RUB"
                );
        WeeklyReviewAiJobView expected = new WeeklyReviewAiJobView(
                UUID.randomUUID(),
                snapshotId,
                WeeklyReviewAiJobStatus.PENDING,
                0,
                1,
                Instant.parse("2026-08-27T12:00:00Z"),
                Instant.parse("2026-08-27T14:00:00Z"),
                null,
                List.of()
        );
        WeeklyReviewAiPreflightView preflight = mock(
                WeeklyReviewAiPreflightView.class
        );
        when(service.generate(snapshotId, approval)).thenReturn(expected);
        when(service.preflight(snapshotId)).thenReturn(preflight);
        when(service.findJob(expected.jobId())).thenReturn(expected);
        WeeklyReviewAiOperationsController controller =
                new WeeklyReviewAiOperationsController(service);

        assertThat(controller.generate(snapshotId, approval)).isSameAs(expected);
        assertThat(controller.preflight(snapshotId)).isSameAs(preflight);
        assertThat(controller.findJob(expected.jobId())).isSameAs(expected);
    }
}
