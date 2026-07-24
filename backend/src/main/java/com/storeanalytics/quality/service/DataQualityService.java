package com.storeanalytics.quality.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.quality.model.DataQualityIssue;
import com.storeanalytics.quality.model.DataQualitySeverity;
import com.storeanalytics.quality.model.DataQualityStatus;
import com.storeanalytics.quality.repository.DataQualityIssueRepository;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.store.service.StoreCatalogService;
import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import com.storeanalytics.store.service.StoreSummaryView;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataQualityService {

    private static final Comparator<DataQualityIssueView> ISSUE_ORDER = Comparator
            .comparingInt((DataQualityIssueView issue) -> severityPriority(issue.severity()))
            .thenComparing(
                    DataQualityIssueView::detectedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())
            )
            .thenComparing(DataQualityIssueView::key);

    private final StoreCatalogService storeCatalogService;
    private final StoreDataStatusService dataStatusService;
    private final StoreRepository storeRepository;
    private final DataQualityIssueRepository issueRepository;
    private final Clock clock;

    public DataQualityService(
            StoreCatalogService storeCatalogService,
            StoreDataStatusService dataStatusService,
            StoreRepository storeRepository,
            DataQualityIssueRepository issueRepository,
            Clock clock
    ) {
        this.storeCatalogService = storeCatalogService;
        this.dataStatusService = dataStatusService;
        this.storeRepository = storeRepository;
        this.issueRepository = issueRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DataQualityOverviewView overview(UUID userId, UserRole role) {
        List<StoreSummaryView> stores = storeCatalogService.findAccessible(
                requireNonNull(userId, "userId"), requireNonNull(role, "role")
        );
        Map<UUID, List<DataQualityIssue>> issuesByStore = persistedIssuesByStore(
                stores.stream().map(StoreSummaryView::id).toList()
        );
        List<StoreDataQualitySummaryView> summaries = stores.stream()
                .map(store -> summaryFromPersisted(
                        store.id(),
                        store.name(),
                        dataStatusService.get(store.id()),
                        issuesByStore.getOrDefault(store.id(), List.of())
                ))
                .toList();
        return new DataQualityOverviewView(
                clock.instant(),
                summaries.size(),
                countStatus(summaries, DataQualityHealthStatus.OK),
                countStatus(summaries, DataQualityHealthStatus.WARNING),
                countStatus(summaries, DataQualityHealthStatus.ERROR),
                summaries.stream().mapToInt(StoreDataQualitySummaryView::openIssueCount).sum(),
                summaries
        );
    }

    @Transactional(readOnly = true)
    public StoreDataQualityView get(UUID storeId) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        Store store = storeRepository.findById(validatedStoreId)
                .orElseThrow(() -> new StoreNotFoundException(validatedStoreId));
        StoreDataStatusView dataStatus = dataStatusService.get(validatedStoreId);
        List<DataQualityIssue> persisted = issueRepository
                .findAllByStoreIdInAndStatus(List.of(validatedStoreId), DataQualityStatus.OPEN);
        List<DataQualityIssueView> issues = issues(dataStatus, persisted);
        return new StoreDataQualityView(
                summaryFromViews(validatedStoreId, store.getName(), dataStatus, issues),
                dataStatus,
                issues
        );
    }

    private Map<UUID, List<DataQualityIssue>> persistedIssuesByStore(List<UUID> storeIds) {
        if (storeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<DataQualityIssue>> result = new HashMap<>();
        issueRepository.findAllByStoreIdInAndStatus(storeIds, DataQualityStatus.OPEN)
                .forEach(issue -> result
                        .computeIfAbsent(issue.getStoreId(), ignored -> new ArrayList<>())
                        .add(issue));
        return result;
    }

    private StoreDataQualitySummaryView summaryFromPersisted(
            UUID storeId,
            String storeName,
            StoreDataStatusView dataStatus,
            List<DataQualityIssue> persisted
    ) {
        return summaryFromViews(storeId, storeName, dataStatus, issues(dataStatus, persisted));
    }

    private StoreDataQualitySummaryView summaryFromViews(
            UUID storeId,
            String storeName,
            StoreDataStatusView dataStatus,
            List<DataQualityIssueView> issues
    ) {
        int errors = countSeverity(issues, DataQualitySeverity.ERROR);
        int warnings = countSeverity(issues, DataQualitySeverity.WARNING);
        int information = countSeverity(issues, DataQualitySeverity.INFO);
        return new StoreDataQualitySummaryView(
                storeId,
                storeName,
                health(errors, warnings),
                dataStatus.status(),
                dataStatus.dataThroughDate(),
                dataStatus.lagDays(),
                issues.size(),
                errors,
                warnings,
                information,
                dataStatus.checkedAt()
        );
    }

    private List<DataQualityIssueView> issues(
            StoreDataStatusView dataStatus,
            List<DataQualityIssue> persisted
    ) {
        List<DataQualityIssueView> result = new ArrayList<>();
        synchronizationIssue(dataStatus).ifPresent(result::add);
        persisted.stream().map(this::issueView).forEach(result::add);
        result.sort(ISSUE_ORDER);
        return List.copyOf(result);
    }

    private java.util.Optional<DataQualityIssueView> synchronizationIssue(
            StoreDataStatusView dataStatus
    ) {
        return switch (dataStatus.status()) {
            case CURRENT -> java.util.Optional.empty();
            case SYNCING -> java.util.Optional.of(derivedIssue(
                    "SYNC_IN_PROGRESS",
                    DataQualitySeverity.INFO,
                    "Data synchronization is in progress",
                    dataStatus.checkedAt(),
                    DataQualityRecommendedAction.WAIT_FOR_SYNC
            ));
            case NOT_SYNCED -> java.util.Optional.of(derivedIssue(
                    "DATA_NOT_SYNCED",
                    DataQualitySeverity.ERROR,
                    "Sales and returns have not been synchronized",
                    dataStatus.checkedAt(),
                    DataQualityRecommendedAction.RUN_SYNC
            ));
            case STALE -> java.util.Optional.of(derivedIssue(
                    "DATA_STALE",
                    DataQualitySeverity.WARNING,
                    "Sales or returns are behind the expected date",
                    dataStatus.checkedAt(),
                    DataQualityRecommendedAction.RUN_SYNC
            ));
            case ERROR -> java.util.Optional.of(derivedIssue(
                    "SYNC_FAILED",
                    DataQualitySeverity.ERROR,
                    "Latest synchronization failed",
                    dataStatus.lastErrorAt() == null
                            ? dataStatus.checkedAt() : dataStatus.lastErrorAt(),
                    DataQualityRecommendedAction.RUN_SYNC
            ));
        };
    }

    private DataQualityIssueView derivedIssue(
            String code,
            DataQualitySeverity severity,
            String message,
            Instant detectedAt,
            DataQualityRecommendedAction action
    ) {
        return new DataQualityIssueView(
                "SYNCHRONIZATION:" + code,
                DataQualitySource.SYNCHRONIZATION,
                code,
                severity,
                "STORE",
                message,
                detectedAt,
                action
        );
    }

    private DataQualityIssueView issueView(DataQualityIssue issue) {
        return new DataQualityIssueView(
                "QUALITY_ISSUE:" + issue.getId(),
                source(issue.getEntityType()),
                issue.getIssueCode(),
                issue.getSeverity(),
                issue.getEntityType(),
                safeMessage(issue.getIssueCode()),
                issue.getDetectedAt(),
                DataQualityRecommendedAction.REVIEW_SOURCE_DOCUMENT
        );
    }

    private DataQualitySource source(String entityType) {
        return switch (entityType) {
            case "SALE" -> DataQualitySource.SALES;
            case "RETURN" -> DataQualitySource.RETURNS;
            default -> DataQualitySource.DATA;
        };
    }

    private String safeMessage(String issueCode) {
        return switch (issueCode) {
            case "SALE_ITEM_NET_MISMATCH" ->
                    "Sale total does not match its active items";
            case "SALE_ITEM_COST_MISMATCH" ->
                    "Sale cost does not match its active items";
            case "SALE_PAYMENT_MISMATCH" ->
                    "Sale payments do not match the document total";
            case "RETURN_PAYMENT_MISMATCH" ->
                    "Return payments do not match returned items";
            case "RETURN_CASH_TRANSACTION_MISMATCH" ->
                    "Return cash operations do not match document payments";
            default -> "Data consistency issue requires review";
        };
    }

    private DataQualityHealthStatus health(int errors, int warnings) {
        if (errors > 0) {
            return DataQualityHealthStatus.ERROR;
        }
        return warnings > 0
                ? DataQualityHealthStatus.WARNING
                : DataQualityHealthStatus.OK;
    }

    private int countSeverity(
            List<DataQualityIssueView> issues,
            DataQualitySeverity severity
    ) {
        return (int) issues.stream().filter(issue -> issue.severity() == severity).count();
    }

    private int countStatus(
            List<StoreDataQualitySummaryView> summaries,
            DataQualityHealthStatus status
    ) {
        return (int) summaries.stream().filter(summary -> summary.status() == status).count();
    }

    private static int severityPriority(DataQualitySeverity severity) {
        return switch (severity) {
            case ERROR -> 0;
            case WARNING -> 1;
            case INFO -> 2;
        };
    }
}
