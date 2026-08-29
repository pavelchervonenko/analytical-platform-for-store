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
                    + "|обуслов(?:ил|ила|ило|лен|лена|лено)"
                    + "|способств\\p{L}*|влия\\p{L}*|сказыва\\p{L}*)"
    );
    private static final Pattern FORBIDDEN_HORIZON = Pattern.compile(
            "(?iu)(?:план(?:а|у|ом|е|ы)?|прогноз(?:а|у|ом|е|ы)?"
                    + "|месяц(?:а|у|ем|е|ы|ев)?"
                    + "|текущ(?:ая|ей|ую) недел(?:я|и|ю))"
    );
    private static final Pattern UNSUPPORTED_TREND = Pattern.compile(
            "(?iu)(?:тренд|тенденц)\\p{L}*"
    );
    private static final Pattern UNAPPROVED_ADVICE = Pattern.compile(
            "(?iu)(?<!\\p{L})(?:важно|нужно|следует|стоит|необходимо"
                    + "|рекомендуется|требуется|сохранить|закрепить"
                    + "|проверить|разобрать|сопоставить|сравнить)(?!\\p{L})"
    );
    private static final Pattern FUTURE_WEEK_REFERENCE = Pattern.compile(
            "(?iu)следующ\\p{L}*\\s+(?:полной\\s+)?недел\\p{L}*"
    );
    private static final Pattern UNAPPROVED_PERIOD_QUALIFIER = Pattern.compile(
            "(?iu)предыдущ\\p{L}*\\s+полной\\s+недел\\p{L}*"
    );
    private static final List<MetricMention> METRIC_MENTIONS = List.of(
            new MetricMention(
                    "NET_REVENUE",
                    Pattern.compile(
                            "(?iu)(?:чист\\p{L}*|общ\\p{L}*)"
                                    + "\\s+выручк\\p{L}*"
                    ),
                    Pattern.compile("^STORE\\.NET_REVENUE\\z")
            ),
            metricMention(
                    "REVENUE", "выручк",
                    "^(?:STORE\\.(?:NET_REVENUE|SALES_REVENUE)"
                            + "|STORE\\.STRUCTURE\\.[A-Z0-9_]+\\.REVENUE)\\z"
            ),
            metricMention(
                    "GROSS_PROFIT", "прибыл",
                    "^STORE\\.GROSS_PROFIT\\z"
            ),
            metricMention(
                    "MARGIN", "марж", "^STORE\\.MARGIN_PERCENT\\z"
            ),
            metricMention(
                    "RETURN", "возврат",
                    "^STORE\\.RETURN_(?:REVENUE|DOCUMENT_COUNT)\\z"
            ),
            metricMention(
                    "ACCESSORY", "аксессуар",
                    "^STORE\\.(?:ATTACH\\.)?[A-Z0-9_]*ACCESSOR"
                            + "(?:Y|IES)[A-Z0-9_.]*\\z"
            ),
            metricMention(
                    "SERVICE", "услуг",
                    "^STORE\\.(?:ATTACH\\.)?[A-Z0-9_]*SERVICE"
                            + "[A-Z0-9_.]*\\z"
            )
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

    private static final Pattern POSITIVE_FACTOR_SIGNAL = Pattern.compile(
            "(?iu)(?:положительн\\p{L}*\\s+(?:сигнал|динамик\\p{L}*)|улучшени\\p{L}*)"
    );
    private static final Pattern NEGATIVE_FACTOR_SIGNAL = Pattern.compile(
            "(?iu)(?:зон\\p{L}*\\s+внимани\\p{L}*|риск\\p{L}*)"
    );
    private static final Pattern DESIRED_OUTCOME_ACTION = Pattern.compile(
            "(?iu)(?:(?:восстанов|повыс|увелич|сниз|улучш)\\p{L}*|вернуть\\b)"
    );
    private static final Pattern WORD = Pattern.compile(
            "(?U)[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*"
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
        validateManagement(source, content, violations);
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
            if (source != null && !source.title().equals(actual.title())) {
                add(violations, "ACTION_TITLE_CHANGED",
                        "$.actionWordings[" + index + "].title",
                        actual.actionId());
            }
            if (source != null && !source.check().equals(actual.check())) {
                add(violations, "ACTION_CHECK_MISMATCH",
                        "$.actionWordings[" + index + "].check",
                        actual.actionId());
            }
        }
    }

    private void validateManagement(
            WeeklyReviewAiInput input,
            WeeklyReviewAiContent content,
            List<LlmValidationViolation> violations
    ) {
        validateSummaryNarrative(input, content, violations);
        Map<String, WeeklyReviewAiInput.FactorSource> factors = new HashMap<>();
        input.factors().forEach(value -> factors.put(value.factorId(), value));
        for (int index = 0; index < content.factorExplanations().size(); index++) {
            WeeklyReviewAiContent.FactorExplanation actual =
                    content.factorExplanations().get(index);
            WeeklyReviewAiInput.FactorSource source = factors.get(
                    actual.factorId()
            );
            if (source == null) {
                continue;
            }
            String path = "$.factorExplanations[" + index + "].text";
            if (normalizeNarrative(actual.text()).equals(
                    normalizeNarrative(source.detail()))) {
                add(violations, "SOURCE_NARRATIVE_RESTATED",
                        path, actual.factorId());
            }
            String managementMeaning = source.managementMeaning();
            String meaningWithoutPeriod = managementMeaning.endsWith(".")
                    ? managementMeaning.substring(
                            0, managementMeaning.length() - 1
                    ) : managementMeaning;
            String expectedNarrative = meaningWithoutPeriod
                    + ("POSITIVE".equals(source.effect())
                    ? " — это положительный сигнал."
                    : " — это зона внимания.");
            boolean exactNarrative = actual.text().equals(expectedNarrative);
            if (!exactNarrative) {
                add(violations, "MANAGEMENT_MEANING_MISSING",
                        path, actual.factorId());
                add(violations, "FACTOR_EFFECT_MISSING",
                        path, actual.factorId());
            }
            boolean positiveSignal = POSITIVE_FACTOR_SIGNAL
                    .matcher(actual.text()).find();
            boolean negativeSignal = NEGATIVE_FACTOR_SIGNAL
                    .matcher(actual.text()).find();
            boolean contradiction = "NEGATIVE".equals(source.effect())
                    && positiveSignal
                    || "POSITIVE".equals(source.effect())
                    && negativeSignal;
            if (contradiction) {
                add(violations, "FACTOR_EFFECT_CONTRADICTION",
                        path, actual.factorId());
            }
        }
        Map<String, WeeklyReviewAiInput.ActionSource> actions = new HashMap<>();
        input.actions().forEach(value -> actions.put(value.actionId(), value));
        for (int index = 0; index < content.actionWordings().size(); index++) {
            WeeklyReviewAiContent.ActionWording actual =
                    content.actionWordings().get(index);
            if (!actions.containsKey(actual.actionId())) {
                continue;
            }
            String path = "$.actionWordings[" + index + "].title";
            Matcher words = WORD.matcher(actual.title());
            int wordCount = 0;
            while (words.find()) {
                wordCount++;
            }
            if (wordCount < 2 || wordCount > 8) {
                add(violations, "ACTION_TITLE_WORD_COUNT",
                        path, actual.actionId());
            }
            if (DESIRED_OUTCOME_ACTION.matcher(actual.title()).lookingAt()) {
                add(violations, "DESIRED_OUTCOME_ACTION",
                        path, actual.actionId());
            }
        }
    }

    private void validateSummaryNarrative(
            WeeklyReviewAiInput input,
            WeeklyReviewAiContent content,
            List<LlmValidationViolation> violations
    ) {
        if (!input.summary().allowedNarratives().contains(
                content.summary().text()
        )) {
            add(violations, "SUMMARY_NARRATIVE_CHANGED",
                    "$.summary.text", null);
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
                null,
                input.summary().evidenceRefs()
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
                        value.factorId(),
                        source.evidenceRefs()
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
                        value.actionId(),
                        source.evidenceRefs()
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
            validateMetricMentions(narrative, violations);
            if (!narrative.causalLanguageAllowed()
                    && CAUSAL_LANGUAGE.matcher(narrative.text()).find()) {
                add(violations, "UNAPPROVED_CAUSALITY",
                        narrative.path(), narrative.reference());
            }
            if (FORBIDDEN_HORIZON.matcher(narrative.text()).find()) {
                add(violations, "FORBIDDEN_HORIZON",
                        narrative.path(), narrative.reference());
            }
            if (UNSUPPORTED_TREND.matcher(narrative.text()).find()) {
                add(violations, "UNSUPPORTED_TREND",
                        narrative.path(), narrative.reference());
            }
            boolean actionTitle = narrative.path().startsWith(
                    "$.actionWordings"
            );
            if (!actionTitle
                    && UNAPPROVED_ADVICE.matcher(narrative.text()).find()) {
                add(violations, "UNAPPROVED_ADVICE",
                        narrative.path(), narrative.reference());
            }
            if (!actionTitle
                    && FUTURE_WEEK_REFERENCE.matcher(narrative.text()).find()) {
                add(violations, "FUTURE_WEEK_REFERENCE",
                        narrative.path(), narrative.reference());
            }
            if (!actionTitle
                    && UNAPPROVED_PERIOD_QUALIFIER.matcher(
                            narrative.text()
                    ).find()) {
                add(violations, "UNAPPROVED_PERIOD_QUALIFIER",
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

    private void validateMetricMentions(
            Narrative narrative,
            List<LlmValidationViolation> violations
    ) {
        for (MetricMention mention : METRIC_MENTIONS) {
            boolean mentioned = mention.pattern().matcher(narrative.text()).find();
            boolean allowed = narrative.allowedEvidenceRefs().stream()
                    .anyMatch(reference -> mention.evidencePattern()
                            .matcher(reference).matches());
            if (mentioned && !allowed) {
                add(violations, "UNAPPROVED_METRIC",
                        narrative.path(), mention.referenceToken());
            }
        }
    }

    private static MetricMention metricMention(
            String referenceToken,
            String wordStem,
            String evidencePattern
    ) {
        return new MetricMention(
                referenceToken,
                Pattern.compile("(?iu)" + wordStem + "\\p{L}*"),
                Pattern.compile(evidencePattern)
        );
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
            String normalized = normalizeNarrative(narrative.text());
            if (!seen.add(normalized)) {
                add(violations, "DUPLICATE_NARRATIVE",
                        narrative.path(), narrative.reference());
            }
        }
    }

    private String normalizeNarrative(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{Pd}\\s]+", " ")
                .trim();
    }

    private void add(
            List<LlmValidationViolation> target,
            String code,
            String path,
            String reference
    ) {
        target.add(new LlmValidationViolation(code, path, reference));
    }

    private record MetricMention(
            String referenceToken,
            Pattern pattern,
            Pattern evidencePattern
    ) {
    }

    private record Narrative(
            String path,
            String text,
            List<String> allowedNumericLiterals,
            boolean causalLanguageAllowed,
            String reference,
            List<String> allowedEvidenceRefs
    ) {
    }
}
