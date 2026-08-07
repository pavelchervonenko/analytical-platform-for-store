package com.storeanalytics.interpretation.operations;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.common.idempotency.IdempotencyRequest;
import com.storeanalytics.common.idempotency.IdempotencyService;
import com.storeanalytics.interpretation.config.InterpretationFeatureProperties;
import com.storeanalytics.interpretation.generation.LlmAnalysisJob;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LlmOperationsService {

    private final LlmOperationsJobCommands jobCommands;
    private final IdempotencyService idempotencyService;
    private final AuditLogService auditLogService;
    private final InterpretationFeatureProperties features;

    public LlmOperationsService(
            LlmOperationsJobCommands jobCommands,
            IdempotencyService idempotencyService,
            AuditLogService auditLogService,
            InterpretationFeatureProperties features
    ) {
        this.jobCommands = jobCommands;
        this.idempotencyService = idempotencyService;
        this.auditLogService = auditLogService;
        this.features = features;
    }

    @Transactional
    public ManualLlmJobView regenerate(
            UUID snapshotId,
            UUID actorId,
            String idempotencyKey,
            ManualLlmActionRequest request
    ) {
        ValidatedAction action = validate(request);
        return idempotencyService.execute(
                actorId,
                idempotencyKey,
                new IdempotencyRequest(
                        "LLM_REGENERATION",
                        "llm-snapshot/" + snapshotId,
                        action
                ),
                ManualLlmJobView.class,
                () -> regenerateOnce(snapshotId, actorId, action)
        );
    }

    @Transactional
    public ManualLlmJobView cancel(
            UUID jobId,
            UUID actorId,
            String idempotencyKey,
            ManualLlmActionRequest request
    ) {
        ValidatedAction action = validate(request);
        return idempotencyService.execute(
                actorId,
                idempotencyKey,
                new IdempotencyRequest(
                        "LLM_JOB_CANCEL",
                        "llm-job/" + jobId,
                        action
                ),
                ManualLlmJobView.class,
                () -> cancelOnce(jobId, actorId, action)
        );
    }

    private ManualLlmJobView regenerateOnce(
            UUID snapshotId,
            UUID actorId,
            ValidatedAction action
    ) {
        if (!features.generationEnabled()) {
            throw new LlmOperationsConflictException("LLM generation is disabled");
        }
        LlmOperationsJobCommands.RegenerationResult commandResult =
                jobCommands.regenerate(snapshotId, actorId);
        LlmAnalysisJob job = commandResult.job();
        auditLogService.record(
                actorId,
                commandResult.storeId(),
                AuditAction.LLM_REGENERATION_REQUESTED,
                new AuditTarget(AuditEntityType.LLM_ANALYSIS_JOB, job.id()),
                action.reason(),
                null,
                Map.of(
                        "snapshotId", snapshotId,
                        "generationRevision", job.generationRevision(),
                        "status", job.status()
                )
        );
        return view(job);
    }

    private ManualLlmJobView cancelOnce(
            UUID jobId,
            UUID actorId,
            ValidatedAction action
    ) {
        LlmOperationsJobCommands.CancellationResult commandResult =
                jobCommands.cancel(jobId);
        LlmAnalysisJob before = commandResult.before();
        LlmAnalysisJob result = commandResult.after();
        auditLogService.record(
                actorId,
                commandResult.storeId(),
                AuditAction.LLM_JOB_CANCELLATION_REQUESTED,
                new AuditTarget(AuditEntityType.LLM_ANALYSIS_JOB, jobId),
                action.reason(),
                Map.of(
                        "status", before.status(),
                        "cancelRequested", before.cancelRequested()
                ),
                Map.of(
                        "status", result.status(),
                        "cancelRequested", result.cancelRequested()
                )
        );
        return view(result);
    }

    private ValidatedAction validate(ManualLlmActionRequest request) {
        String reason = request == null || request.reason() == null
                ? "" : request.reason().trim();
        if (reason.length() < 10 || reason.length() > 500) {
            throw new InvalidRequestException("reason must contain 10 to 500 characters");
        }
        return new ValidatedAction(reason);
    }

    private ManualLlmJobView view(LlmAnalysisJob job) {
        return new ManualLlmJobView(
                job.id(),
                job.snapshotId(),
                job.generationRevision(),
                job.status().name(),
                job.phase().name(),
                job.cancelRequested(),
                job.updatedAt()
        );
    }

    private record ValidatedAction(String reason) {
    }
}
