package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import com.storeanalytics.interpretation.validation.LlmValidationViolation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Enforces backend ownership after the response has passed schema validation. */
@Component
public final class WeeklyReviewAiSemanticValidator {

    private static final int MAX_VIOLATIONS = 100;
    private static final Pattern NUMERIC_LITERAL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])[+-]?\\d+(?:[\\s\\u00A0]\\d{3})*(?:[.,]\\d+)?"
    );
    private static final Pattern CAUSAL_LANGUAGE = Pattern.compile(
            "(?iu)(?:из-за|поэтому|прив(?:ел|ела|ело|ели)"
                    + "|в результате|за сч[её]т|благодаря"
                    + "|обуслов(?:ил|ила|ило|лен|лена|лено))"
    );
    private static final Pattern FORBIDDEN_HORIZON = Pattern.compile(
            "(?iu)(?:план(?:а|у|ом|е|ы)?|прогноз(?:а|у|ом|е|ы)?"
                    + "|месяц(?:а|у|ем|е|ы|ев)?"
                    + "|текущ(?:ая|ей|ую) недел(?:я|и|ю))"
    );
    private static final Pattern FORBIDDEN_PERSONNEL_JUDGMENT = Pattern.compile(
            "(?iu)(?:увол|штраф|зарплат|личност|характер|мотивац)"
    );
    private static final Pattern GENERIC_NARRATIVE = Pattern.compile(
            "(?iu)(?:определ[её]нное количество клиентов|пров[её]л(?:а)? ряд продаж)"
    );
    private static final Pattern INTERNAL_IDENTIFIER = Pattern.compile(
            "(?iu)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}"
                    + "-[0-9a-f]{12}\\b"
    );

    private final WeeklyReviewAiStructuralValidator structuralValidator;

    public WeeklyReviewAiSemanticValidator(
            WeeklyReviewAiStructuralValidator structuralValidator
    ) {
        this.structuralValidator = requireNonNull(
                structuralValidator, "structuralValidator"
        );
    }

    public WeeklyReviewAiValidationResult validate(
            WeeklyReviewAiInput input,
            String responseBody
    ) {
        WeeklyReviewAiInput source = requireNonNull(input, "input");
        WeeklyReviewAiValidationResult structural =
                structuralValidator.validate(responseBody);
        if (structural.outcome() != LlmValidationOutcome.VALID) {
            return structural;
        }
        WeeklyReviewAiContent content = structural.content();
        List<LlmValidationViolation> violations = new ArrayList<>();
        validateObjectSets(source, content, violations);
        validateEvidence(source, content, violations);
        validateActionChecks(source, content, violations);
        validateNarratives(source, content, violations);
        if (!violations.isEmpty()) {
            return WeeklyReviewAiValidationResult.invalid(
                    LlmValidationOutcome.SEMANTIC_INVALID,
                    violations.stream().limit(MAX_VIOLATIONS).toList()
            );
        }
        return structural.markSemanticallyValidated();
    }

    private void validateObjectSets(
            WeeklyReviewAiInput input,
            WeeklyReviewAiContent content,
            List<LlmValidationViolation> violations
    ) {
        List<String> expectedFactors = input.factors().stream()
                .map(WeeklyReviewAiInput.FactorSource::factorId)
                .toList();
        List<String> actualFactors = content.factorExplanations().stream()
                .map(WeeklyReviewAiContent.FactorExplanation::factorId)
                .toList();
        if (!expectedFactors.equals(actualFactors)) {
            add(violations, "FACTOR_SET_MISMATCH", "$.factorExplanations", null);
        }
        List<String> expectedActions = input.actions().stream()
                .map(WeeklyReviewAiInput.ActionSource::actionId)
                .toList();
        List<String> actualActions = content.actionWordings().stream()
                .map(WeeklyReviewAiContent.ActionWording::actionId)
                .toList();
        if (!expectedActions.equals(actualActions)) {
            add(violations, "ACTION_SET_MISMATCH", "$.actionWordings", null);
        }
    }

    private void validateEvidence(
            WeeklyReviewAiInput input,
            WeeklyReviewAiContent content,
            List<LlmValidationViolation> violations
    ) {
        if (!input.summary().evidenceRefs().equals(
                content.summary().evidenceRefs()
        )) {
            add(violations, "SUMMARY_EVIDENCE_MISMATCH",
                    "$.summary.evidenceRefs", null);
        }
        Map<String, WeeklyReviewAiInput.FactorSource> expected = new HashMap<>();
        input.factors().forEach(value -> expected.put(value.factorId(), value));
        for (int index = 0; index < content.factorExplanations().size(); index++) {
            WeeklyReviewAiContent.FactorExplanation actual =
                    content.factorExplanations().get(index);
            WeeklyReviewAiInput.FactorSource source = expected.get(actual.factorId());
            if (source != null && !source.evidenceRefs().equals(actual.evidenceRefs())) {
                add(violations, "FACTOR_EVIDENCE_MISMATCH",
                        "$.factorExplanations[" + index + "].evidenceRefs",
                        actual.factorId());
            }
        }
    }

    private void validateActionChecks(
            WeeklyReviewAiInput input,
            WeeklyReviewAiContent content,
            List<LlmValidationViolation> violations
    ) {
        Map<String, WeeklyReviewAiInput.ActionSource> expected = new HashMap<>();
        input.actions().forEach(value -> expected.put(value.actionId(), value));
        for (int index = 0; index < content.actionWordings().size(); index++) {
            WeeklyReviewAiContent.ActionWording actual =
                    content.actionWordings().get(index);
            WeeklyReviewAiInput.ActionSource source = expected.get(actual.actionId());
            if (source != null && !source.check().equals(actual.check())) {
                add(violations, "ACTION_CHECK_MISMATCH",
                        "$.actionWordings[" + index + "].check",
                        actual.actionId());
            }
        }
    }

    private void validateNarratives(
            WeeklyReviewAiInput input,
            WeeklyReviewAiContent content,
            List<LlmValidationViolation> violations
    ) {
        List<Narrative> narratives = new ArrayList<>();
        narratives.add(new Narrative(
                "$.summary.text",
                content.summary().text(),
                input.summary().allowedNumericLiterals(),
                false,
                null
        ));
        Map<String, WeeklyReviewAiInput.FactorSource> factors = new HashMap<>();
        input.factors().forEach(value -> factors.put(value.factorId(), value));
        appendFactors(content, factors, narratives);
        Map<String, WeeklyReviewAiInput.ActionSource> actions = new HashMap<>();
        input.actions().forEach(value -> actions.put(value.actionId(), value));
        appendActions(content, actions, narratives);
        validateNarrativeRules(narratives, violations);
        validateDuplicates(narratives, violations);
    }

    private void appendFactors(
            WeeklyReviewAiContent content,
            Map<String, WeeklyReviewAiInput.FactorSource> factors,
            List<Narrative> target
    ) {
        for (int index = 0; index < content.factorExplanations().size(); index++) {
            WeeklyReviewAiContent.FactorExplanation value =
                    content.factorExplanations().get(index);
            WeeklyReviewAiInput.FactorSource source = factors.get(value.factorId());
            if (source != null) {
                target.add(new Narrative(
                        "$.factorExplanations[" + index + "].text",
                        value.text(),
                        source.allowedNumericLiterals(),
                        source.causalLanguageAllowed(),
                        value.factorId()
                ));
            }
        }
    }

    private void appendActions(
            WeeklyReviewAiContent content,
            Map<String, WeeklyReviewAiInput.ActionSource> actions,
            List<Narrative> target
    ) {
        for (int index = 0; index < content.actionWordings().size(); index++) {
            WeeklyReviewAiContent.ActionWording value = content.actionWordings().get(index);
            WeeklyReviewAiInput.ActionSource source = actions.get(value.actionId());
            if (source != null) {
                target.add(new Narrative(
                        "$.actionWordings[" + index + "].title",
                        value.title(),
                        source.allowedNumericLiterals(),
                        false,
                        value.actionId()
                ));
            }
        }
    }

    private void validateNarrativeRules(
            List<Narrative> narratives,
            List<LlmValidationViolation> violations
    ) {
        for (Narrative narrative : narratives) {
            validateNumbers(narrative, violations);
            if (!narrative.causalLanguageAllowed()
                    && CAUSAL_LANGUAGE.matcher(narrative.text()).find()) {
                add(violations, "UNAPPROVED_CAUSALITY",
                        narrative.path(), narrative.reference());
            }
            if (FORBIDDEN_HORIZON.matcher(narrative.text()).find()) {
                add(violations, "FORBIDDEN_HORIZON",
                        narrative.path(), narrative.reference());
            }
            if (FORBIDDEN_PERSONNEL_JUDGMENT.matcher(narrative.text()).find()) {
                add(violations, "FORBIDDEN_PERSONNEL_JUDGMENT",
                        narrative.path(), narrative.reference());
            }
            if (GENERIC_NARRATIVE.matcher(narrative.text()).find()) {
                add(violations, "GENERIC_NARRATIVE",
                        narrative.path(), narrative.reference());
            }
            if (INTERNAL_IDENTIFIER.matcher(narrative.text()).find()) {
                add(violations, "FORBIDDEN_IDENTIFIER",
                        narrative.path(), narrative.reference());
            }
        }
    }

    private void validateNumbers(
            Narrative narrative,
            List<LlmValidationViolation> violations
    ) {
        Set<String> allowed = new HashSet<>();
        narrative.allowedNumericLiterals().stream()
                .map(this::canonicalNumericLiteral)
                .forEach(allowed::add);
        Matcher matcher = NUMERIC_LITERAL.matcher(narrative.text());
        while (matcher.find()) {
            String literal = matcher.group();
            if (!allowed.contains(canonicalNumericLiteral(literal))) {
                add(violations, "UNAPPROVED_NUMBER",
                        narrative.path(), literal);
            }
        }
    }

    private String canonicalNumericLiteral(String literal) {
        String normalized = literal
                .replace(" ", "")
                .replace("\u00A0", "")
                .replace(",", ".");
        try {
            BigDecimal value = new BigDecimal(normalized);
            return value.signum() == 0
                    ? "0"
                    : value.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException exception) {
            return normalized;
        }
    }

    private void validateDuplicates(
            List<Narrative> narratives,
            List<LlmValidationViolation> violations
    ) {
        Set<String> seen = new LinkedHashSet<>();
        for (Narrative narrative : narratives) {
            String normalized = narrative.text()
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[\\p{Punct}\\s]+", " ")
                    .trim();
            if (!seen.add(normalized)) {
                add(violations, "DUPLICATE_NARRATIVE",
                        narrative.path(), narrative.reference());
            }
        }
    }

    private void add(
            List<LlmValidationViolation> target,
            String code,
            String path,
            String reference
    ) {
        target.add(new LlmValidationViolation(code, path, reference));
    }

    private record Narrative(
            String path,
            String text,
            List<String> allowedNumericLiterals,
            boolean causalLanguageAllowed,
            String reference
    ) {
    }
}
