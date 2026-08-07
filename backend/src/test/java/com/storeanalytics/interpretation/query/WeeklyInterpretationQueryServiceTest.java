package com.storeanalytics.interpretation.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.interpretation.exception.WeeklyInterpretationNotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyInterpretationQueryServiceTest {

    private final WeeklyInterpretationQueryRepository repository = mock(
            WeeklyInterpretationQueryRepository.class
    );
    private final WeeklyInterpretationQueryService service =
            new WeeklyInterpretationQueryService(repository);

    @Test
    void buildsBoundedHistoryPageFromCurrentRevisions() {
        UUID storeId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 8, 2);
        when(repository.countCurrent(storeId, from, to)).thenReturn(13L);
        when(repository.listCurrent(storeId, from, to, 12, 0L))
                .thenReturn(List.of());

        var result = service.list(storeId, from, to, 0, 12);

        assertThat(result.totalElements()).isEqualTo(13);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.hasPrevious()).isFalse();
        verify(repository).listCurrent(storeId, from, to, 12, 0L);
    }

    @Test
    void rejectsReversedHistoryRangeBeforeQueryingDatabase() {
        assertThatThrownBy(() -> service.list(
                UUID.randomUUID(),
                LocalDate.of(2026, 8, 3),
                LocalDate.of(2026, 8, 2),
                0,
                12
        )).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void hidesMissingAndCrossStoreInterpretationsBehindStableNotFound() {
        UUID storeId = UUID.randomUUID();
        UUID interpretationId = UUID.randomUUID();
        when(repository.findById(storeId, interpretationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(storeId, interpretationId))
                .isInstanceOf(WeeklyInterpretationNotFoundException.class);
    }
}
