package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Action;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Evidence;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Factor;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.NarrativeItem;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SummaryBlock;
import com.storeanalytics.interpretation.validation.LlmJsonSchemaValidator;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WeeklyReviewAiInputCompactorTest {

    private final WeeklyReviewAiInputCompactor compactor =
            new WeeklyReviewAiInputCompactor();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emitsOnlySelectedStoreWordingAndReferencedEvidence() throws Exception {
        WeeklyReviewResponse response = response(ReportState.READY, "STORE");

        WeeklyReviewAiInput result = compactor.compact(response);
        String json = objectMapper.writeValueAsString(result);

        assertThat(new LlmJsonSchemaValidator(WeeklyReviewAiContract.INPUT_SCHEMA)
                .validate(json)).isEmpty();
        assertThat(result.promptVersion()).isEqualTo("weekly-interpretation-v22");
        assertThat(result.summary().outcomeText())
                .isEqualTo("Чистая выручка — 1000 ₽ (+11,1%)");
        assertThat(result.summary().evidenceRefs())
                .containsExactly("STORE.NET_REVENUE");
        assertThat(result.summary().allowedNumericLiterals())
                .containsExactly("1000", "+11,1", "1000.00", "900.00");
        assertThat(result.factors()).singleElement().satisfies(factor -> {
            assertThat(factor.factorId()).isEqualTo("factor:return_revenue");
            assertThat(factor.causalLanguageAllowed()).isTrue();
            assertThat(factor.allowedNumericLiterals())
                    .containsExactly("100", "50", "+100,0", "100.00", "50.00");
        });
        assertThat(result.actions()).singleElement().satisfies(action -> {
            assertThat(action.actionId())
                    .isEqualTo("action:restore:return_revenue");
            assertThat(action.allowedNumericLiterals())
                    .containsExactly("50", "100.00", "50.00");
        });
        assertThat(result.evidence())
                .extracting(WeeklyReviewAiInput.EvidenceSource::evidenceRef)
                .containsExactly(
                        "STORE.NET_REVENUE",
                        "STORE.RETURN_REVENUE"
                );
        assertThat(json)
                .doesNotContain(
                        "employeePublicId",
                        "displayName",
                        "snapshotPublicId",
                        "monthlyPlan",
                        "period",
                        "STORE.UNUSED"
                );
        assertThat(List.of(WeeklyReviewAiInput.SummarySource.class
                .getRecordComponents()))
                .extracting(component -> component.getName())
                .containsExactly(
                        "outcomeText",
                        "evidenceRefs",
                        "allowedNumericLiterals"
                );
    }

    @Test
    void inputTypeHasNoPersonTeamOrPlanFields() {
        assertThat(List.of(WeeklyReviewAiInput.class.getRecordComponents()))
                .extracting(component -> component.getName())
                .containsExactly(
                        "contractVersion",
                        "promptVersion",
                        "contentSchemaVersion",
                        "summary",
                        "factors",
                        "actions",
                        "evidence"
                )
                .noneMatch(name -> name.toLowerCase().contains("employee")
                        || name.toLowerCase().contains("team")
                        || name.toLowerCase().contains("plan")
                        || name.toLowerCase().contains("period"));
    }

    @Test
    void refusesBlockedReportsAndNonStoreReferences() {
        assertThatThrownBy(() -> compactor.compact(response(
                ReportState.BLOCKED, "STORE"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("READY or PARTIAL");

        assertThatThrownBy(() -> compactor.compact(response(
                ReportState.READY, "EMPLOYEE"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("store evidence");
    }

    private WeeklyReviewResponse response(
            ReportState reportState,
            String evidenceScope
    ) {
        WeeklyReviewResponse response = mock(WeeklyReviewResponse.class);
        SummaryBlock summary = mock(SummaryBlock.class);
        NarrativeItem outcome = narrative(
                "Чистая выручка — 1000 ₽ (+11,1%)",
                List.of("STORE.NET_REVENUE")
        );
        NarrativeItem risk = narrative(
                "Сумма возвратов выросла",
                List.of("STORE.RETURN_REVENUE")
        );
        when(summary.outcome()).thenReturn(outcome);
        when(summary.positive()).thenReturn(null);
        when(summary.risk()).thenReturn(risk);

        Factor factor = mock(Factor.class);
        when(factor.factorId()).thenReturn("factor:return_revenue");
        when(factor.title()).thenReturn("Сумма возвратов выросла");
        when(factor.detail()).thenReturn("Возвраты: 100 ₽ против 50 ₽ (+100,0%)");
        when(factor.effect()).thenReturn(Effect.NEGATIVE);
        when(factor.contributionAmount()).thenReturn(new BigDecimal("-50.00"));
        when(factor.evidenceRefs()).thenReturn(List.of("STORE.RETURN_REVENUE"));

        Action action = mock(Action.class);
        when(action.actionId()).thenReturn("action:restore:return_revenue");
        when(action.scope()).thenReturn("STORE");
        when(action.title()).thenReturn("Снизить сумму возвратов");
        when(action.check()).thenReturn("Сравнить следующую неделю с 50 ₽");
        when(action.evidenceRefs()).thenReturn(List.of("STORE.RETURN_REVENUE"));

        when(response.reportState()).thenReturn(reportState);
        when(response.summary()).thenReturn(summary);
        when(response.factors()).thenReturn(List.of(factor));
        when(response.actions()).thenReturn(List.of(action));
        List<Evidence> evidence = List.of(
                evidence(
                        "STORE.NET_REVENUE",
                        evidenceScope,
                        "Чистая выручка",
                        new BigDecimal("1000.00"),
                        new BigDecimal("900.00")
                ),
                evidence(
                        "STORE.RETURN_REVENUE",
                        evidenceScope,
                        "Возвраты",
                        new BigDecimal("100.00"),
                        new BigDecimal("50.00")
                ),
                evidence(
                        "STORE.UNUSED",
                        "STORE",
                        "Неиспользованный факт",
                        BigDecimal.ONE,
                        BigDecimal.ZERO
                )
        );
        when(response.evidence()).thenReturn(evidence);
        return response;
    }

    private NarrativeItem narrative(String text, List<String> evidenceRefs) {
        NarrativeItem item = mock(NarrativeItem.class);
        when(item.text()).thenReturn(text);
        when(item.evidenceRefs()).thenReturn(evidenceRefs);
        return item;
    }

    private Evidence evidence(
            String evidenceRef,
            String scope,
            String label,
            BigDecimal current,
            BigDecimal previous
    ) {
        Evidence evidence = mock(Evidence.class);
        when(evidence.evidenceRef()).thenReturn(evidenceRef);
        when(evidence.scope()).thenReturn(scope);
        when(evidence.employeePublicId()).thenReturn(null);
        when(evidence.label()).thenReturn(label);
        when(evidence.unit()).thenReturn(WeeklyReviewResponse.Unit.RUB);
        when(evidence.currentValue()).thenReturn(current);
        when(evidence.previousValue()).thenReturn(previous);
        when(evidence.available()).thenReturn(true);
        return evidence;
    }
}
