package com.storeanalytics.interpretation.query;

import com.storeanalytics.common.web.PageParameters;
import com.storeanalytics.common.web.PageResponse;
import com.storeanalytics.interpretation.exception.WeeklyInterpretationNotFoundException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeeklyInterpretationQueryService {

    private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);

    private final WeeklyInterpretationQueryRepository repository;

    public WeeklyInterpretationQueryService(
            WeeklyInterpretationQueryRepository repository
    ) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public WeeklyInterpretationDetailView latest(UUID storeId) {
        return repository.findLatest(storeId).orElseThrow(
                WeeklyInterpretationNotFoundException::latest
        );
    }

    @Transactional(readOnly = true)
    public WeeklyInterpretationDetailView get(
            UUID storeId,
            UUID interpretationId
    ) {
        return repository.findById(storeId, interpretationId).orElseThrow(
                () -> WeeklyInterpretationNotFoundException.byId(interpretationId)
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<WeeklyInterpretationSummaryView> list(
            UUID storeId,
            LocalDate periodStartFrom,
            LocalDate periodEndTo,
            int page,
            int size
    ) {
        PageParameters parameters = new PageParameters(page, size);
        LocalDate from = periodStartFrom == null ? MIN_DATE : periodStartFrom;
        LocalDate to = periodEndTo == null ? MAX_DATE : periodEndTo;
        if (to.isBefore(from)) {
            throw new com.storeanalytics.common.exception.InvalidRequestException(
                    "periodEndTo must not be before periodStartFrom"
            );
        }
        long total = repository.countCurrent(storeId, from, to);
        var items = repository.listCurrent(
                storeId,
                from,
                to,
                parameters.size(),
                (long) parameters.page() * parameters.size()
        );
        int totalPages = total == 0
                ? 0 : (int) Math.ceilDiv(total, parameters.size());
        return new PageResponse<>(
                items,
                parameters.page(),
                parameters.size(),
                total,
                totalPages,
                parameters.page() + 1 < totalPages,
                parameters.page() > 0
        );
    }
}
