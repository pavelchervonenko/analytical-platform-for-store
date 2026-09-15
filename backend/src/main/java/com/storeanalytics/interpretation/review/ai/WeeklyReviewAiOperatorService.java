package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.common.exception.PreconditionRequiredException;
import com.storeanalytics.interpretation.generation.LlmProviderException;
import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewSnapshotStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeeklyReviewAiOperatorService {

    private final WeeklyReviewAiGenerationProperties properties;
    private final WeeklyReviewSnapshotStore snapshotStore;
    private final WeeklyReviewAiJobStore jobStore;
    private final WeeklyReviewAiOperatorSupport support;
    private final Clock clock;

    public WeeklyReviewAiOperatorService(
            WeeklyReviewAiGenerationProperties properties,
            WeeklyReviewSnapshotStore snapshotStore,
            WeeklyReviewAiJobStore jobStore,
            WeeklyReviewAiOperatorSupport support,
            Clock clock
    ) {
        this.properties = properties;
        this.snapshotStore = snapshotStore;
        this.jobStore = jobStore;
        this.support = support;
        this.clock = clock;
    }

    public WeeklyReviewAiJobView findJob(UUID jobId) {
        return jobStore.findById(jobId)
                .map(WeeklyReviewAiJobView::from)
                .orElseThrow(WeeklyReviewAiJobNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public WeeklyReviewAiPreflightView preflight(UUID snapshotId) {
        PersistedWeeklyReviewSnapshot snapshot = eligibleSnapshot(snapshotId);
        Instant now = clock.instant();
        PreparedWeeklyReviewAiRequest prepared;
        LlmProviderPreflight estimate;
        BigDecimal actualCostToday = jobStore.actualCostSince(
                startOfUtcDay(now)
        );
        try {
            if (!"YANDEX".equals(properties.providerCode())) {
                throw new IllegalStateException(
                        "Weekly review AI provider is unsupported"
                );
            }
            prepared = support.requestFactory().prepare(
                    new WeeklyReviewAiProviderRequestCommand(
                            preflightJobId(snapshot.id()),
                            snapshot,
                            properties.providerCode(),
                            support.yandexProperties().getModelUri(),
                            properties.temperature(),
                            properties.maxOutputTokens(),
                            now,
                            properties.providerCallTimeout(),
                            now.plus(properties.jobDeadline()),
                            java.util.List.of()
                    )
            );
            estimate = support.requestPreflight().evaluate(prepared.request());
            support.budgetGuard().validate(
                    prepared.request(), estimate, actualCostToday
            );
        } catch (WeeklyReviewAiBudgetException
                 | LlmProviderException
                 | IllegalArgumentException
                 | IllegalStateException failure) {
            throw new WeeklyReviewAiPreflightRejectedException();
        }

        Optional<WeeklyReviewAiJob> existingJob = jobStore.findBySnapshot(
                snapshot.id()
        );
        Optional<PersistedWeeklyReviewAiEnrichment> existingEnrichment =
                support.enrichmentStore().findActive(snapshot.id());
        return view(
                snapshot,
                prepared,
                estimate,
                actualCostToday,
                existingJob,
                existingEnrichment
        );
    }

    public WeeklyReviewAiJobView generate(
            UUID snapshotId,
            WeeklyReviewAiGenerationApproval approval
    ) {
        if (!properties.enabled()) {
            throw new WeeklyReviewAiDisabledException();
        }
        if (!properties.workerEnabled()) {
            throw new WeeklyReviewAiWorkerDisabledException();
        }
        if (approval == null) {
            throw new PreconditionRequiredException(
                    "Exact weekly review AI approval is required"
            );
        }
        WeeklyReviewAiPreflightView checked = preflight(snapshotId);
        validateApproval(checked, approval);
        WeeklyReviewAiJob job = jobStore.enqueueApproved(
                snapshotId,
                properties.providerCode(),
                support.yandexProperties().getModelUri(),
                approval.approvedMaximumProviderCalls(),
                clock.instant(),
                properties.jobDeadline()
        );
        return WeeklyReviewAiJobView.from(job);
    }

    private PersistedWeeklyReviewSnapshot eligibleSnapshot(UUID snapshotId) {
        PersistedWeeklyReviewSnapshot snapshot = snapshotStore
                .findById(snapshotId)
                .orElseThrow(WeeklyReviewAiSnapshotNotFoundException::new);
        ReportState state = snapshot.response().reportState();
        if (state != ReportState.READY && state != ReportState.PARTIAL) {
            throw new WeeklyReviewAiSnapshotNotEligibleException();
        }
        return snapshot;
    }

    private WeeklyReviewAiPreflightView view(
            PersistedWeeklyReviewSnapshot snapshot,
            PreparedWeeklyReviewAiRequest prepared,
            LlmProviderPreflight estimate,
            BigDecimal actualCostToday,
            Optional<WeeklyReviewAiJob> existingJob,
            Optional<PersistedWeeklyReviewAiEnrichment> existingEnrichment
    ) {
        var period = snapshot.response().period();
        var job = existingJob.orElse(null);
        var enrichment = existingEnrichment.orElse(null);
        return new WeeklyReviewAiPreflightView(
                new WeeklyReviewAiPreflightView.WeeklyReviewAiPreflightSnapshot(
                        snapshot.id(),
                        snapshot.storeId(),
                        snapshot.revision(),
                        period.current().start(),
                        period.current().end(),
                        period.timezone(),
                        snapshot.response().reportState().name(),
                        snapshot.contentHash()
                ),
                new WeeklyReviewAiPreflightView.WeeklyReviewAiPreflightContract(
                        WeeklyReviewAiContract.PROMPT_VERSION,
                        WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                        WeeklyReviewAiContract.SELECTION_SCHEMA_VERSION,
                        WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION
                ),
                new WeeklyReviewAiPreflightView.WeeklyReviewAiPrivacySummary(
                        "PASS_STORE_ONLY_SCHEMA", "STORE_ONLY", false, false
                ),
                requestView(prepared, estimate),
                new WeeklyReviewAiPreflightView.WeeklyReviewAiPreflightBudget(
                        estimate.estimatedMaximumCost(),
                        properties.maxProviderCalls(),
                        1,
                        estimate.estimatedMaximumCost().multiply(
                                BigDecimal.valueOf(
                                        properties.maxProviderCalls()
                                )
                        ),
                        estimate.costCurrency(),
                        properties.maxEstimatedCostRub(),
                        actualCostToday,
                        properties.dailyCostLimitRub()
                ),
                new WeeklyReviewAiPreflightView.WeeklyReviewAiExistingState(
                        job == null ? null : job.id(),
                        job == null ? "NONE" : job.status().name(),
                        enrichment == null ? null : enrichment.id(),
                        enrichment == null ? null : enrichment.inputHash(),
                        enrichment == null ? null : enrichment.contentHash()
                ),
                existingJob.isEmpty() && existingEnrichment.isEmpty(),
                properties.enabled(),
                properties.workerEnabled()
        );
    }

    private WeeklyReviewAiPreflightView.WeeklyReviewAiPreflightRequest requestView(
            PreparedWeeklyReviewAiRequest prepared,
            LlmProviderPreflight estimate
    ) {
        WeeklyReviewAiInput input = prepared.input();
        return new WeeklyReviewAiPreflightView.WeeklyReviewAiPreflightRequest(
                properties.providerCode(),
                modelVersion(support.yandexProperties().getModelUri()),
                "WORKER_ONLY",
                prepared.inputHash(),
                prepared.requestHash(),
                estimate.estimatedInputTokens(),
                properties.maxOutputTokens(),
                estimate.contextWindowTokens(),
                input.factors().size(),
                input.actions().size(),
                input.evidence().size()
        );
    }

    private void validateApproval(
            WeeklyReviewAiPreflightView checked,
            WeeklyReviewAiGenerationApproval approval
    ) {
        if (!checked.approvalEligible()
                || !checked.snapshot().contentHash().equals(
                        approval.snapshotContentHash()
                )
                || !checked.request().inputHash().equals(approval.inputHash())
                || !checked.request().requestHash().equals(
                        approval.requestHash()
                )
                || !checked.budget().costCurrency().equals(
                        approval.costCurrency()
                )
                || approval.approvedMaximumProviderCalls()
                > checked.budget().maximumAllowedProviderCalls()) {
            throw new PreconditionFailedException(
                    "Weekly review AI approval does not match preflight"
            );
        }
        BigDecimal exactMaximum = checked.budget()
                .estimatedMaximumCostPerCall()
                .multiply(BigDecimal.valueOf(
                        approval.approvedMaximumProviderCalls()
                ));
        if (approval.approvedMaximumTotalCost().compareTo(exactMaximum) != 0) {
            throw new PreconditionFailedException(
                    "Weekly review AI approved cost does not match preflight"
            );
        }
    }

    private UUID preflightJobId(UUID snapshotId) {
        return UUID.nameUUIDFromBytes(
                ("weekly-review-ai-preflight:" + snapshotId)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private String modelVersion(String modelUri) {
        int separator = modelUri.lastIndexOf('/');
        String version = separator < 0 ? "" : modelUri.substring(separator + 1);
        if (!version.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new WeeklyReviewAiPreflightRejectedException();
        }
        return version;
    }

    private Instant startOfUtcDay(Instant value) {
        return value.atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
