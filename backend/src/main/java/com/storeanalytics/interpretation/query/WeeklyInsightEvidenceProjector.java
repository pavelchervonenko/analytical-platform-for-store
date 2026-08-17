package com.storeanalytics.interpretation.query;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Comparison;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EmployeeFacts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import com.storeanalytics.interpretation.snapshot.SnapshotEmployeeMembership;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Resolves cited internal facts into bounded public evidence. Internal evidence and employee
 * references are replaced with response-local opaque codes before content leaves the backend.
 */
@Component
public final class WeeklyInsightEvidenceProjector {

    private static final int MAX_PUBLIC_EVIDENCE = 200;
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru-RU");
    private static final Map<String, String> METRIC_LABELS = Map.ofEntries(
            Map.entry("NET_REVENUE", "Выручка"),
            Map.entry("NET_QUANTITY", "Количество"),
            Map.entry("COST_AMOUNT", "Себестоимость"),
            Map.entry("GROSS_PROFIT", "Валовая прибыль"),
            Map.entry("MARGIN_PERCENT", "Маржа"),
            Map.entry("AVERAGE_RECEIPT", "Средний чек"),
            Map.entry(
                    "ADDITIONAL_REVENUE_PER_PHONE",
                    "Дополнительная выручка на телефон"
            ),
            Map.entry("REVENUE_SHARE_PERCENT", "Доля выручки"),
            Map.entry("NUMERATOR_QUANTITY", "Чистое количество допов"),
            Map.entry("DENOMINATOR_QUANTITY", "Чистое количество техники"),
            Map.entry("RATE_PER_HUNDRED", "Attach-rate"),
            Map.entry("PLAN_ACTUAL_AMOUNT", "Фактическая сумма"),
            Map.entry("PLAN_TARGET_AMOUNT", "Плановая сумма"),
            Map.entry("PLAN_AMOUNT_COMPLETION_PERCENT", "Выполнение плана"),
            Map.entry("PLAN_PROJECTED_AMOUNT", "Прогноз суммы"),
            Map.entry(
                    "PLAN_PROJECTED_COMPLETION_PERCENT",
                    "Прогноз выполнения плана"
            ),
            Map.entry("PLAN_REMAINING_AMOUNT", "Осталось до плана"),
            Map.entry(
                    "PLAN_REQUIRED_PER_REMAINING_DAY",
                    "Требуется в оставшийся день"
            ),
            Map.entry("PLAN_ACTUAL_SHARE_PERCENT", "Фактическая доля"),
            Map.entry("PLAN_TARGET_SHARE_PERCENT", "Целевая доля"),
            Map.entry(
                    "PLAN_SHARE_GAP_PERCENTAGE_POINTS",
                    "Отклонение доли от цели"
            ),
            Map.entry(
                    "PLAN_CRITERION_COMPLETION_PERCENT",
                    "Выполнение целевого процента"
            ),
            Map.entry("PLAN_STATUS", "Статус плана"),
            Map.entry("SHIFT_COUNT", "Отработанные смены"),
            Map.entry("WORKED_HOURS", "Отработанные часы"),
            Map.entry("WORKLOAD_STATUS", "Достаточность нагрузки"),
            Map.entry("COMPLETED_SALES_COUNT", "Завершённые продажи"),
            Map.entry("STORE_REVENUE_SHARE_PERCENT", "Доля выручки магазина"),
            Map.entry("REVENUE_PER_SHIFT", "Выручка за смену"),
            Map.entry("REVENUE_PER_HOUR", "Выручка за час"),
            Map.entry("ADDITIONAL_REVENUE", "Дополнительная выручка"),
            Map.entry("ADDITIONAL_SHARE_PERCENT", "Доля дополнительных продаж"),
            Map.entry("RATING_OVERALL_SCORE", "Итоговый рейтинг"),
            Map.entry("RATING_RANK", "Место в рейтинге"),
            Map.entry("RATING_COVERAGE_PERCENT", "Покрытие рейтинга"),
            Map.entry(
                    "RATING_CONTRIBUTION_SCORE",
                    "Вклад в результат"
            ),
            Map.entry("RATING_EFFICIENCY_SCORE", "Эффективность"),
            Map.entry("RATING_STRUCTURE_SCORE", "Структура продаж"),
            Map.entry("RATING_ATTACH_SCORE", "Attach-rate в рейтинге"),
            Map.entry("RATING_ELIGIBLE_COUNT", "Сотрудники в сравнении")
    );
    private static final Map<String, String> GROUP_LABELS = Map.of(
            "PHONES", "Телефоны",
            "DEVICES", "Техника",
            "ACCESSORY", "Аксессуары",
            "SERVICE", "Услуги",
            "ADDITIONAL_REVENUE", "Дополнительные продажи"
    );
    private static final Map<String, String> PLAN_LABELS = Map.of(
            "REVENUE", "Выручка",
            "ACCESSORY", "Аксессуары",
            "SERVICE", "Услуги",
            "ADDITIONAL", "Допы"
    );
    private static final Map<String, String> STATUS_LABELS = Map.ofEntries(
            Map.entry("SUFFICIENT", "Данных достаточно"),
            Map.entry("LIMITED", "Данные ограничены"),
            Map.entry("INSUFFICIENT", "Данных недостаточно"),
            Map.entry("AHEAD", "Выше плана"),
            Map.entry("ACHIEVED", "Цель достигнута"),
            Map.entry("ON_TRACK", "По плану"),
            Map.entry("AT_RISK", "Есть риск невыполнения"),
            Map.entry("BEHIND", "Ниже плана"),
            Map.entry("COMPLETED", "План выполнен"),
            Map.entry("MISSED", "Цель не выполнена"),
            Map.entry("NOT_AVAILABLE", "Нет надёжных данных"),
            Map.entry("NOT_APPLICABLE", "Не применяется")
    );

