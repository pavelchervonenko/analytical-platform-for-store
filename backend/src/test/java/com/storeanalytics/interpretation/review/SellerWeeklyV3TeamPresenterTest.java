package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.review.SellerWeeklyTeamFactsProjector.EmployeeContribution;
import com.storeanalytics.interpretation.review.SellerWeeklyTeamFactsProjector.PeriodEmployeeFacts;
import com.storeanalytics.interpretation.review.SellerWeeklyTeamFactsProjector.TeamFinancialFacts;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerWeeklyV3TeamPresenterTest {

    @Test
    void departedSellerKeepsMetricsAndHistoricalObservationButReceivesNoFutureAction() {
        TeamFinancialFacts facts = decliningFinancialFacts();
        var result = new SellerWeeklyV3TeamPresenter().presentHistorical(facts, true, true, java.util.Set.of());
        assertThat(result.cards()).singleElement().satisfies(item -> {
            assertThat(item.actionableNow()).isFalse();
            assertThat(item.card().action()).isNull();
            assertThat(item.card().attention()).isNotNull();
            assertThat(item.card().metrics().netRevenue().current()).isEqualByComparingTo("60");
            assertThat(item.card().limitations()).anyMatch(text -> text.contains("Не в текущей команде"));
        });
    }

    @Test
    void currentHistoricalMemberPreservesTheExistingPresentationAndPersonalAction() {
        TeamFinancialFacts facts = decliningFinancialFacts();
        var presenter = new SellerWeeklyV3TeamPresenter();
        assertThat(presenter.presentHistorical(facts, true, true,
                java.util.Set.of(facts.employees().getFirst().employeeId()))).isEqualTo(presenter.present(facts));
    }

    @Test
    void displayCapKeepsHiddenFinancialRemainder() {
        List<EmployeeContribution> employees = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            UUID id = new UUID(0, index + 1);
            var current = period("1");
            var previous = period("2");
            employees.add(new EmployeeContribution(id, "Synthetic " + index, current, previous));
        }
        var facts = new TeamFinancialFacts(employees, new BigDecimal("101"),
                new BigDecimal("202"), BigDecimal.ZERO, BigDecimal.ZERO);

        var result = new SellerWeeklyV3TeamPresenter().present(facts);

        assertThat(result.display().totalCount()).isEqualTo(101);
        assertThat(result.display().displayedCount()).isEqualTo(100);
        assertThat(result.display().hiddenCount()).isEqualTo(1);
        assertThat(result.display().hiddenCurrentNetRevenue()).isEqualByComparingTo("1");
        assertThat(result.display().hiddenPreviousNetRevenue()).isEqualByComparingTo("2");
        assertThat(result.cards()).hasSize(100);
        assertThat(result.cards()).allMatch(card -> card.card().action() == null);
        assertThat(result.cards().getFirst().card().metrics().completedSales().sufficiency())
                .isEqualTo(Sufficiency.INSUFFICIENT);
        assertThat(result.team().roster().participatesInBenchmark()).isZero();
    }

    @Test
    void sufficientlySampledFinancialDeclineCreatesPersonalActionWithoutShifts() {
        UUID id = UUID.randomUUID();
        var current = new PeriodEmployeeFacts(new BigDecimal("60"), new BigDecimal("60"),
                BigDecimal.ZERO, new BigDecimal("10"), 6, 0, 6, 6);
        var previous = new PeriodEmployeeFacts(new BigDecimal("100"), new BigDecimal("100"),
                BigDecimal.ZERO, new BigDecimal("20"), 6, 0, 6, 6);
        var facts = new TeamFinancialFacts(List.of(new EmployeeContribution(id, "Synthetic", current, previous)),
                new BigDecimal("60"), new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("20"));

        var result = new SellerWeeklyV3TeamPresenter().present(facts);

        assertThat(result.team().state()).isEqualTo(BlockState.READY);
        assertThat(result.team().attentionEmployeeCount()).isEqualTo(1);
        assertThat(result.cards()).singleElement().satisfies(item -> {
            assertThat(item.card().sortGroup()).isEqualTo("FINANCIAL_ONLY");
            assertThat(item.card().attention()).isNotNull();
            assertThat(item.card().action()).isNotNull();
            assertThat(item.card().action().scope()).isEqualTo("EMPLOYEE");
            assertThat(item.card().metrics().shiftCount().current()).isNull();
            assertThat(item.card().peerComparison()).isNull();
        });
    }

    @Test
    void smallSalesSampleDoesNotProducePersonalAction() {
        var current = new PeriodEmployeeFacts(new BigDecimal("60"), new BigDecimal("60"),
                BigDecimal.ZERO, new BigDecimal("10"), 3, 0, 3, 3);
        var previous = new PeriodEmployeeFacts(new BigDecimal("100"), new BigDecimal("100"),
                BigDecimal.ZERO, new BigDecimal("20"), 3, 0, 3, 3);
        var facts = new TeamFinancialFacts(List.of(new EmployeeContribution(
                UUID.randomUUID(), "Synthetic", current, previous)),
                new BigDecimal("60"), new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("20"));

        var result = new SellerWeeklyV3TeamPresenter().present(facts);

        assertThat(result.team().state()).isEqualTo(BlockState.READY);
        assertThat(result.team().attentionEmployeeCount()).isZero();
        assertThat(result.cards().getFirst().card().action()).isNull();
        assertThat(result.cards().getFirst().card().metrics().netRevenue().sufficiency())
                .isEqualTo(Sufficiency.LIMITED);
    }

    @Test
    void unclassifiedAdditionalSalesOnlyLimitAdditionalPersonalConclusion() {
        var result = new SellerWeeklyV3TeamPresenter().present(decliningFinancialFacts(), true, false);

        assertThat(result.team().state()).isEqualTo(BlockState.LIMITED);
        assertThat(result.cards()).singleElement().satisfies(item -> {
            assertThat(item.card().metrics().netRevenue().metricState()).isEqualTo(MetricState.READY);
            assertThat(item.card().metrics().additionalRevenue().metricState()).isEqualTo(MetricState.LIMITED);
            assertThat(item.card().action().metricCode()).isEqualTo("NET_REVENUE");
            assertThat(item.card().ownDynamics()).allMatch(observation ->
                    observation.observationId().contains("net_revenue"));
        });
    }

    @Test
    void uncertainReturnAttributionSuppressesPersonalFinancialActions() {
        var result = new SellerWeeklyV3TeamPresenter().present(decliningFinancialFacts(), false, true);

        assertThat(result.team().state()).isEqualTo(BlockState.LIMITED);
        assertThat(result.team().attentionEmployeeCount()).isZero();
        assertThat(result.cards()).singleElement().satisfies(item -> {
            assertThat(item.card().metrics().netRevenue().metricState()).isEqualTo(MetricState.LIMITED);
            assertThat(item.card().action()).isNull();
            assertThat(item.card().attention()).isNull();
        });
    }

    @Test
    void noFinancialActivityDoesNotClaimTeamAssessment() {
        var empty = new PeriodEmployeeFacts(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0);
        var facts = new TeamFinancialFacts(List.of(new EmployeeContribution(
                UUID.randomUUID(), "Synthetic", empty, empty)),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        var result = new SellerWeeklyV3TeamPresenter().present(facts);

        assertThat(result.team().state()).isEqualTo(BlockState.INSUFFICIENT);
        assertThat(result.team().roster().activeAssignedWithActivity()).isZero();
        assertThat(result.display().totalCount()).isEqualTo(1);
    }

    private PeriodEmployeeFacts period(String net) {
        return new PeriodEmployeeFacts(new BigDecimal(net), new BigDecimal(net),
                BigDecimal.ZERO, BigDecimal.ZERO, 1, 0, 1, 1);
    }

    private TeamFinancialFacts decliningFinancialFacts() {
        var current = new PeriodEmployeeFacts(new BigDecimal("60"), new BigDecimal("60"),
                BigDecimal.ZERO, new BigDecimal("10"), 6, 0, 6, 6);
        var previous = new PeriodEmployeeFacts(new BigDecimal("100"), new BigDecimal("100"),
                BigDecimal.ZERO, new BigDecimal("20"), 6, 0, 6, 6);
        return new TeamFinancialFacts(List.of(new EmployeeContribution(
                UUID.randomUUID(), "Synthetic", current, previous)),
                new BigDecimal("60"), new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("20"));
    }
}
