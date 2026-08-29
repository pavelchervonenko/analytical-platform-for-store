package com.storeanalytics.interpretation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiJobStatus;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiJobView;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiOperatorService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiOperationsControllerTest {

    @Test
    void delegatesExactSnapshotAndReturnsAcceptedJobView() {
        UUID snapshotId = UUID.randomUUID();
        WeeklyReviewAiOperatorService service = mock(
                WeeklyReviewAiOperatorService.class
        );
        WeeklyReviewAiJobView expected = new WeeklyReviewAiJobView(
                UUID.randomUUID(),
                snapshotId,
                WeeklyReviewAiJobStatus.PENDING,
                0,
                2,
                Instant.parse("2026-08-27T12:00:00Z"),
                Instant.parse("2026-08-27T14:00:00Z"),
                null,
                List.of()
        );
        when(service.generate(snapshotId)).thenReturn(expected);
        when(service.findJob(expected.jobId())).thenReturn(expected);

        WeeklyReviewAiJobView actual = new WeeklyReviewAiOperationsController(
                service
        ).generate(snapshotId);

        assertThat(actual).isSameAs(expected);
        assertThat(new WeeklyReviewAiOperationsController(service)
                .findJob(expected.jobId())).isSameAs(expected);
    }
}