    public WeeklyInsightContentView project(
            WeeklyInsightContentView content,
            PersistedWeeklySnapshot snapshot
    ) {
        WeeklyInsightContentView source = requireNonNull(content, "content");
        PersistedWeeklySnapshot persisted = requireNonNull(snapshot, "snapshot");
        ObjectNode store = objectCopy(source.store(), "store");
        ObjectNode team = objectCopy(source.teamInsights(), "teamInsights");
        ArrayNode limitations = arrayCopy(
                source.dataLimitations(), "dataLimitations"
        );
        List<WeeklyInsightEmployeeView> employees = employeeCopies(source.employees());

        Set<String> citedRefs = new TreeSet<>();
        collectEvidenceRefs(store, citedRefs);
        collectEvidenceRefs(team, citedRefs);
        collectEvidenceRefs(limitations, citedRefs);
        employees.forEach(employee ->
                collectEvidenceRefs(employee.insight(), citedRefs)
        );
        require(citedRefs.size() <= MAX_PUBLIC_EVIDENCE,
                "Published interpretation evidence exceeds public API limit");

        Map<String, String> publicCodes = publicCodes(citedRefs);
        EvidenceContext context = context(persisted);
        List<WeeklyInsightEvidenceView> evidence = citedRefs.stream()
                .map(reference -> resolve(
                        publicCodes.get(reference), reference, context
                ))
                .toList();

        rewriteEvidenceRefs(store, publicCodes);
        rewriteEvidenceRefs(team, publicCodes);
        rewriteEvidenceRefs(limitations, publicCodes);
        employees.forEach(employee ->
                rewriteEvidenceRefs(employee.insight(), publicCodes)
        );
        rewriteEmployeeRefs(store, context.memberships());
        rewriteEmployeeRefs(team, context.memberships());
        rewriteEmployeeRefs(limitations, context.memberships());
        employees.forEach(employee ->
                rewriteEmployeeRefs(
                        employee.insight(), context.memberships()
                )
        );
        return new WeeklyInsightContentView(
                store, team, employees, limitations, evidence
        );
    }

