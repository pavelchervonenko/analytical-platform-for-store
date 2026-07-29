package com.storeanalytics.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.metrics.model.ReportType;
import com.storeanalytics.metrics.repository.ReportSnapshotRepository;
import com.storeanalytics.metrics.repository.ReportSummaryProjection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

class ReportQueryServiceTest {

    private ReportSnapshotRepository repository;
    private ReportSnapshotCodec codec;
    private ReportQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReportSnapshotRepository.class);
        codec = mock(ReportSnapshotCodec.class);
        service = new ReportQueryService(repository, codec);
    }

    @Test
    void listsScalarProjectionWithoutDecodingPayload() {
        UUID storeId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        ReportSummaryProjection projection = mock(ReportSummaryProjection.class);
        when(projection.getId()).thenReturn(reportId);
        when(projection.getStoreId()).thenReturn(storeId);
        when(projection.getType()).thenReturn(ReportType.MONTHLY);
        when(projection.getPeriodStart()).thenReturn(LocalDate.of(2026, 6, 1));
        when(projection.getPeriodEnd()).thenReturn(LocalDate.of(2026, 6, 30));
        when(projection.getStatus()).thenReturn(ReportStatus.FINALIZED);
        when(projection.getRevision()).thenReturn(2);
        when(projection.getCurrentRevision()).thenReturn(true);
        when(projection.getTemplateVersion()).thenReturn("store-monthly-report-v1");
        when(projection.getSchemaVersion()).thenReturn(1);
        when(projection.getFinalizedAt()).thenReturn(Instant.parse("2026-07-01T10:00:00Z"));
        when(repository.findArchiveSummaries(
                eq(storeId),
                eq(LocalDate.of(2026, 1, 1)),
                eq(LocalDate.of(2026, 12, 31)),
                eq(List.of(ReportType.MONTHLY)),
                eq(ReportStatus.FINALIZED),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(projection),
                PageRequest.of(0, 25),
                30
        ));

        var result = service.list(storeId, 2026, ReportType.MONTHLY, 0, 25);

        assertThat(result.items()).singleElement().satisfies(report -> {
            assertThat(report.id()).isEqualTo(reportId);
            assertThat(report.currentRevision()).isTrue();
            assertThat(report.coverage()).isEqualTo(ReportCoverageStatus.COMPLETE);
        });
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(25);
        assertThat(result.totalElements()).isEqualTo(30);
        assertThat(result.hasNext()).isTrue();
        verifyNoInteractions(codec);
    }

    @Test
    void rejectsUnsupportedYearAndPageSizeBeforeQuery() {
        assertThatThrownBy(() -> service.list(
                UUID.randomUUID(), 1999, ReportType.ANNUAL, 0, 20
        )).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(
                UUID.randomUUID(), 2026, ReportType.ANNUAL, 0, 101
        )).isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(repository, codec);
    }

    @Test
    void delegatesDistinctArchiveYearsToRepository() {
        UUID storeId = UUID.randomUUID();
        when(repository.findFinalizedYears(storeId)).thenReturn(List.of(2026, 2025));

        assertThat(service.years(storeId)).containsExactly(2026, 2025);

        verify(repository).findFinalizedYears(storeId);
    }
}
