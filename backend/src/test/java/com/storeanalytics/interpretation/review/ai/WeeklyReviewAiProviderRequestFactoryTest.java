package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiProviderRequestFactoryTest {

    private final WeeklyReviewAiInputCompactor compactor = mock(
            WeeklyReviewAiInputCompactor.class
    );
    private final WeeklyReviewAiProviderRequestFactory factory =
            new WeeklyReviewAiProviderRequestFactory(
                    compactor,
                    new WeeklyReviewAiContentCodec()
            );

    @Test
    void packagesExactV24InputPromptAndSchema() {
        PersistedWeeklyReviewSnapshot snapshot = snapshot();
        WeeklyReviewAiInput input = WeeklyReviewAiEvaluationCorpus
                .onlineCases().getFirst().input();
        when(compactor.compact(snapshot.response())).thenReturn(input);
        Instant now = Instant.parse("2026-08-27T10:00:00Z");

        PreparedWeeklyReviewAiRequest prepared = factory.prepare(
                new WeeklyReviewAiProviderRequestCommand(
                        UUID.fromString("00000000-0000-0000-0000-000000000101"),
                snapshot,
                "YANDEX",
                "gpt://folder/yandexgpt-5.1",
                new BigDecimal("0.1"),
                1400,
                now,
                Duration.ofMinutes(3),
                now.plus(Duration.ofMinutes(10)),
                List.of()
                )
        );

        assertThat(prepared.input()).isEqualTo(input);
        assertThat(prepared.inputHash()).matches("[a-f0-9]{64}");
        assertThat(prepared.requestHash()).matches("[a-f0-9]{64}");
        assertThat(prepared.request().systemPrompt())
                .contains("optional enrichment system prompt v24")
                .doesNotContain("Предыдущий ответ был отклонён");
        assertThat(prepared.request().responseSchemaJson())
                .contains("\"schemaVersion\":{\"const\":4}");
        assertThat(prepared.request().callDeadline())
                .isEqualTo(now.plus(Duration.ofMinutes(3)));
    }

    @Test
    void addsOnlyValidatedRetryCodesAndCapsDeadline() {
        PersistedWeeklyReviewSnapshot snapshot = snapshot();
        when(compactor.compact(snapshot.response())).thenReturn(
                WeeklyReviewAiEvaluationCorpus.onlineCases().getFirst().input()
        );
        Instant now = Instant.parse("2026-08-27T10:00:00Z");

        PreparedWeeklyReviewAiRequest prepared = factory.prepare(
                new WeeklyReviewAiProviderRequestCommand(
                        UUID.randomUUID(),
                snapshot,
                "YANDEX",
                "gpt://folder/yandexgpt-5.1",
                new BigDecimal("0.1"),
                1400,
                now,
                Duration.ofMinutes(3),
                now.plusSeconds(30),
                List.of("UNAPPROVED_NUMBER")
                )
        );

        assertThat(prepared.request().systemPrompt())
                .contains("UNAPPROVED_NUMBER");
        assertThat(prepared.request().callDeadline()).isEqualTo(now.plusSeconds(30));
        assertThatThrownBy(() -> factory.prepare(
                new WeeklyReviewAiProviderRequestCommand(
                        UUID.randomUUID(), snapshot, "YANDEX",
                "gpt://folder/yandexgpt-5.1", new BigDecimal("0.1"), 1400,
                now, Duration.ofMinutes(3), now.plusSeconds(30),
                List.of("bad\ncode")
                )
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private PersistedWeeklyReviewSnapshot snapshot() {
        PersistedWeeklyReviewSnapshot snapshot = mock(
                PersistedWeeklyReviewSnapshot.class
        );
        when(snapshot.response()).thenReturn(mock(WeeklyReviewResponse.class));
        return snapshot;
    }
}