    private List<WeeklyInsightEmployeeView> employeeCopies(
            List<WeeklyInsightEmployeeView> employees
    ) {
        return employees.stream().map(employee -> new WeeklyInsightEmployeeView(
                employee.employeeId(),
                employee.displayName(),
                employee.analysisStatus(),
                employee.insight()
        )).toList();
    }

    private Map<String, String> publicCodes(Set<String> references) {
        Map<String, String> result = new LinkedHashMap<>();
        int index = 1;
        for (String reference : references) {
            result.put(
                    reference,
                    "EV" + String.format(Locale.ROOT, "%03d", index++)
            );
        }
        return Map.copyOf(result);
    }

    private EvidenceContext context(PersistedWeeklySnapshot snapshot) {
        Map<String, EvidenceIndexEntry> evidenceIndex = new TreeMap<>();
        snapshot.payload().manifest().evidence().forEach(entry -> {
            EvidenceIndexEntry previous = evidenceIndex.put(
                    entry.evidenceRef(), entry
            );
            require(previous == null,
                    "Snapshot contains duplicate evidence index entries");
        });
        Map<String, Fact> facts = new TreeMap<>();
        snapshot.payload().facts().store().forEach(fact -> putFact(facts, fact));
        snapshot.payload().facts().team().forEach(fact -> putFact(facts, fact));
        for (EmployeeFacts employee : snapshot.payload().facts().employees()) {
            employee.facts().forEach(fact -> putFact(facts, fact));
        }
        Map<String, SnapshotEmployeeMembership> memberships = new TreeMap<>();
        snapshot.employees().forEach(membership ->
                memberships.put(membership.employeeRef(), membership)
        );
        return new EvidenceContext(
                evidenceIndex,
                facts,
                memberships,
                snapshot.payload().manifest().categoryLabels()
        );
    }

    private void putFact(Map<String, Fact> target, Fact fact) {
        Fact previous = target.put(fact.evidenceRef(), fact);
        require(previous == null || previous.equals(fact),
                "Snapshot contains conflicting public evidence facts");
    }

    private WeeklyInsightEvidenceView resolve(
            String code,
            String reference,
            EvidenceContext context
    ) {
        EvidenceIndexEntry index = context.evidenceIndex().get(reference);
        if (index == null) {
            throw new IllegalStateException(
                    "Published interpretation cites evidence outside its snapshot"
            );
        }
        Fact fact = context.facts().get(reference);
        if (index.available() && fact == null) {
            throw new IllegalStateException(
                    "Published interpretation cites unavailable snapshot fact"
            );
        }
        SnapshotEmployeeMembership membership = membership(index, context);
        String categoryLabel = fact == null || fact.categoryCode() == null
                ? null : context.categoryLabels().get(fact.categoryCode());
        if (fact == null) {
            return new WeeklyInsightEvidenceView(
                    code,
                    unavailableLabel(reference),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    index.scope(),
                    id(membership),
                    name(membership),
                    categoryLabel,
                    false
            );
        }
        Comparison comparison = fact.comparison();
        String current = formatValue(fact.value(), fact.unit(), false);
        String previous = comparison == null
                ? null : formatValue(
                        comparison.previousValue(), fact.unit(), false
                );
        String delta = comparison == null
                ? null : formatValue(
                        comparison.absoluteDelta(), fact.unit(), true
                );
        String relative = comparison == null
                ? null : formatValue(
                        comparison.relativeDeltaPercent(), Unit.PERCENT, true
                );
        return new WeeklyInsightEvidenceView(
                code,
                label(fact, reference, membership, categoryLabel),
                current,
                previous,
                delta,
                relative,
                comparisonText(previous, delta, relative),
                fact.unit(),
                fact.sufficiency(),
                index.scope(),
                id(membership),
                name(membership),
                categoryLabel,
                true
        );
    }

    private SnapshotEmployeeMembership membership(
            EvidenceIndexEntry index,
            EvidenceContext context
    ) {
        if (index.employeeRef() == null) {
            return null;
        }
        SnapshotEmployeeMembership membership = context.memberships().get(
                index.employeeRef()
        );
        if (membership == null) {
            throw new IllegalStateException(
                    "Published evidence employee is outside its snapshot"
            );
        }
        return membership;
    }

