package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality.SECONDARY;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency.INSUFFICIENT;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency.SUFFICIENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateKind;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateSignal;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EmployeeFacts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklyAnalyticalCandidateProjectorTest {

    private final WeeklyAnalyticalCandidateProjector projector =
            new WeeklyAnalyticalCandidateProjector(
                    new WeeklySnapshotPolicyV3()
            );

    @Test
    void selectsOnlyMaterialStorePlanCategoryAndAttachMovements() {
        List<Fact> store = List.of(
                fact(
                        "STORE.NET_REVENUE.CURRENT", "NET_REVENUE", null,
                        Unit.MONEY, "120000", "100000", SUFFICIENT
                ),
                fact(
                        "STORE.CATEGORY:ACCESSORY.NET_REVENUE.CURRENT",
                        "NET_REVENUE", "ACCESSORY", Unit.MONEY,
                        "18000", "10000", SUFFICIENT
                ),
                fact(
                        "STORE.CATEGORY:ACCESSORY.REVENUE_SHARE_PERCENT.CURRENT",
                        "REVENUE_SHARE_PERCENT", "ACCESSORY", Unit.PERCENT,
                        "15", "10", SUFFICIENT
                ),
                fact(
                        "STORE.CATEGORY:TINY.NET_REVENUE.CURRENT",
                        "NET_REVENUE", "TINY", Unit.MONEY,
                        "200", "100", SUFFICIENT
                ),
                fact(
                        "STORE.CATEGORY:TINY.REVENUE_SHARE_PERCENT.CURRENT",
                        "REVENUE_SHARE_PERCENT", "TINY", Unit.PERCENT,
                        "0.2", "0.1", SUFFICIENT
                ),
                fact(
                        "STORE.PLAN:ACCESSORIES.PROJECTED_COMPLETION_PERCENT",
                        "PLAN_PROJECTED_COMPLETION_PERCENT", null, Unit.PERCENT,
                        "88", null, SUFFICIENT
                ),
                fact(
                        "STORE.PLAN:ACCESSORIES.ACTUAL_AMOUNT",
                        "PLAN_ACTUAL_AMOUNT", null, Unit.MONEY,
                        "6300", null, SUFFICIENT
                ),
                fact(
                        "STORE.PLAN:ACCESSORIES.TARGET_AMOUNT",
                        "PLAN_TARGET_AMOUNT", null, Unit.MONEY,
                        "10000", null, SUFFICIENT
                ),
                fact(
                        "STORE.ATTACH:CASE_TO_PHONE.RATE_PER_HUNDRED.CURRENT",
                        "RATE_PER_HUNDRED", "ACCESSORY", Unit.RATE_PER_HUNDRED,
                        "42", "50", SUFFICIENT
                ),
                fact(
                        "STORE.ATTACH:CASE_TO_PHONE.DENOMINATOR_QUANTITY.CURRENT",
                        "DENOMINATOR_QUANTITY", "ACCESSORY", Unit.COUNT,
                        "10", "9", SUFFICIENT
                ),
                fact(
                        "STORE.ATTACH:WEAK_SAMPLE.RATE_PER_HUNDRED.CURRENT",
                        "RATE_PER_HUNDRED", "ACCESSORY", Unit.RATE_PER_HUNDRED,
                        "20", "50", INSUFFICIENT
                ),
                fact(
                        "STORE.ATTACH:WEAK_SAMPLE.DENOMINATOR_QUANTITY.CURRENT",
                        "DENOMINATOR_QUANTITY", "ACCESSORY", Unit.COUNT,
                        "2", "8", INSUFFICIENT
                )
        );

        WeeklyAnalyticalCandidateProjector.Projection result =
                projector.project(store, List.of());

        assertThat(result.candidates())
                .extracting(CandidateSignal::theme)
                .contains(
                        "REVENUE_DYNAMICS",
                        "CATEGORY_MIX",
                        "PLAN",
                        "ATTACH_RATE"
                );
        assertThat(result.candidates())
                .filteredOn(candidate -> "PLAN".equals(candidate.theme()))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.kind()).isEqualTo(CandidateKind.RISK);
                    assertThat(candidate.evidenceRefs()).containsExactly(
                            "STORE.PLAN:ACCESSORIES.PROJECTED_COMPLETION_PERCENT",
                            "STORE.PLAN:ACCESSORIES.ACTUAL_AMOUNT",
                            "STORE.PLAN:ACCESSORIES.TARGET_AMOUNT"
                    );
                });
        assertThat(result.candidates())
                .filteredOn(candidate -> "CATEGORY_MIX".equals(candidate.theme()))
                .singleElement()
                .extracting(CandidateSignal::categoryCode)
                .isEqualTo("ACCESSORY");
        assertThat(result.candidates())
                .noneMatch(candidate -> candidate.evidenceRefs().stream()
                        .anyMatch(reference -> reference.contains("TINY")
                                || reference.contains("WEAK_SAMPLE")));
        assertThat(result.candidates())
                .extracting(CandidateSignal::candidateRef)
                .containsExactly("C001", "C002", "C003", "C004");
    }

    @Test
    void selectsEmployeeAttachMovementOnlyWithSufficientPairOfSamples() {
        EmployeeFacts employee = new EmployeeFacts(
                "E01",
                SUFFICIENT,
                List.of("ATTACH"),
                List.of(
                        fact(
                                "EMP:E01.ATTACH:CASE_TO_PHONE.RATE_PER_HUNDRED.CURRENT",
                                "RATE_PER_HUNDRED", "ACCESSORY",
                                Unit.RATE_PER_HUNDRED, "40", "50", SUFFICIENT
                        ),
                        fact(
                                "EMP:E01.ATTACH:CASE_TO_PHONE."
                                        + "DENOMINATOR_QUANTITY.CURRENT",
                                "DENOMINATOR_QUANTITY", "ACCESSORY",
                                Unit.COUNT, "8", "7", SUFFICIENT
                        )
                )
        );

        WeeklyAnalyticalCandidateProjector.Projection result =
                projector.project(List.of(), List.of(employee));

        assertThat(result.candidates())
                .filteredOn(candidate -> "ATTACH_RATE".equals(candidate.theme()))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.employeeRef()).isEqualTo("E01");
                    assertThat(candidate.kind()).isEqualTo(CandidateKind.RISK);
                    assertThat(candidate.evidenceRefs()).containsExactly(
                            "EMP:E01.ATTACH:CASE_TO_PHONE."
                                    + "RATE_PER_HUNDRED.CURRENT",
                            "EMP:E01.ATTACH:CASE_TO_PHONE."
                                    + "DENOMINATOR_QUANTITY.CURRENT"
                    );
                });
    }

    @Test
    void buildsTeamDistributionUniqueLeadersMostImprovedAndLearningPairs() {
        List<EmployeeFacts> employees = List.of(
                employee("E01", "100", "90", "70", "1000"),
                employee("E02", "80", "80", "75", "800"),
                employee("E03", "60", "70", "70", "500"),
                employee("E04", "40", "60", "60", "300")
        );
        List<Fact> store = List.of(fact(
                "STORE.CATEGORY:PHONE_NEW.NET_REVENUE.CURRENT",
                "NET_REVENUE", "PHONE_NEW", Unit.MONEY,
                "2600", "2400", SUFFICIENT
        ));

        WeeklyAnalyticalCandidateProjector.Projection result =
                projector.project(store, employees);

        assertThat(result.teamFacts())
                .filteredOn(fact -> "TEAM_RATING_CONTRIBUTION_SCORE_MEDIAN"
                        .equals(fact.metricCode()))
                .singleElement()
                .extracting(Fact::value)
                .isEqualTo(new BigDecimal("70.0000"));
        assertThat(result.teamFacts())
                .filteredOn(fact -> "TEAM_NET_REVENUE_Q1".equals(fact.metricCode()))
                .singleElement()
                .extracting(Fact::categoryCode)
                .isEqualTo("PHONE_NEW");

        assertThat(result.candidates())
                .filteredOn(candidate -> "COMPETENCY_LEADER".equals(candidate.theme())
                        && "COMMERCIAL_CONTRIBUTION".equals(
                        candidate.competencyCode()
                ))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.employeeRef()).isEqualTo("E01");
                    assertThat(candidate.targetEmployeeRefs()).isEmpty();
                    assertThat(candidate.sufficiency()).isEqualTo(SUFFICIENT);
                });
        assertThat(result.candidates())
                .filteredOn(candidate -> "LEARNING_OPPORTUNITY".equals(candidate.theme())
                        && "COMMERCIAL_CONTRIBUTION".equals(
                        candidate.competencyCode()
                ))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.employeeRef()).isEqualTo("E01");
                    assertThat(candidate.targetEmployeeRefs())
                            .containsExactly("E03", "E04");
                });
        assertThat(result.candidates())
                .filteredOn(candidate -> "MOST_IMPROVED".equals(candidate.theme()))
                .singleElement()
                .extracting(CandidateSignal::employeeRef)
                .isEqualTo("E01");
        assertThat(result.candidates())
                .extracting(CandidateSignal::candidateRef)
                .doesNotHaveDuplicates();
    }

    @Test
    void doesNotBuildTeamClaimsFromTwoEmployees() {
        WeeklyAnalyticalCandidateProjector.Projection result = projector.project(
                List.of(),
                List.of(
                        employee("E01", "100", "90", "70", "1000"),
                        employee("E02", "80", "80", "75", "800")
                )
        );

        assertThat(result.teamFacts()).isEmpty();
        assertThat(result.candidates())
                .noneMatch(candidate -> "COMPETENCY_LEADER".equals(candidate.theme())
                        || "LEARNING_OPPORTUNITY".equals(candidate.theme())
                        || "MOST_IMPROVED".equals(candidate.theme()));
    }

    private EmployeeFacts employee(
            String employeeRef,
            String contributionScore,
            String overallScore,
            String previousOverallScore,
            String categoryRevenue
    ) {
        return new EmployeeFacts(
                employeeRef,
                SUFFICIENT,
                List.of("RESULT", "RATING", "CATEGORIES"),
                List.of(
                        fact(
                                "EMP:" + employeeRef
                                        + ".RATING.CONTRIBUTION_SCORE.CURRENT",
                                "RATING_CONTRIBUTION_SCORE", null, Unit.SCORE,
                                contributionScore, contributionScore, SUFFICIENT
                        ),
                        fact(
                                "EMP:" + employeeRef
                                        + ".RATING.OVERALL_SCORE.CURRENT",
                                "RATING_OVERALL_SCORE", null, Unit.SCORE,
                                overallScore, previousOverallScore, SUFFICIENT
                        ),
                        fact(
                                "EMP:" + employeeRef
                                        + ".CATEGORY:PHONE_NEW.NET_REVENUE.CURRENT",
                                "NET_REVENUE", "PHONE_NEW", Unit.MONEY,
                                categoryRevenue, categoryRevenue, SUFFICIENT
                        )
                )
        );
    }

    private Fact fact(
            String evidenceRef,
            String metricCode,
            String categoryCode,
            Unit unit,
            String current,
            String previous,
            Sufficiency sufficiency
    ) {
        return SnapshotFactFactory.numeric(
                evidenceRef,
                metricCode,
                new BigDecimal(current),
                previous == null ? null : new BigDecimal(previous),
                new SnapshotFactFactory.FactOptions(
                        categoryCode,
                        unit,
                        sufficiency,
                        SECONDARY,
                        true
                )
        );
    }
}
