package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Action;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Direction;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Evidence;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Factor;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState;
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
    void emitsOnlyStoreFactsSelectorsAndReferencedEvidence() throws Exception {
        WeeklyReviewResponse response = response(ReportState.READY, "STORE");

        WeeklyReviewAiInput result = compactor.compact(response);
        String json = objectMapper.writeValueAsString(result);

        assertThat(new LlmJsonSchemaValidator(WeeklyReviewAiContract.INPUT_SCHEMA)
                .validate(json)).isEmpty();
        assertThat(result.promptVersion())
                .isEqualTo(WeeklyReviewAiContract.PROMPT_VERSION);
        assertThat(result.reportState()).isEqualTo("READY");
        assertThat(result.summary().outcomeEffect()).isEqualTo("POSITIVE");
        assertThat(result.summary().allowedSelectors()).containsExactly("SUMMARY_RISK");
        assertThat(result.summary().allowedFocusFactorIds())
                .containsExactly("factor:return_revenue");
        assertThat(result.summary().evidenceRefs())
                .containsExactly("STORE.NET_REVENUE");
        assertThat(result.factors()).singleElement().satisfies(factor -> {
            assertThat(factor.factorId()).isEqualTo("factor:return_revenue");
            assertThat(factor.kind()).isEqualTo("RETURN_CHANGE");
            assertThat(factor.direction()).isEqualTo("UP");
            assertThat(factor.effect()).isEqualTo("NEGATIVE");
            assertThat(factor.causalLanguageAllowed()).isTrue();
            assertThat(factor.allowedSelectors()).containsExactly(
                    "FACTOR_RISK",
                    "FACTOR_CONTROL"
            );
        });
        assertThat(result.actions()).singleElement().satisfies(action -> {
            assertThat(action.title())
                    .isEqualTo("Проанализировать рост возвратов");
            assertThat(action.actionId())
                    .isEqualTo("action:restore:return_revenue");
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
                        "STORE.UNUSED",
                        "outcomeText",
                        "managementMeaning",
                        "allowedNarratives"
                );
    }

    @Test
    void emitsMixedSummaryWhenRevenueAndProfitMoveInOppositeDirections() {
        WeeklyReviewResponse response = response(ReportState.READY, "STORE");
        MetricComparison revenue = metric(
                "NET_REVENUE", Effect.POSITIVE, Materiality.MATERIAL
        );
        MetricComparison profit = metric(
                "GROSS_PROFIT", Effect.NEGATIVE, Materiality.MATERIAL
        );
        when(response.results()).thenReturn(List.of(revenue, profit));

        WeeklyReviewAiInput result = compactor.compact(response);

        assertThat(result.summary().outcomeEffect()).isEqualTo("MIXED");
    }

    @Test
    void emitsBoundedKindsDirectionsAndFramingChoices() {
        WeeklyReviewResponse response = response(ReportState.READY, "STORE");
        Factor structure = factor(
                "factor:structure", "STRUCTURE_CHANGE", Direction.DOWN
        );
        Factor attach = factor(
                "factor:attach", "ATTACH_CHANGE", Direction.UP
        );
        when(response.factors()).thenReturn(List.of(structure, attach));

        WeeklyReviewAiInput result = compactor.compact(response);

        assertThat(result.factors())
                .extracting(
                        WeeklyReviewAiInput.FactorSource::kind,
                        WeeklyReviewAiInput.FactorSource::direction
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "STRUCTURE_CHANGE", "DOWN"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "ATTACH_CHANGE", "UP"
                        )
                );
        assertThat(result.summary().allowedSelectors()).containsExactly("SUMMARY_STRENGTH");
    }

    @Test
    void inputTypeHasNoPersonTeamPlanOrPeriodFields() {
        assertThat(List.of(WeeklyReviewAiInput.class.getRecordComponents()))
                .extracting(component -> component.getName())
                .containsExactly(
                        "contractVersion",
                        "promptVersion",
                        "contentSchemaVersion",
                        "reportState",
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
        when(summary.outcome()).thenReturn(outcome);

        Factor factor = mock(Factor.class);
        when(factor.factorId()).thenReturn("factor:return_revenue");
        when(factor.kind()).thenReturn("RETURN_CHANGE");
        MetricComparison factorComparison = mock(MetricComparison.class);
        when(factorComparison.direction()).thenReturn(Direction.UP);
        when(factor.comparison()).thenReturn(factorComparison);
        when(factor.title()).thenReturn("Сумма возвратов выросла");
        when(factor.effect()).thenReturn(Effect.NEGATIVE);
        when(factor.contributionAmount()).thenReturn(new BigDecimal("-50.00"));
        when(factor.evidenceRefs()).thenReturn(List.of("STORE.RETURN_REVENUE"));

        Action action = mock(Action.class);
        when(action.actionId()).thenReturn("action:restore:return_revenue");
        when(action.scope()).thenReturn("STORE");
        when(action.title()).thenReturn("Проанализировать рост возвратов");
        when(action.check()).thenReturn("Сравнить следующую неделю с 50 ₽");
        when(action.evidenceRefs()).thenReturn(List.of("STORE.RETURN_REVENUE"));

        when(response.reportState()).thenReturn(reportState);
        MetricComparison revenue = metric(
                "NET_REVENUE", Effect.POSITIVE, Materiality.MATERIAL
        );
        MetricComparison profit = metric(
                "GROSS_PROFIT", Effect.POSITIVE, Materiality.MATERIAL
        );
        when(response.results()).thenReturn(List.of(revenue, profit));
        when(response.summary()).thenReturn(summary);
        when(response.factors()).thenReturn(List.of(factor));
        when(response.actions()).thenReturn(List.of(action));
        List<Evidence> selectedEvidence = List.of(
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
        when(response.evidence()).thenReturn(selectedEvidence);
        return response;
    }

    private Factor factor(String factorId, String kind, Direction direction) {
        Factor factor = mock(Factor.class);
        MetricComparison comparison = mock(MetricComparison.class);
        when(comparison.direction()).thenReturn(direction);
        when(factor.factorId()).thenReturn(factorId);
        when(factor.kind()).thenReturn(kind);
        when(factor.comparison()).thenReturn(comparison);
        when(factor.title()).thenReturn("Изменение показателя");
        when(factor.effect()).thenReturn(Effect.POSITIVE);
        when(factor.evidenceRefs()).thenReturn(List.of("STORE.RETURN_REVENUE"));
        return factor;
    }

    private NarrativeItem narrative(String text, List<String> evidenceRefs) {
        NarrativeItem item = mock(NarrativeItem.class);
        when(item.text()).thenReturn(text);
        when(item.effect()).thenReturn(Effect.POSITIVE);
        when(item.evidenceRefs()).thenReturn(evidenceRefs);
        return item;
    }

    private MetricComparison metric(
            String code,
            Effect effect,
            Materiality materiality
    ) {
        MetricComparison metric = mock(MetricComparison.class);
        when(metric.code()).thenReturn(code);
        when(metric.effect()).thenReturn(effect);
        when(metric.materiality()).thenReturn(materiality);
        when(metric.metricState()).thenReturn(MetricState.READY);
        return metric;
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
