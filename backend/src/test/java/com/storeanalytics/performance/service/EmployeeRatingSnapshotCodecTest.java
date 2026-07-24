package com.storeanalytics.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.performance.model.EmployeeRatingSnapshot;
import com.storeanalytics.store.model.Store;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmployeeRatingSnapshotCodecTest {

    private final EmployeeRatingSnapshotCodec codec = new EmployeeRatingSnapshotCodec(
            new ObjectMapper().findAndRegisterModules()
    );

    @Test
    void verifiesPayloadAndRestoresFinalizationMetadata() {
        EmployeeRatingResult live = result();
        String payload = codec.encode(live);
        EmployeeRatingSnapshot snapshot = snapshot(payload, codec.sha256(payload));

        EmployeeRatingResult restored = codec.decode(snapshot);

        assertThat(restored.storeId()).isEqualTo(live.storeId());
        assertThat(restored.formula()).isEqualTo(live.formula());
        assertThat(restored.employees()).isEqualTo(live.employees());
        assertThat(restored.history().status())
                .isEqualTo(EmployeeRatingHistoryStatus.FINALIZED);
        assertThat(restored.history().snapshotId()).isEqualTo(snapshot.getId());
        assertThat(restored.history().finalizedByName()).isEqualTo("Rating Manager");
    }

    @Test
    void rejectsPayloadWhoseHashDoesNotMatch() {
        String payload = codec.encode(result());
        EmployeeRatingSnapshot snapshot = snapshot(payload, "b".repeat(64));

        assertThatThrownBy(() -> codec.decode(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("employee rating snapshot integrity check failed");
    }

    private EmployeeRatingSnapshot snapshot(String payload, String hash) {
        EmployeeRatingResult result = result();
        EmployeeRatingSnapshot snapshot = mock(EmployeeRatingSnapshot.class);
        Store store = mock(Store.class);
        AppUser actor = mock(AppUser.class);
        when(store.getId()).thenReturn(result.storeId());
        when(actor.getId()).thenReturn(UUID.fromString(
                "20000000-0000-0000-0000-000000000001"
        ));
        when(snapshot.getId()).thenReturn(UUID.fromString(
                "30000000-0000-0000-0000-000000000001"
        ));
        when(snapshot.getStore()).thenReturn(store);
        when(snapshot.getPeriodStart()).thenReturn(result.periodStart());
        when(snapshot.getPeriodEnd()).thenReturn(result.periodEnd());
        when(snapshot.getFormulaCode()).thenReturn(result.formula().version());
        when(snapshot.getResultPayload()).thenReturn(payload);
        when(snapshot.getResultSha256()).thenReturn(hash);
        when(snapshot.getFinalizedBy()).thenReturn(actor);
        when(snapshot.getFinalizedByName()).thenReturn("Rating Manager");
        when(snapshot.getCreatedAt()).thenReturn(Instant.parse("2026-08-01T08:00:00Z"));
        return snapshot;
    }

    private EmployeeRatingResult result() {
        BigDecimal twentyFive = new BigDecimal("25.00");
        return new EmployeeRatingResult(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                new RatingFormulaView(
                        "employee-rating-v1",
                        twentyFive,
                        twentyFive,
                        twentyFive,
                        twentyFive,
                        new BigDecimal("50.00"),
                        new BigDecimal("50.00"),
                        new BigDecimal("3.000"),
                        new BigDecimal("150.00"),
                        new BigDecimal("75.00")
                ),
                new RatingPlanContext(
                        true,
                        new BigDecimal("100.00"),
                        new BigDecimal("1000000.00"),
                        new BigDecimal("4.00"),
                        new BigDecimal("3.00"),
                        new BigDecimal("7.00"),
                        new BigDecimal("900000.00"),
                        new BigDecimal("90.00")
                ),
                List.of(),
                EmployeeRatingHistoryView.live()
        );
    }
}
