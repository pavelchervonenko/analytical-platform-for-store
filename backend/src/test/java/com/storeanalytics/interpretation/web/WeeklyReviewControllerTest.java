package com.storeanalytics.interpretation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewProperties;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class WeeklyReviewControllerTest {

    @Test
    void returnsCurrentReviewWithPrivateNoStoreCaching() {
        UUID storeId = UUID.randomUUID();
        WeeklyReviewService service = mock(WeeklyReviewService.class);
        WeeklyReviewResponse response = mock(WeeklyReviewResponse.class);
        when(service.current(storeId)).thenReturn(Optional.of(response));

        ResponseEntity<WeeklyReviewResponse> result =
                controller(service, true).current(storeId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, no-store");
        assertThat(result.getBody()).isSameAs(response);
    }

    @Test
    void returnsNotFoundWithTheSameCachePolicyBeforeFirstSnapshotExists() {
        UUID storeId = UUID.randomUUID();
        WeeklyReviewService service = mock(WeeklyReviewService.class);
        when(service.current(storeId)).thenReturn(Optional.empty());

        ResponseEntity<WeeklyReviewResponse> result =
                controller(service, true).current(storeId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, no-store");
    }

    @Test
    void returnsNotFoundWithoutReadingSnapshotsWhenTheRolloutIsDisabled() {
        UUID storeId = UUID.randomUUID();
        WeeklyReviewService service = mock(WeeklyReviewService.class);

        ResponseEntity<WeeklyReviewResponse> result =
                controller(service, false).current(storeId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, no-store");
        verify(service, never()).current(storeId);
    }

    private WeeklyReviewController controller(
            WeeklyReviewService service,
            boolean enabled
    ) {
        return new WeeklyReviewController(
                service, new WeeklyReviewProperties(enabled)
        );
    }
}
