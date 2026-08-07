package com.storeanalytics.interpretation.publication;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.generation.LlmAnalysisAttempt;
import com.storeanalytics.interpretation.generation.LlmAnalysisAttemptStatus;
import com.storeanalytics.interpretation.contract.CanonicalLlmJson;
import com.storeanalytics.interpretation.contract.LlmCanonicalJsonCodec;
import org.springframework.stereotype.Component;

@Component
public class WeeklyPublicationMaterialFactory {

    private final LlmCanonicalJsonCodec jsonCodec;

    public WeeklyPublicationMaterialFactory(LlmCanonicalJsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    public WeeklyPublicationMaterial create(LlmAnalysisAttempt attempt) {
        LlmAnalysisAttempt value = requireNonNull(attempt, "attempt");
        require(value.status() == LlmAnalysisAttemptStatus.SUCCEEDED,
                "publication requires a SUCCEEDED attempt");
        require(value.validatedResponseBody() != null,
                "validated response body is unavailable");
        require(value.finishedAt() != null, "validated attempt has no finishedAt");
        CanonicalLlmJson content = jsonCodec.canonicalize(
                value.validatedResponseBody()
        );
        require(content.contentHash().equals(value.validatedResponseHash()),
                "validated response hash does not match its body");
        return new WeeklyPublicationMaterial(
                content.canonicalJson(),
                content.contentHash(),
                value.finishedAt()
        );
    }
}
