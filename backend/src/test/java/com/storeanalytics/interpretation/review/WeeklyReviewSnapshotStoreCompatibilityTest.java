package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.metrics.warranty.AttachAttributionPolicy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class WeeklyReviewSnapshotStoreCompatibilityTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation ->
            "query".equals(invocation.getMethod().getName())
                    ? List.of() : Answers.RETURNS_DEFAULTS.answer(invocation));
    private final WeeklyReviewSnapshotStore store = new WeeklyReviewSnapshotStore(
            jdbc, mock(WeeklyReviewSnapshotCodec.class), mock(WeeklyReviewVersionedCodec.class),
            mock(AttachAttributionPolicy.class), mock(Clock.class), mock(WeeklyReviewAttributionRepository.class));

    @Test
    void legacyLatestReadFiltersToV2BeforeDeserialization() {
        UUID storeId = UUID.randomUUID();
        DateRange period = new DateRange(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23));

        assertThat(store.findLatest(storeId, period)).isEmpty();

        verify(jdbc).query(argThat((String sql) -> sql.contains("report_contract_version = 2")),
                ArgumentMatchers.<RowMapper<PersistedWeeklyReviewSnapshot>>any(),
                eq(storeId), eq(period.start()), eq(period.end()));
    }

    @Test
    void legacyByIdCannotDecodeAnUnsupportedV3Payload() {
        UUID snapshotId = UUID.randomUUID();

        assertThat(store.findById(snapshotId)).isEmpty();

        verify(jdbc).query(argThat((String sql) -> sql.contains("report_contract_version = 2")),
                ArgumentMatchers.<RowMapper<PersistedWeeklyReviewSnapshot>>any(), eq(snapshotId));
    }

    @Test
    void v3LatestReadFiltersBeforeDeserialization() {
        UUID storeId = UUID.randomUUID();
        DateRange period = new DateRange(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23));

        assertThat(store.findLatestV3(storeId, period)).isEmpty();

        verify(jdbc).query(argThat((String sql) -> sql.contains("report_contract_version = 3")),
                ArgumentMatchers.<RowMapper<PersistedWeeklyReviewV3Snapshot>>any(),
                eq(storeId), eq(period.start()), eq(period.end()));
    }

    @Test
    void v3ByIdReadFiltersBeforeDeserialization() {
        UUID snapshotId = UUID.randomUUID();

        assertThat(store.findV3ById(snapshotId)).isEmpty();

        verify(jdbc).query(argThat((String sql) -> sql.contains("report_contract_version = 3")),
                ArgumentMatchers.<RowMapper<PersistedWeeklyReviewV3Snapshot>>any(), eq(snapshotId));
    }
}
