package com.storeanalytics.interpretation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.SellerWeeklyReviewProperties;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewService;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewView;
import com.storeanalytics.interpretation.review.WeeklyReviewProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class SellerWeeklyReviewControllerTest {
    @Test
    void disabledCutoverNeverCallsReadOrWriter() {
        for (boolean parent : new boolean[]{false, true}) {
            var service = mock(SellerWeeklyReviewService.class);
            var controller = new SellerWeeklyReviewController(service,
                    new SellerWeeklyReviewProperties(false), new WeeklyReviewProperties(parent));
            assertThat(controller.current(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(controller.generate(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verifyNoInteractions(service);
        }
    }

    @Test
    void disabledParentNeverCallsReadOrWriter() {
        var service = mock(SellerWeeklyReviewService.class);
        var controller = new SellerWeeklyReviewController(service,
                new SellerWeeklyReviewProperties(true), new WeeklyReviewProperties(false));
        assertThat(controller.current(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.generate(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(service);
    }

    @Test
    void preparingIsSuccessfulSellerResponseNotAnInvitationToUseStoreFallback() {
        var service = mock(SellerWeeklyReviewService.class);
        UUID store = UUID.randomUUID();
        var view = new SellerWeeklyReviewView(SellerWeeklyReviewView.Freshness.PREPARING, null);
        when(service.current(store)).thenReturn(view);
        when(service.generate(store)).thenReturn(view);
        var controller = new SellerWeeklyReviewController(service,
                new SellerWeeklyReviewProperties(true), new WeeklyReviewProperties(true));
        for (var result : java.util.List.of(controller.current(store), controller.generate(store))) {
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isSameAs(view);
            assertThat(result.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
        }
    }
}
