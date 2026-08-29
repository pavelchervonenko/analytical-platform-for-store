package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.BenchmarkPolicy;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Observation;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.RosterSummary;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.TeamBlock;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklyReviewResponseContractTest {

    @Test
    void teamContractContainsOnlyAggregatesAndNoEmployeeIdentity() {
        List<String> componentNames = Arrays.stream(TeamBlock.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames).containsExactly(
                "blockId",
                "state",
                "roster",
                "observations",
                "attentionEmployeeCount",
                "benchmarkPolicy",
                "limitations"
        );
        assertThat(componentNames).noneMatch(name ->
                name.toLowerCase().contains("employeeid")
                        || name.toLowerCase().contains("name")
                        || name.toLowerCase().contains("action")
        );
    }

    @Test
    void teamCollectionsAreDefensiveAndObservationCountIsBounded() {
        List<Observation> observations = new ArrayList<>();
        TeamBlock team = team(observations);
        observations.add(observation("late"));

        assertThat(team.observations()).isEmpty();
        assertThatThrownBy(() -> team(List.of(
                observation("one"),
                observation("two"),
                observation("three")
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most two");
    }

    @Test
    void benchmarkRequiresTheApprovedThreeEmployeeSample() {
        assertThatThrownBy(() -> new BenchmarkPolicy(
                "MEDIAN", 2, "Медиана магазина"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be 3");
    }

    private TeamBlock team(List<Observation> observations) {
        return new TeamBlock(
                "team",
                BlockState.READY,
                new RosterSummary(5, 4, 4, 1, 1),
                observations,
                1,
                new BenchmarkPolicy("MEDIAN", 3, "Медиана магазина, 4 сотрудника"),
                List.of()
        );
    }

    private Observation observation(String id) {
        return new Observation(
                id,
                "Командное изменение",
                "Показатель изменился минимум у двух сотрудников",
                WeeklyReviewResponse.Effect.NEUTRAL,
                List.of("TEAM." + id.toUpperCase())
        );
    }
}
