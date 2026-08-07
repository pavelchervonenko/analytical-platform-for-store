package com.storeanalytics.notification.fanout;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class WeeklyTelegramMessageRenderer {

    static final int TELEGRAM_TEXT_LIMIT = 4096;
    private static final int NARRATIVE_LIMIT = 230;
    private static final int EMPLOYEE_LINE_LIMIT = 190;
    private static final int RELATIONSHIP_LIMIT = 4;
    private static final int LIMITATION_LIMIT = 3;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern(
            "dd.MM.yyyy"
    );

    public RenderedTelegramMessage render(
            WeeklyNotificationEvent event,
            JsonNode content,
            Map<String, String> employeeNames
    ) {
        WeeklyNotificationEvent source = requireNonNull(event, "event");
        JsonNode root = requireNonNull(content, "content");
        Map<String, String> names = Map.copyOf(requireNonNull(
                employeeNames,
                "employeeNames"
        ));
        StringBuilder text = new StringBuilder();

        line(text, source.revised()
                ? "📊 НЕДЕЛЯ · ОТЧЁТ ОБНОВЛЁН"
                : "📊 НЕДЕЛЯ · ОТЧЁТ ГОТОВ");
        line(text, source.storeName());
        line(text, DATE_FORMAT.format(source.periodStart()) + " — "
                + DATE_FORMAT.format(source.periodEnd())
                + " · версия " + source.revision());

        switch (source.contentSchemaVersion()) {
            case 1 -> renderLegacy(text, root, names);
            case 2 -> renderFlat(text, root, names);
            default -> throw new IllegalStateException(
                    "Unsupported Telegram content schema version: "
                            + source.contentSchemaVersion()
            );
        }

        section(text, "ПОЛНЫЙ РАЗБОР");
        line(text, "Откройте аналитический кабинет: там есть детали "
                + "по категориям, сотрудникам и данным.");
        String rendered = fit(text.toString().strip(), TELEGRAM_TEXT_LIMIT);
        return new RenderedTelegramMessage(rendered, sha256(rendered));
    }

    private void renderLegacy(
            StringBuilder text,
            JsonNode root,
            Map<String, String> names
    ) {
        JsonNode store = root.path("store");
        section(text, "✨ ГЛАВНОЕ");
        block(text, null, store.path("headline").path("text"));

        section(text, "📈 РЕЗУЛЬТАТ И ДИНАМИКА");
        block(text, "Результат", store.path("resultSummary").path("text"));
        block(text, "Изменение", store.path("dynamicsSummary").path("text"));
        block(text, "План", store.path("planOutlook").path("text"));

        section(text, "🔎 ФОКУС");
        block(text, "Категории",
                store.path("categoryPerformance").path("summary").path("text"));
        block(text, "Доп. продажи",
                store.path("additionalSalesPerformance")
                        .path("summary").path("text"));
        block(text, "Главный риск",
                store.path("primaryRisk").path("summary"));

        renderLegacyActions(text, store.path("recommendedActions"));

        JsonNode team = root.path("teamInsights");
        String teamSummary = value(team.path("summary").path("text"));
        if (!teamSummary.isBlank()) {
            section(text, "👥 КОМАНДА");
            line(text, teamSummary);
        }
        renderLegacyEmployees(text, root.path("employees"), names);
    }

    private void renderLegacyActions(StringBuilder text, JsonNode actions) {
        if (!actions.isArray() || actions.isEmpty()) {
            return;
        }
        section(text, "🎯 ДЕЙСТВИЯ НА НЕДЕЛЮ");
        int number = 1;
        for (JsonNode action : actions) {
            action(text, number++, action);
        }
    }

    private void renderLegacyEmployees(
            StringBuilder text,
            JsonNode employees,
            Map<String, String> names
    ) {
        if (!employees.isArray() || employees.isEmpty()) {
            return;
        }
        section(text, "СОТРУДНИКИ");
        employees.forEach(employee ->
                legacyEmployeeLine(text, employee, names));
    }

    private void renderFlat(
            StringBuilder text,
            JsonNode root,
            Map<String, String> names
    ) {
        Set<String> seen = new HashSet<>();
        String headline = summaryText(
                root,
                "STORE",
                null,
                "HEADLINE"
        );
        if (!headline.isBlank()) {
            section(text, "✨ ГЛАВНОЕ");
        }
        flatLine(text, null, headline, seen);

        String result = summaryText(root, "STORE", null, "RESULT");
        String dynamics = summaryText(root, "STORE", null, "DYNAMICS");
        String plan = summaryText(root, "STORE", null, "PLAN_OUTLOOK");
        if (anyPresent(result, dynamics, plan)) {
            section(text, "📈 РЕЗУЛЬТАТ И ДИНАМИКА");
        }
        flatLine(text, "Результат", result, seen);
        flatLine(text, "Изменение", dynamics, seen);
        flatLine(text, "План", plan, seen);

        String categories = summaryText(
                root,
                "STORE",
                null,
                "CATEGORY_PERFORMANCE"
        );
        String additionalSales = summaryText(
                root,
                "STORE",
                null,
                "ADDITIONAL_SALES"
        );
        String risk = insightSummary(root, "STORE", null, "RISK");
        if (anyPresent(categories, additionalSales, risk)) {
            section(text, "🔎 ФОКУС");
        }
        flatLine(text, "Категории", categories, seen);
        flatLine(text, "Доп. продажи", additionalSales, seen);
        flatLine(text, "Главный риск", risk, seen);

        renderActions(text, root.path("actions"));
        renderTeam(text, root, names);
        renderEmployees(text, root, names);
        renderLimitations(text, root.path("dataLimitations"));
    }

    private void renderActions(StringBuilder text, JsonNode actions) {
        if (!actions.isArray() || actions.isEmpty()) {
            return;
        }
        section(text, "🎯 ДЕЙСТВИЯ НА НЕДЕЛЮ");
        int number = 1;
        for (JsonNode action : actions) {
            action(text, number++, action);
        }
    }

    private void action(StringBuilder text, int number, JsonNode action) {
        String title = value(action.path("title"));
        String summary = value(action.path("summary"));
        String horizon = horizonLabel(value(action.path("horizon")));
        line(text, number + ". " + join(title, horizon));
        if (!summary.isBlank() && !sameText(title, summary)) {
            line(text, "↳ " + truncate(summary, NARRATIVE_LIMIT));
        }
    }

    private void renderTeam(
            StringBuilder text,
            JsonNode root,
            Map<String, String> names
    ) {
        String summary = summaryText(root, "TEAM", null, "TEAM_OVERVIEW");
        JsonNode relationships = root.path("teamRelationships");
        if (summary.isBlank()
                && (!relationships.isArray() || relationships.isEmpty())) {
            return;
        }
        section(text, "👥 КОМАНДА");
        if (!summary.isBlank()) {
            line(text, truncate(summary, NARRATIVE_LIMIT));
        }
        int rendered = 0;
        for (JsonNode relationship : relationships) {
            if (rendered >= RELATIONSHIP_LIMIT) {
                break;
            }
            String source = employeeNames(
                    relationship.path("sourceEmployeeRefs"),
                    names
            );
            String target = employeeNames(
                    relationship.path("targetEmployeeRefs"),
                    names
            );
            String participants = target.isBlank()
                    ? source
                    : source + " → " + target;
            String marker = relationshipMarker(
                    value(relationship.path("type"))
            );
            String summaryText = value(relationship.path("summary"));
            line(text, marker + " " + truncate(
                    join(participants, summaryText),
                    NARRATIVE_LIMIT
            ));
            rendered++;
        }
        if (relationships.size() > rendered) {
            line(text, "Ещё рекомендаций по обмену опытом: "
                    + (relationships.size() - rendered));
        }
    }

    private void renderEmployees(
            StringBuilder text,
            JsonNode root,
            Map<String, String> names
    ) {
        JsonNode employees = root.path("employees");
        if (!employees.isArray() || employees.isEmpty()) {
            return;
        }
        section(text, "СОТРУДНИКИ");
        employees.forEach(employee -> flatEmployeeLine(
                text,
                root,
                employee,
                names
        ));
    }

    private void renderLimitations(
            StringBuilder text,
            JsonNode limitations
    ) {
        if (!limitations.isArray() || limitations.isEmpty()) {
            return;
        }
        section(text, "⚠️ ЧТО ВАЖНО УЧЕСТЬ");
        int rendered = 0;
        Set<String> seen = new HashSet<>();
        int hidden = 0;
        for (JsonNode limitation : limitations) {
            String summary = truncate(
                    value(limitation.path("summary")),
                    NARRATIVE_LIMIT
            );
            if (summary.isBlank()
                    || !seen.add(summary.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (rendered >= LIMITATION_LIMIT) {
                hidden++;
                continue;
            }
            line(text, "• " + summary);
            rendered++;
        }
        if (hidden > 0) {
            line(text, "Остальные ограничения подробно описаны в кабинете.");
        }
    }

    private void flatEmployeeLine(
            StringBuilder text,
            JsonNode root,
            JsonNode employee,
            Map<String, String> names
    ) {
        String ref = value(employee.path("employeeRef"));
        String name = displayName(ref, names);
        if ("INSUFFICIENT".equals(value(employee.path("analysisStatus")))) {
            line(text, "• " + name + " — пока недостаточно данных");
            return;
        }
        String headline = summaryText(root, "EMPLOYEE", ref, "HEADLINE");
        String risk = insightTitle(root, "EMPLOYEE", ref, "RISK");
        String details = join(headline, labelled("Фокус", risk));
        line(text, truncate(
                "• " + name + " — " + fallback(
                        details,
                        "результат доступен в кабинете"
                ),
                EMPLOYEE_LINE_LIMIT
        ));
    }

    private void legacyEmployeeLine(
            StringBuilder text,
            JsonNode employee,
            Map<String, String> names
    ) {
        String ref = value(employee.path("employeeRef"));
        String name = displayName(ref, names);
        if ("INSUFFICIENT".equals(value(employee.path("analysisStatus")))) {
            line(text, "• " + name + " — пока недостаточно данных");
            return;
        }
        String details = join(
                value(employee.path("headline").path("text")),
                labelled(
                        "Фокус",
                        value(employee.path("attentionArea").path("title"))
                ),
                labelled(
                        "Риск",
                        value(employee.path("primaryRisk").path("title"))
                )
        );
        line(text, truncate(
                "• " + name + " — " + fallback(
                        details,
                        "результат доступен в кабинете"
                ),
                EMPLOYEE_LINE_LIMIT
        ));
    }

    private String summaryText(
            JsonNode root,
            String scope,
            String employeeRef,
            String section
    ) {
        for (JsonNode summary : root.path("summaryBlocks")) {
            if (scope.equals(value(summary.path("scope")))
                    && section.equals(value(summary.path("section")))
                    && sameNullableRef(
                            summary.path("employeeRef"),
                            employeeRef
                    )) {
                return value(summary.path("text"));
            }
        }
        return "";
    }

    private String insightSummary(
            JsonNode root,
            String scope,
            String employeeRef,
            String kind
    ) {
        JsonNode insight = insight(root, scope, employeeRef, kind);
        return insight == null ? "" : value(insight.path("summary"));
    }

    private String insightTitle(
            JsonNode root,
            String scope,
            String employeeRef,
            String kind
    ) {
        JsonNode insight = insight(root, scope, employeeRef, kind);
        return insight == null ? "" : value(insight.path("title"));
    }

    private JsonNode insight(
            JsonNode root,
            String scope,
            String employeeRef,
            String kind
    ) {
        for (JsonNode insight : root.path("insights")) {
            if (scope.equals(value(insight.path("scope")))
                    && kind.equals(value(insight.path("kind")))
                    && sameNullableRef(
                            insight.path("employeeRef"),
                            employeeRef
                    )) {
                return insight;
            }
        }
        return null;
    }

    private boolean sameNullableRef(JsonNode node, String expected) {
        return expected == null ? node.isNull() : expected.equals(value(node));
    }

    private String employeeNames(
            JsonNode refs,
            Map<String, String> names
    ) {
        StringBuilder result = new StringBuilder();
        for (JsonNode ref : refs) {
            String name = displayName(value(ref), names);
            if (!name.isBlank()) {
                if (!result.isEmpty()) {
                    result.append(", ");
                }
                result.append(name);
            }
        }
        return result.toString();
    }

    private String displayName(
            String employeeRef,
            Map<String, String> names
    ) {
        String name = names.get(employeeRef);
        return name == null || name.isBlank() ? "Сотрудник" : normalize(name);
    }

    private String relationshipMarker(String type) {
        return switch (type) {
            case "COMPETENCY_LEADER" -> "⭐";
            case "MOST_IMPROVED" -> "↗";
            case "LEARNING_OPPORTUNITY" -> "↔";
            default -> "•";
        };
    }

    private String horizonLabel(String horizon) {
        return switch (horizon) {
            case "CURRENT_WEEK" -> "на этой неделе";
            case "NEXT_WEEK" -> "на следующей неделе";
            case "NEXT_30_DAYS" -> "в течение месяца";
            default -> "";
        };
    }

    private void flatLine(
            StringBuilder text,
            String label,
            String value,
            Set<String> seen
    ) {
        String normalized = normalize(value);
        if (normalized.isBlank() || !seen.add(normalized)) {
            return;
        }
        line(text, label == null
                ? truncate(normalized, NARRATIVE_LIMIT)
                : "• " + label + ": "
                        + truncate(normalized, NARRATIVE_LIMIT));
    }

    private void block(
            StringBuilder text,
            String label,
            JsonNode node
    ) {
        String normalized = value(node);
        if (normalized.isBlank()) {
            return;
        }
        line(text, label == null
                ? truncate(normalized, NARRATIVE_LIMIT)
                : "• " + label + ": "
                        + truncate(normalized, NARRATIVE_LIMIT));
    }

    private String labelled(String label, String value) {
        return value.isBlank() ? "" : label + ": " + value;
    }

    private String join(String... values) {
        return java.util.Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " · " + right)
                .orElse("");
    }

    private boolean anyPresent(String... values) {
        return java.util.Arrays.stream(values)
                .anyMatch(value ->
                        value != null && !value.isBlank()
                );
    }

    private String fallback(String value, String fallback) {
        return value.isBlank() ? fallback : value;
    }

    private boolean sameText(String first, String second) {
        return normalize(first).equalsIgnoreCase(normalize(second));
    }

    private String value(JsonNode node) {
        return node.isTextual() ? normalize(node.asText()) : "";
    }

    private String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    private String truncate(String value, int limit) {
        String normalized = normalize(value);
        int count = normalized.codePointCount(0, normalized.length());
        if (count <= limit) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, Math.max(1, limit - 1));
        return normalized.substring(0, end).stripTrailing() + "…";
    }

    private String fit(String value, int limit) {
        if (value.codePointCount(0, value.length()) <= limit) {
            return value;
        }
        String suffix = "\n\nЧасть деталей сокращена. "
                + "Полная версия — в кабинете.";
        int budget = limit - suffix.codePointCount(0, suffix.length());
        int end = value.offsetByCodePoints(0, budget);
        String candidate = value.substring(0, end);
        int lineBreak = candidate.lastIndexOf('\n');
        String fitted = lineBreak > 0
                ? candidate.substring(0, lineBreak).stripTrailing()
                : candidate.stripTrailing();
        return fitted + suffix;
    }

    private void section(StringBuilder text, String title) {
        blank(text);
        line(text, title);
    }

    private void line(StringBuilder text, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank()) {
            text.append(normalized).append('\n');
        }
    }

    private void blank(StringBuilder text) {
        if (!text.isEmpty()
                && text.charAt(text.length() - 1) == '\n'
                && (text.length() < 2
                || text.charAt(text.length() - 2) != '\n')) {
            text.append('\n');
        }
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }
}