    private UUID id(SnapshotEmployeeMembership membership) {
        return membership == null ? null : membership.employeeId();
    }

    private String name(SnapshotEmployeeMembership membership) {
        return membership == null ? null : membership.displayNameSnapshot();
    }

    private String label(
            Fact fact,
            String reference,
            SnapshotEmployeeMembership membership,
            String categoryLabel
    ) {
        String metric = METRIC_LABELS.getOrDefault(
                fact.metricCode(), "Показатель"
        );
        String context = contextLabel(reference, categoryLabel);
        List<String> parts = new ArrayList<>();
        if (membership != null) {
            parts.add(membership.displayNameSnapshot());
        } else if (reference.startsWith("TEAM.")) {
            parts.add("Команда");
        }
        if (context != null) {
            parts.add(context);
        }
        parts.add(metric);
        return String.join(" · ", parts);
    }

    private String contextLabel(String reference, String categoryLabel) {
        if (categoryLabel != null) {
            return categoryLabel;
        }
        String group = identifier(reference, "GROUP:");
        if (group != null) {
            return GROUP_LABELS.getOrDefault(group, "Группа продаж");
        }
        String plan = identifier(reference, "PLAN:");
        if (plan != null) {
            return "План — " + PLAN_LABELS.getOrDefault(plan, "направление");
        }
        if (reference.contains(".ATTACH:")) {
            return "Допродажи";
        }
        return null;
    }

    private String identifier(String reference, String marker) {
        int start = reference.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int valueStart = start + marker.length();
        int end = reference.indexOf('.', valueStart);
        return end < 0 ? null : reference.substring(valueStart, end);
    }

    private String unavailableLabel(String reference) {
        if (reference.contains("DATA_COVERAGE")) {
            return "Полнота исходных данных";
        }
        if (reference.contains("CLASSIFICATION_QUALITY")) {
            return "Качество классификации";
        }
        if (reference.contains("ATTACH_DATA_QUALITY")) {
            return "Качество данных attach-rate";
        }
        if (reference.contains("GROSS_PROFIT")) {
            return "Данные валовой прибыли";
        }
        return "Доступность показателя";
    }

    private String comparisonText(
            String previous,
            String delta,
            String relative
    ) {
        if (previous == null) {
            return null;
        }
        StringBuilder result = new StringBuilder("Было ").append(previous);
        if (delta != null) {
            result.append(" · изменение ").append(delta);
            if (relative != null) {
                result.append(" (").append(relative).append(')');
            }
        }
        return result.toString();
    }

