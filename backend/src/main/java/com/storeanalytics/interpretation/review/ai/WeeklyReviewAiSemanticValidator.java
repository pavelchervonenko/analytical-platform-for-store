package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import com.storeanalytics.interpretation.validation.LlmValidationViolation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Validates provider selectors, renders them, and validates final schema4. */
@Component
public final class WeeklyReviewAiSemanticValidator {

    private static final int MAX_VIOLATIONS = 100;
    private static final Pattern SELECTOR_TOKEN = Pattern.compile(
            "(?:SUMMARY|FACTOR)_[A-Z_]+"
    );

    private final WeeklyReviewAiSelectionStructuralValidator selectionValidator;
    private final WeeklyReviewAiStructuralValidator contentValidator;
    private final WeeklyReviewAiRendererV25 renderer;
    private final WeeklyReviewAiContentCodec codec;

    @Autowired
    public WeeklyReviewAiSemanticValidator(
            WeeklyReviewAiSelectionStructuralValidator selectionValidator,
            WeeklyReviewAiStructuralValidator contentValidator,
            WeeklyReviewAiRendererV25 renderer,
            WeeklyReviewAiContentCodec codec
    ) {
        this.selectionValidator = requireNonNull(
                selectionValidator, "selectionValidator"
        );
        this.contentValidator = requireNonNull(
                contentValidator, "contentValidator"
        );
        this.renderer = requireNonNull(renderer, "renderer");
        this.codec = requireNonNull(codec, "codec");
    }

    public WeeklyReviewAiSemanticValidator(
            WeeklyReviewAiStructuralValidator contentValidator
    ) {
        this(
                new WeeklyReviewAiSelectionStructuralValidator(),
                contentValidator,
                new WeeklyReviewAiRendererV25(),
                new WeeklyReviewAiContentCodec()
        );
    }

    public WeeklyReviewAiValidationResult validate(
            WeeklyReviewAiInput input,
            String responseBody
    ) {
        WeeklyReviewAiInput source = requireNonNull(input, "input");
        WeeklyReviewAiSelectionValidationResult structural =
                selectionValidator.validate(responseBody);
        if (structural.outcome() != LlmValidationOutcome.VALID) {
            return WeeklyReviewAiValidationResult.invalid(
                    structural.outcome(),
                    structural.violations()
            );
        }

        WeeklyReviewAiSelection selection = structural.selection();
        List<LlmValidationViolation> violations = new ArrayList<>();
        validateSelection(source, selection, violations);
        if (!violations.isEmpty()) {
            return invalid(violations);
        }

        WeeklyReviewAiContent rendered;
        try {
            rendered = renderer.render(source, selection);
        } catch (RuntimeException exception) {
            return invalid(List.of(new LlmValidationViolation(
                    "RENDERING_FAILED", "$", null
            )));
        }
        WeeklyReviewAiValidationResult finalStructure =
                contentValidator.validate(codec.canonical(rendered));
        if (finalStructure.outcome() != LlmValidationOutcome.VALID) {
            return finalStructure;
        }
        validateRendered(source, finalStructure.content(), violations);
        if (!violations.isEmpty()) {
            return invalid(violations);
        }
        return finalStructure.markSemanticallyValidated();
    }

    private void validateSelection(
            WeeklyReviewAiInput input,
            WeeklyReviewAiSelection selection,
            List<LlmValidationViolation> violations
    ) {
        List<String> expectedFactors = input.factors().stream()
                .map(WeeklyReviewAiInput.FactorSource::factorId)
                .toList();
        List<String> actualFactors = selection.factorSelections().stream()
                .map(WeeklyReviewAiSelection.FactorSelection::factorId)
                .toList();
        if (!expectedFactors.equals(actualFactors)) {
            add(violations, "FACTOR_SET_MISMATCH",
                    "$.factorSelections", null);
        }
        Map<String, WeeklyReviewAiInput.FactorSource> factors = new HashMap<>();
        input.factors().forEach(value -> factors.put(value.factorId(), value));
        for (int index = 0;
                index < selection.factorSelections().size();
                index++) {
            WeeklyReviewAiSelection.FactorSelection actual =
                    selection.factorSelections().get(index);
            WeeklyReviewAiInput.FactorSource source = factors.get(
                    actual.factorId()
            );
            if (source != null
                    && !source.allowedSelectors().contains(actual.selector())) {
                add(violations, "FACTOR_SELECTOR_NOT_ALLOWED",
                        "$.factorSelections[" + index + "].selector",
                        actual.factorId());
            }
        }
        validateSummarySelection(input, selection.summary(), factors, violations);
    }

    private void validateSummarySelection(
            WeeklyReviewAiInput input,
            WeeklyReviewAiSelection.SummarySelection selection,
            Map<String, WeeklyReviewAiInput.FactorSource> factors,
            List<LlmValidationViolation> violations
    ) {
        String selector = selection.selector();
        if (!input.summary().allowedSelectors().contains(selector)) {
            add(violations, "SUMMARY_SELECTOR_NOT_ALLOWED",
                    "$.summary.selector", null);
            return;
        }
        validateFocusKnown(
                input, selection.primaryFactorId(),
                "$.summary.primaryFactorId", violations
        );
        validateFocusKnown(
                input, selection.secondaryFactorId(),
                "$.summary.secondaryFactorId", violations
        );
        switch (selector) {
            case "SUMMARY_OUTCOME" -> requireFocus(
                    selection, null, null, factors, violations
            );
            case "SUMMARY_STRENGTH" -> requireFocus(
                    selection, "POSITIVE", null, factors, violations
            );
            case "SUMMARY_RISK" -> requireFocus(
                    selection, "NEGATIVE", null, factors, violations
            );
            case "SUMMARY_BALANCED" -> requireFocus(
                    selection, "POSITIVE", "NEGATIVE", factors, violations
            );
            default -> add(
                    violations,
                    "SUMMARY_SELECTOR_NOT_ALLOWED",
                    "$.summary.selector",
                    null
            );
        }
    }

