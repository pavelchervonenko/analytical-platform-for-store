package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WeeklyReviewAiRendererV25Test {

    private final WeeklyReviewAiRendererV25 renderer =
            new WeeklyReviewAiRendererV25();

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            POSITIVE | Неделя завершилась лучше периода сравнения.
            NEGATIVE | Неделя завершилась слабее периода сравнения.
            MIXED | Ключевые результаты недели изменились разнонаправленно.
            NEUTRAL | Ключевые результаты недели существенно не изменились.
            """)
    void rendersEveryOutcomeEffect(String effect, String expected) {
        WeeklyReviewAiContent content = renderer.render(
                WeeklyReviewAiTestFixtures.minimalInput(effect),
                outcomeSelection()
        );

        assertThat(content.summary().text()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            RETURN_CHANGE | UP | NEGATIVE | false | FACTOR_CONTROL | Сумма возвратов стала выше
            RETURN_CHANGE | UP | NEGATIVE | true | FACTOR_CONTROL | Давление возвратов на результат усилилось
            RETURN_CHANGE | DOWN | POSITIVE | true | FACTOR_SIGNAL | Давление возвратов на результат снизилось
            RETURN_CHANGE | FLAT | NEGATIVE | false | FACTOR_CONTROL | Сумма возвратов существенно не изменилась
            RETURN_CHANGE | UNKNOWN | NEGATIVE | false | FACTOR_CONTROL | недоступно
            STRUCTURE_CHANGE | DOWN | NEGATIVE | false | FACTOR_RISK | Проверяемый фактор относительно периода сравнения
            ATTACH_CHANGE | UP | POSITIVE | false | FACTOR_STRENGTH | Проверяемый фактор относительно периода сравнения
            OTHER | UP | POSITIVE | false | FACTOR_SIGNAL | Проверяемый фактор относительно периода сравнения
            """)
    void rendersFactorKindsWithoutInventingFacts(
            String kind,
            String direction,
            String effect,
            boolean causalAllowed,
            String selector,
            String expectedFragment
    ) {
        WeeklyReviewAiInput input = factorInput(
                kind, direction, effect, causalAllowed, selector
        );
        WeeklyReviewAiContent content = renderer.render(
                input,
                factorSelection(selector)
        );

        assertThat(content.factorExplanations().getFirst().text())
                .contains(expectedFragment)
                .doesNotContain("SUMMARY_", "FACTOR_");
        if ("RETURN_CHANGE".equals(kind) && !causalAllowed) {
            assertThat(content.factorExplanations().getFirst().text())
                    .doesNotContain("на результат");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            FLAT | сумма возвратов существенно не изменилась
            UNKNOWN | изменение суммы возвратов недоступно
            """)
    void rendersFlatAndUnknownReturnDirectionInSummary(
            String direction,
            String expectedFragment
    ) {
        WeeklyReviewAiInput input = factorInput(
                "RETURN_CHANGE",
                direction,
                "NEGATIVE",
                false,
                "FACTOR_CONTROL"
        );
        WeeklyReviewAiSelection selection = new WeeklyReviewAiSelection(
                1,
                new WeeklyReviewAiSelection.SummarySelection(
                        "SUMMARY_RISK", "factor:test", null
                ),
                List.of(new WeeklyReviewAiSelection.FactorSelection(
                        "factor:test", "FACTOR_CONTROL"
                ))
        );

        WeeklyReviewAiContent content = renderer.render(input, selection);

        assertThat(content.summary().text()).contains(expectedFragment);
    }

    @Test
    void rejectsUnknownSelectorWithoutDefaultRendering() {
        WeeklyReviewAiInput input = factorInput(
                "ATTACH_CHANGE",
                "UP",
                "POSITIVE",
                false,
                "FACTOR_SIGNAL"
        );
        WeeklyReviewAiSelection selection = factorSelection(
                "FACTOR_UNKNOWN"
        );

        assertThatThrownBy(() -> renderer.render(input, selection))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown factor selector");
    }

    private WeeklyReviewAiSelection outcomeSelection() {
        return new WeeklyReviewAiSelection(
                1,
                new WeeklyReviewAiSelection.SummarySelection(
                        "SUMMARY_OUTCOME", null, null
                ),
                List.of()
        );
    }

    private WeeklyReviewAiSelection factorSelection(String selector) {
        return new WeeklyReviewAiSelection(
                1,
                new WeeklyReviewAiSelection.SummarySelection(
                        "SUMMARY_OUTCOME", null, null
                ),
                List.of(new WeeklyReviewAiSelection.FactorSelection(
                        "factor:test", selector
                ))
        );
    }

    private WeeklyReviewAiInput factorInput(
            String kind,
            String direction,
            String effect,
            boolean causalAllowed,
            String selector
    ) {
        return new WeeklyReviewAiInput(
                4,
                WeeklyReviewAiContract.PROMPT_VERSION,
                4,
                "READY",
                new WeeklyReviewAiInput.SummarySource(
                        "NEUTRAL",
                        List.of("SUMMARY_OUTCOME"),
                        List.of("factor:test"),
                        List.of("STORE.NET_REVENUE")
                ),
                List.of(new WeeklyReviewAiInput.FactorSource(
                        "factor:test",
                        kind,
                        "Проверяемый фактор",
                        direction,
                        effect,
                        causalAllowed,
                        List.of(selector),
                        List.of("STORE.TEST_METRIC")
                )),
                List.of(),
                List.of(
                        WeeklyReviewAiTestFixtures.evidence(
                                "STORE.NET_REVENUE",
                                "Чистая выручка",
                                "RUB",
                                "100",
                                "100"
                        ),
                        WeeklyReviewAiTestFixtures.evidence(
                                "STORE.TEST_METRIC",
                                "Проверяемый показатель",
                                "COUNT",
                                "2",
                                "1"
                        )
                )
        );
    }
}