    private String formatValue(Object value, Unit unit, boolean signed) {
        if (value == null) {
            return null;
        }
        if (unit == Unit.STATUS) {
            return STATUS_LABELS.getOrDefault(value.toString(), "Статус");
        }
        BigDecimal number = new BigDecimal(value.toString());
        int fractionDigits = switch (unit) {
            case MONEY -> 2;
            case COUNT -> 3;
            case PERCENT, RATE_PER_HUNDRED, HOURS, SCORE -> 1;
            case RANK -> 0;
            case STATUS -> throw new IllegalStateException("STATUS is not numeric");
        };
        BigDecimal rounded = number.setScale(fractionDigits, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        NumberFormat formatter = NumberFormat.getNumberInstance(RUSSIAN);
        formatter.setGroupingUsed(true);
        formatter.setMinimumFractionDigits(0);
        formatter.setMaximumFractionDigits(fractionDigits);
        String formatted = formatter.format(rounded);
        if (signed && number.signum() > 0) {
            formatted = "+" + formatted;
        }
        return switch (unit) {
            case MONEY -> formatted + " ₽";
            case PERCENT, RATE_PER_HUNDRED -> formatted + "%";
            case HOURS -> formatted + " ч";
            case RANK -> signed ? formatted : "№ " + formatted;
            case COUNT, SCORE -> formatted;
            case STATUS -> throw new IllegalStateException("STATUS is not numeric");
        };
    }

    private void collectEvidenceRefs(JsonNode node, Set<String> result) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if ("evidenceRefs".equals(entry.getKey())) {
                    JsonNode refs = entry.getValue();
                    if (!refs.isArray()) {
                        throw new IllegalStateException(
                                "Published evidenceRefs is not an array"
                        );
                    }
                    refs.forEach(value -> {
                        if (!value.isTextual() || value.asText().isBlank()) {
                            throw new IllegalStateException(
                                    "Published evidence reference is not text"
                            );
                        }
                        result.add(value.asText());
                    });
                } else {
                    collectEvidenceRefs(entry.getValue(), result);
                }
            });
        } else if (node.isArray()) {
            node.forEach(value -> collectEvidenceRefs(value, result));
        }
    }

    private void rewriteEvidenceRefs(
            JsonNode node,
            Map<String, String> publicCodes
    ) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> fields = object.properties().stream()
                    .map(Map.Entry::getKey)
                    .toList();
            for (String field : fields) {
                JsonNode value = object.get(field);
                if ("evidenceRefs".equals(field)) {
                    ArrayNode refs = JsonNodeFactory.instance.arrayNode();
                    value.forEach(reference -> {
                        String code = publicCodes.get(reference.asText());
                        if (code == null) {
                            throw new IllegalStateException(
                                    "Published evidence cannot be anonymized"
                            );
                        }
                        refs.add(code);
                    });
                    object.set(field, refs);
                } else {
                    rewriteEvidenceRefs(value, publicCodes);
                }
            }
        } else if (node.isArray()) {
            node.forEach(value -> rewriteEvidenceRefs(value, publicCodes));
        }
    }

    private void rewriteEmployeeRefs(
            JsonNode node,
            Map<String, SnapshotEmployeeMembership> memberships
    ) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> fields = object.properties().stream()
                    .map(Map.Entry::getKey)
                    .toList();
            for (String field : fields) {
                JsonNode value = object.get(field);
                if ("employeeRef".equals(field) && value.isTextual()) {
                    object.put(
                            field,
                            publicEmployeeId(value.asText(), memberships)
                    );
                } else if (value.isArray()
                        && ("employeeRefs".equals(field)
                        || field.endsWith("EmployeeRefs"))) {
                    ArrayNode publicIds = JsonNodeFactory.instance.arrayNode();
                    value.forEach(reference -> publicIds.add(
                            publicEmployeeId(
                                    reference.asText(), memberships
                            )
                    ));
                    object.set(field, publicIds);
                } else {
                    rewriteEmployeeRefs(value, memberships);
                }
            }
        } else if (node.isArray()) {
            node.forEach(value -> rewriteEmployeeRefs(value, memberships));
        }
    }

    private String publicEmployeeId(
            String employeeRef,
            Map<String, SnapshotEmployeeMembership> memberships
    ) {
        SnapshotEmployeeMembership membership = memberships.get(employeeRef);
        if (membership == null) {
            if (isUuid(employeeRef)) {
                return employeeRef;
            }
            throw new IllegalStateException(
                    "Published interpretation employee is outside its snapshot"
            );
        }
        return membership.employeeId().toString();
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private ObjectNode objectCopy(JsonNode value, String field) {
        if (!value.isObject()) {
            throw new IllegalStateException(
                    "Weekly insight " + field + " must be an object"
            );
        }
        return (ObjectNode) value.deepCopy();
    }

    private ArrayNode arrayCopy(JsonNode value, String field) {
        if (!value.isArray()) {
            throw new IllegalStateException(
                    "Weekly insight " + field + " must be an array"
            );
        }
        return (ArrayNode) value.deepCopy();
    }

    private record EvidenceContext(
            Map<String, EvidenceIndexEntry> evidenceIndex,
            Map<String, Fact> facts,
            Map<String, SnapshotEmployeeMembership> memberships,
            Map<String, String> categoryLabels
    ) {
    }
}
