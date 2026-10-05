package com.storeanalytics.interpretation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.SellerWeeklyHistoricalReviewService;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewProperties;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewView;
import com.storeanalytics.interpretation.review.WeeklyReviewProperties;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class SellerWeeklyHistoricalReviewControllerTest {
    @Test
    void disabledFeatureOrParentDoesNotReadOrPrepareAWeek() {
        var service = mock(SellerWeeklyHistoricalReviewService.class);
        for (boolean parent : new boolean[]{false, true}) {
            var controller = new SellerWeeklyHistoricalReviewController(service,
                    new SellerWeeklyReviewProperties(false), new WeeklyReviewProperties(parent));
            assertThat(controller.period(UUID.randomUUID(), LocalDate.parse("2026-09-14")).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
        var controller = new SellerWeeklyHistoricalReviewController(service,
                new SellerWeeklyReviewProperties(true), new WeeklyReviewProperties(false));
        assertThat(controller.period(UUID.randomUUID(), LocalDate.parse("2026-09-14")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(service);
    }

    @Test
    void authorizedAdapterPreservesExactPeriodAndPrivateNoStoreResponse() {
        var service = mock(SellerWeeklyHistoricalReviewService.class);
        var controller = new SellerWeeklyHistoricalReviewController(service,
                new SellerWeeklyReviewProperties(true), new WeeklyReviewProperties(true));
        UUID store = UUID.randomUUID();
        LocalDate week = LocalDate.parse("2026-09-14");
        var view = new SellerWeeklyReviewView(SellerWeeklyReviewView.Freshness.PREPARING, null);
        when(service.period(store, week)).thenReturn(view);
        var response = controller.period(store, week);
        assertThat(response.getBody()).isSameAs(view);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
        verify(service).period(store, week);
    }
}
