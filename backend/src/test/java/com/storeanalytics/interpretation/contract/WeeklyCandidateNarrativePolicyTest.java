package com.storeanalytics.interpretation.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateKind;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateSignal;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklyCandidateNarrativePolicyTest {

    @Test
    void keepsCandidateNarrativesQualitativeAndDimensionScoped() {
        WeeklyCandidateNarrativePolicy.Narrative revenue =
                WeeklyCandidateNarrativePolicy.forCandidate(candidate(
                        CandidateKind.OPPORTUNITY,
                        "REVENUE_DYNAMICS"
                ));
        WeeklyCandidateNarrativePolicy.Narrative profit =
                WeeklyCandidateNarrativePolicy.forCandidate(candidate(
                        CandidateKind.RISK,
                        "PROFITABILITY"
                ));

        assertThat(revenue.title()).isEqualTo("Динамика выручки");
        assertThat(revenue.summary()).isEqualTo(
                "Динамика выручки: подтверждён положительный сигнал."
        );
        assertThat(revenue.summary()).doesNotContain("прибыл", "марж");
        assertThat(profit.title()).isEqualTo("Динамика валовой прибыли");
        assertThat(profit.summary()).isEqualTo(
                "Динамика валовой прибыли требует внимания."
        );
        assertThat(profit.summary()).doesNotContain("выруч");
        assertThat(revenue.summary()).doesNotContainPattern("[0-9]");
        assertThat(profit.summary()).doesNotContainPattern("[0-9]");
    }

    @Test
    void usesSafeFallbackForUnknownTheme() {
        WeeklyCandidateNarrativePolicy.Narrative narrative =
                WeeklyCandidateNarrativePolicy.forCandidate(candidate(
                        CandidateKind.OBSERVATION,
                        "FUTURE_THEME"
                ));

        assertThat(narrative.title()).isEqualTo(
                "Подтверждённый бизнес-сигнал"
        );
        assertThat(narrative.summary()).isEqualTo(
                "Подтверждённый бизнес-сигнал: подтверждено изменение."
        );
    }

    private CandidateSignal candidate(CandidateKind kind, String theme) {
        return new CandidateSignal(
                "C001",
                kind,
                theme,
                null,
                List.of("STORE.NET_REVENUE.CURRENT")
        );
    }
}
