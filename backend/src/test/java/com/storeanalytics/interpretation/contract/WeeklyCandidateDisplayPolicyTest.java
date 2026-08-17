package com.storeanalytics.interpretation.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateKind;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateSignal;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklyCandidateDisplayPolicyTest {

    @Test
    void rendersConcreteDirectionalBusinessMeaning() {
        WeeklyCandidateDisplayPolicy.Narrative revenue =
                WeeklyCandidateDisplayPolicy.forCandidate(candidate(
                        CandidateKind.OPPORTUNITY,
                        "REVENUE_DYNAMICS"
                ));
        WeeklyCandidateDisplayPolicy.Narrative attach =
                WeeklyCandidateDisplayPolicy.forCandidate(candidate(
                        CandidateKind.RISK,
                        "ATTACH_RATE"
                ));
        WeeklyCandidateDisplayPolicy.Narrative employee =
                WeeklyCandidateDisplayPolicy.forCandidate(candidate(
                        CandidateKind.OPPORTUNITY,
                        "EMPLOYEE_PERFORMANCE"
                ));

        assertThat(revenue.summary()).isEqualTo(
                "Выручка существенно выросла относительно прошлого периода."
        );
        assertThat(attach.summary()).isEqualTo(
                "Частота дополнительных продаж существенно снизилась "
                        + "при достаточной базе."
        );
        assertThat(employee.summary()).isEqualTo(
                "Результат сотрудника существенно улучшился относительно "
                        + "его прошлого периода."
        );
    }

    @Test
    void keepsUnknownThemeOnSafeVerifiedFallback() {
        WeeklyCandidateDisplayPolicy.Narrative narrative =
                WeeklyCandidateDisplayPolicy.forCandidate(candidate(
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
