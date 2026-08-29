package com.storeanalytics.interpretation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewService;
import com.storeanalytics.interpretation.review.WeeklyReviewSnapshotView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewOperationsControllerTest {

    @Test
    void generatesExactImmutableSnapshotAndReturnsItsOperatorIdentity() {
        UUID storeId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        WeeklyReviewService service = mock(WeeklyReviewService.class);
        WeeklyReviewResponse response = mock(WeeklyReviewResponse.class);
        when(response.period()).thenReturn(new PeriodContext(
                "Europe/Moscow",
                new DateRange(
                        LocalDate.of(2026, 8, 17),
                        LocalDate.of(2026, 8, 23)
                ),
                new DateRange(
                        LocalDate.of(2026, 8, 10),
                        LocalDate.of(2026, 8, 16)
                ),
                "17–23 августа 2026",
                "10–16 августа 2026"
        ));
        when(response.reportState()).thenReturn(ReportState.READY);
        when(service.generate(storeId)).thenReturn(
                new PersistedWeeklyReviewSnapshot(
                        snapshotId,
                        storeId,
                        1,
                        null,
                        response,
                        "a".repeat(64),
                        Instant.parse("2026-08-27T12:00:00Z")
                )
        );

        WeeklyReviewSnapshotView result = new WeeklyReviewOperationsController(
                service
        ).generate(storeId);

        assertThat(result.snapshotId()).isEqualTo(snapshotId);
        assertThat(result.storeId()).isEqualTo(storeId);
        assertThat(result.reportState()).isEqualTo(ReportState.READY);
        assertThat(result.period().start()).isEqualTo(
                LocalDate.of(2026, 8, 17)
        );
    }
}
