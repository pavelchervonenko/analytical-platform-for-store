package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.EmployeeCard;
import com.storeanalytics.interpretation.snapshot.EmployeeSalesSampleFacts;
import com.storeanalytics.performance.service.EmployeeRatingEntry;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewTeamEmployeeProjectorTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final LocalDate START = LocalDate.of(2026, 8, 17);
    private static final LocalDate END = LocalDate.of(2026, 8, 23);

    private final WeeklyReviewTeamEmployeeProjector projector =
            new WeeklyReviewTeamEmployeeProjector(new WeeklyReviewPolicyV1());

    @Test
    void keepsTeamAggregateAndEmployeeContentStrictlySeparate() {
        EmployeeRatingEntry anna = employee("Анна", "700.00", 2, "16.00");
        EmployeeRatingEntry boris = employee("Борис", "500.00", 2, "16.00");
        EmployeeRatingEntry vera = employee("Вера", "300.00", 2, "16.00");

        WeeklyReviewTeamEmployeeProjector.Projection result = projector.project(
                ratings(anna, boris, vera),
                ratings(
                        copy(anna, "500.00"),
                        copy(boris, "500.00"),
                        copy(vera, "500.00")
                ),
                sales(anna, boris, vera, 6),
                sales(anna, boris, vera, 6),
                0,
                0,
                Map.of()
        );

        assertThat(result.team().roster().activeAssignedWithActivity()).isEqualTo(3);
        assertThat(result.team().roster().participatesInBenchmark()).isEqualTo(3);
        assertThat(result.team().benchmarkPolicy().label())
                .isEqualTo("Медиана магазина, 3 сотрудников");
        assertThat(result.team().toString())
                .doesNotContain("Анна", "Борис", "Вера", anna.employeeId().toString());
        assertThat(result.employees()).extracting(EmployeeCard::displayName)
                .containsExactly("Вера", "Анна", "Борис");
        assertThat(card(result, "Анна").peerComparison().benchmarkValue())
                .isEqualByComparingTo("500.00");
    }

    @Test
    void doesNotCreatePeerComparisonWhenOnlyTwoEmployeesAreEligible() {
        EmployeeRatingEntry anna = employee("Анна", "700.00", 2, "16.00");
        EmployeeRatingEntry boris = employee("Борис", "500.00", 2, "16.00");

        WeeklyReviewTeamEmployeeProjector.Projection result = projector.project(
                ratings(anna, boris),
                ratings(copy(anna, "600.00"), copy(boris, "600.00")),
                sales(anna, boris, 6),
                sales(anna, boris, 6),
                0,
                0,
                Map.of()
        );

        assertThat(result.employees()).allMatch(card -> card.peerComparison() == null);
        assertThat(result.team().benchmarkPolicy().label()).contains("минимум 3");
    }

    @Test
    void lowSampleShowsExactLimitationWithoutGenericNarrativeOrAction() {
        EmployeeRatingEntry employee = employee("Анна", "100.00", 1, "8.00");

        EmployeeCard result = projector.project(
                ratings(employee),
                ratings(copy(employee, "80.00")),
                sales(employee, 2),
                sales(employee, 2),
                0,
                0,
                Map.of()
        ).employees().getFirst();

        assertThat(result.sortGroup()).isEqualTo("LIMITED");
        assertThat(result.ownDynamics()).isEmpty();
        assertThat(result.action()).isNull();
        assertThat(result.limitations())
                .contains("Недостаточно продаж для сравнения: 2 и 2")
                .anyMatch(message -> message.contains("смен"));
    }

    @Test
    void materialNegativeDynamicsProducesOnePersonalActionOnlyOnEmployeeCard() {
        EmployeeRatingEntry current = employee("Анна", "700.00", 2, "16.00");
        EmployeeRatingEntry previous = copy(current, "1000.00");

        WeeklyReviewTeamEmployeeProjector.Projection result = projector.project(
                ratings(current),
                ratings(previous),
                sales(current, 6),
                sales(previous, 6),
                0,
                0,
                Map.of()
        );

        EmployeeCard card = result.employees().getFirst();
        assertThat(card.attention()).isNotNull();
        assertThat(card.action()).isNotNull();
        assertThat(card.action().employeePublicId()).isEqualTo(current.employeeId().toString());
        assertThat(result.team().attentionEmployeeCount()).isOne();
        assertThat(result.team().toString()).doesNotContain("Анна", current.employeeId().toString());
    }

    @Test
    void unattributedReturnsLimitOnlyAggregatePeopleQualityWithoutPersonalLeak() {
        EmployeeRatingEntry employee = employee("Анна", "700.00", 2, "16.00");

        WeeklyReviewTeamEmployeeProjector.Projection result = projector.project(
                ratings(employee),
                ratings(copy(employee, "700.00")),
                sales(employee, 6),
                sales(employee, 6),
                2,
                1,
                Map.of()
        );

        assertThat(result.team().state())
                .isEqualTo(WeeklyReviewResponse.BlockState.LIMITED);
        assertThat(result.team().limitations()).containsExactly(
                "Возвраты без продавца исходной продажи: "
                        + "2 за текущую неделю и 1 за предыдущую"
        );
        assertThat(result.team().toString())
                .doesNotContain("Анна", employee.employeeId().toString());
        assertThat(result.employees().getFirst().limitations()).isEmpty();
    }

    @Test
    void excludesInactiveEmployeeWithoutActivity() {
        EmployeeRatingEntry employee = employee("Анна", "0.00", 0, "0.00");

        WeeklyReviewTeamEmployeeProjector.Projection result = projector.project(
                ratings(employee),
                ratings(employee),
                sales(employee, 0),
                sales(employee, 0),
                0,
                0,
                Map.of()
        );

        assertThat(result.employees()).isEmpty();
        assertThat(result.team().roster().activeAssignedWithActivity()).isZero();
    }

    private EmployeeCard card(
            WeeklyReviewTeamEmployeeProjector.Projection projection,
            String name
    ) {
        return projection.employees().stream()
                .filter(card -> name.equals(card.displayName()))
                .findFirst()
                .orElseThrow();
    }

    private EmployeeRatingResult ratings(EmployeeRatingEntry... employees) {
        return new EmployeeRatingResult(
                STORE_ID,
                START,
                END,
                null,
                null,
                List.of(employees),
                null
        );
    }

    private EmployeeSalesSampleFacts sales(
            EmployeeRatingEntry first,
            EmployeeRatingEntry second,
            int count
    ) {
        return new EmployeeSalesSampleFacts(Map.of(
                first.employeeId(), (long) count,
                second.employeeId(), (long) count
        ));
    }

    private EmployeeSalesSampleFacts sales(
            EmployeeRatingEntry first,
            EmployeeRatingEntry second,
            EmployeeRatingEntry third,
            int count
    ) {
        return new EmployeeSalesSampleFacts(Map.of(
                first.employeeId(), (long) count,
                second.employeeId(), (long) count,
                third.employeeId(), (long) count
        ));
    }

    private EmployeeSalesSampleFacts sales(EmployeeRatingEntry employee, int count) {
        return new EmployeeSalesSampleFacts(Map.of(employee.employeeId(), (long) count));
    }

    private EmployeeRatingEntry employee(
            String name,
            String revenue,
            long shifts,
            String hours
    ) {
        BigDecimal amount = new BigDecimal(revenue);
        BigDecimal workedHours = new BigDecimal(hours);
        BigDecimal perHour = workedHours.signum() == 0
                ? null
                : amount.divide(workedHours, 2, java.math.RoundingMode.HALF_UP);
        return new EmployeeRatingEntry(
                UUID.randomUUID(),
                name,
                true,
                true,
                true,
                true,
                shifts,
                workedHours,
                amount,
                BigDecimal.ZERO,
                null,
                perHour,
                amount.multiply(new BigDecimal("0.10")),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                amount.multiply(new BigDecimal("0.10")),
                new BigDecimal("10.00"),
                null,
                false,
                null,
                List.of()
        );
    }

    private EmployeeRatingEntry copy(EmployeeRatingEntry source, String revenue) {
        BigDecimal amount = new BigDecimal(revenue);
        BigDecimal perHour = amount.divide(
                source.workedHours(), 2, java.math.RoundingMode.HALF_UP
        );
        return new EmployeeRatingEntry(
                source.employeeId(),
                source.displayName(),
                source.employeeActive(),
                source.assignmentActive(),
                source.participatesInRanking(),
                source.ratingEligible(),
                source.shiftCount(),
                source.workedHours(),
                amount,
                BigDecimal.ZERO,
                null,
                perHour,
                amount.multiply(new BigDecimal("0.10")),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                amount.multiply(new BigDecimal("0.10")),
                new BigDecimal("10.00"),
                null,
                false,
                null,
                List.of()
        );
    }
}