    private void validateFocusKnown(
            WeeklyReviewAiInput input,
            String factorId,
            String path,
            List<LlmValidationViolation> violations
    ) {
        if (factorId != null
                && !input.summary().allowedFocusFactorIds().contains(factorId)) {
            add(violations, "SUMMARY_FOCUS_NOT_ALLOWED", path, factorId);
        }
    }

    private void requireFocus(
            WeeklyReviewAiSelection.SummarySelection selection,
            String primaryEffect,
            String secondaryEffect,
            Map<String, WeeklyReviewAiInput.FactorSource> factors,
            List<LlmValidationViolation> violations
    ) {
        validateFocusEffect(
                selection.primaryFactorId(),
                primaryEffect,
                "$.summary.primaryFactorId",
                factors,
                violations
        );
        validateFocusEffect(
                selection.secondaryFactorId(),
                secondaryEffect,
                "$.summary.secondaryFactorId",
                factors,
                violations
        );
    }

    private void validateFocusEffect(
            String factorId,
            String expectedEffect,
            String path,
            Map<String, WeeklyReviewAiInput.FactorSource> factors,
            List<LlmValidationViolation> violations
    ) {
        if (expectedEffect == null) {
            if (factorId != null) {
                add(violations, "SUMMARY_FOCUS_UNEXPECTED", path, factorId);
            }
            return;
        }
        WeeklyReviewAiInput.FactorSource factor = factors.get(factorId);
        if (factor == null || !expectedEffect.equals(factor.effect())) {
            add(violations, "SUMMARY_FOCUS_EFFECT_MISMATCH", path, factorId);
        }
    }

    private void validateRendered(
            WeeklyReviewAiInput input,
            WeeklyReviewAiContent content,
            List<LlmValidationViolation> violations
    ) {
        Set<String> evidence = new HashSet<>();
        input.evidence().forEach(value -> evidence.add(value.evidenceRef()));
        validateText(
                content.summary().text(),
                "$.summary.text",
                violations
        );
        validateKnownEvidence(
                content.summary().evidenceRefs(),
                evidence,
                "$.summary.evidenceRefs",
                violations
        );

        Map<String, WeeklyReviewAiInput.FactorSource> factors = new HashMap<>();
        input.factors().forEach(value -> factors.put(value.factorId(), value));
        for (int index = 0;
                index < content.factorExplanations().size();
                index++) {
            WeeklyReviewAiContent.FactorExplanation actual =
                    content.factorExplanations().get(index);
            WeeklyReviewAiInput.FactorSource source = factors.get(
                    actual.factorId()
            );
            String path = "$.factorExplanations[" + index + "]";
            if (source == null
                    || !source.evidenceRefs().equals(actual.evidenceRefs())) {
                add(violations, "RENDERED_FACTOR_MISMATCH",
                        path, actual.factorId());
            }
            validateText(actual.text(), path + ".text", violations);
        }

        Map<String, WeeklyReviewAiInput.ActionSource> actions = new HashMap<>();
        input.actions().forEach(value -> actions.put(value.actionId(), value));
        for (int index = 0; index < content.actionWordings().size(); index++) {
            WeeklyReviewAiContent.ActionWording actual =
                    content.actionWordings().get(index);
            WeeklyReviewAiInput.ActionSource source = actions.get(
                    actual.actionId()
            );
            if (source == null || !source.title().equals(actual.title())
                    || !source.check().equals(actual.check())) {
                add(violations, "RENDERED_ACTION_MISMATCH",
                        "$.actionWordings[" + index + "]",
                        actual.actionId());
            }
        }
    }

    private void validateKnownEvidence(
            List<String> references,
            Set<String> known,
            String path,
            List<LlmValidationViolation> violations
    ) {
        for (String reference : references) {
            if (!known.contains(reference)) {
                add(violations, "RENDERED_EVIDENCE_UNKNOWN",
                        path, reference);
            }
        }
    }

    private void validateText(
            String text,
            String path,
            List<LlmValidationViolation> violations
    ) {
        if (SELECTOR_TOKEN.matcher(text).find()) {
            add(violations, "SELECTOR_TOKEN_LEAK", path, null);
        }
    }

    private WeeklyReviewAiValidationResult invalid(
            List<LlmValidationViolation> violations
    ) {
        return WeeklyReviewAiValidationResult.invalid(
                LlmValidationOutcome.SEMANTIC_INVALID,
                violations.stream().limit(MAX_VIOLATIONS).toList()
        );
    }

    private void add(
            List<LlmValidationViolation> target,
            String code,
            String path,
            String reference
    ) {
        target.add(new LlmValidationViolation(code, path, reference));
    }
}
