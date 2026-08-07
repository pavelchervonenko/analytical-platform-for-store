package com.storeanalytics.interpretation.generation;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.config.LlmAnalysisPlannerProperties;
import com.storeanalytics.interpretation.config.LlmGenerationProperties;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class LlmAnalysisRequestFactory {

    public static final String PROVIDER_CODE = "YANDEX";
    public static final String PROVIDER_CONFIG_VERSION = "yandex-foundation-models-v1";
    public static final String ANALYSIS_POLICY_VERSION = "weekly-analysis-v1";
    public static final String BUDGET_POLICY_VERSION = "weekly-budget-v1";

    private final LlmGenerationProperties generationProperties;
    private final YandexLlmProperties yandexProperties;
    private final LlmAnalysisPlannerProperties plannerProperties;
    private final ObjectWriter canonicalWriter;

    public LlmAnalysisRequestFactory(
            LlmGenerationProperties generationProperties,
            YandexLlmProperties yandexProperties,
            LlmAnalysisPlannerProperties plannerProperties
    ) {
        this.generationProperties = generationProperties;
        this.yandexProperties = yandexProperties;
        this.plannerProperties = plannerProperties;
        this.canonicalWriter = JsonMapper.builder()
                .findAndAddModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build()
                .writer();
    }

    public LlmAnalysisJobRequest automatic(
            LlmAnalysisPlanningStore.SnapshotTarget target,
            Instant now
    ) {
        GenerationParameters parameters = new GenerationParameters(
                generationProperties.temperature(),
                generationProperties.maxOutputTokens(),
                generationProperties.maxProviderCalls()
        );
        String parametersJson = serialize(parameters);
        LlmAnalysisTriggerType triggerType = target.snapshotRevision() == 1
                ? LlmAnalysisTriggerType.INITIAL
                : LlmAnalysisTriggerType.SNAPSHOT_REVISION;
        HashMaterial hashMaterial = new HashMaterial(
                target.snapshotId(),
                target.factsHash(),
                PROVIDER_CODE,
                yandexProperties.getModelUri(),
                PROVIDER_CONFIG_VERSION,
                generationProperties.contentSchemaVersion(),
                generationProperties.promptVersion(),
                ANALYSIS_POLICY_VERSION,
                BUDGET_POLICY_VERSION,
                parameters
        );
        int retryLimit = generationProperties.maxProviderCalls() - 1;
        return new LlmAnalysisJobRequest(
                target.snapshotId(),
                1,
                triggerType,
                null,
                PROVIDER_CODE,
                yandexProperties.getModelUri(),
                PROVIDER_CONFIG_VERSION,
                generationProperties.contentSchemaVersion(),
                generationProperties.promptVersion(),
                ANALYSIS_POLICY_VERSION,
                BUDGET_POLICY_VERSION,
                parametersJson,
                hash(hashMaterial),
                retryLimit,
                Math.min(retryLimit, 1),
                now.plus(plannerProperties.jobDeadline())
        );
    }
    public LlmAnalysisJobRequest manual(
            LlmAnalysisPlanningStore.SnapshotTarget target,
            int generationRevision,
            UUID requestedBy,
            Instant now
    ) {
        if (generationRevision < 2) {
            throw new IllegalArgumentException(
                    "Manual generation revision must be at least 2"
            );
        }
        if (requestedBy == null) {
            throw new IllegalArgumentException("requestedBy must not be null");
        }
        LlmAnalysisJobRequest automatic = automatic(target, now);
        return new LlmAnalysisJobRequest(
                automatic.snapshotId(),
                generationRevision,
                LlmAnalysisTriggerType.MANUAL_REGENERATION,
                requestedBy,
                automatic.providerCode(),
                automatic.requestedModel(),
                automatic.providerConfigVersion(),
                automatic.contentSchemaVersion(),
                automatic.promptVersion(),
                automatic.analysisPolicyVersion(),
                automatic.budgetPolicyVersion(),
                automatic.generationParameters(),
                automatic.inputHash(),
                automatic.maxTransportRetries(),
                automatic.maxValidationRetries(),
                automatic.deadlineAt()
        );
    }


    private String serialize(Object value) {
        try {
            return canonicalWriter.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("LLM request metadata could not be encoded", exception);
        }
    }

    private String hash(HashMaterial material) {
        try {
            byte[] canonical = canonicalWriter.writeValueAsBytes(material);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical)
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("LLM input hash could not be created", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record GenerationParameters(
            java.math.BigDecimal temperature,
            int maxOutputTokens,
            int maxProviderCalls
    ) {
    }

    private record HashMaterial(
            java.util.UUID snapshotId,
            String factsHash,
            String providerCode,
            String requestedModel,
            String providerConfigVersion,
            int contentSchemaVersion,
            String promptVersion,
            String analysisPolicyVersion,
            String budgetPolicyVersion,
            GenerationParameters generationParameters
    ) {
    }
}
