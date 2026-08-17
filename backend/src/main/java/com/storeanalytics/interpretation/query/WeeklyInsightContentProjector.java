package com.storeanalytics.interpretation.query;

import com.storeanalytics.interpretation.snapshot.SnapshotEmployeeMembership;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class WeeklyInsightContentProjector {

    private static final int LEGACY_SCHEMA_VERSION = 1;
    private static final int FLAT_SCHEMA_VERSION = 2;
    private static final int PRIMARY_SIGNAL_SCHEMA_VERSION = 3;
    private final WeeklyInsightV2ContentProjector flatProjector =
            new WeeklyInsightV2ContentProjector();
    private final WeeklyInsightV3ContentProjector primarySignalProjector =
            new WeeklyInsightV3ContentProjector();

    public WeeklyInsightContentView project(
            WeeklyInterpretationDetailView interpretation,
            PersistedWeeklySnapshot snapshot
    ) {
        int version = interpretation.interpretation() == null
                ? LEGACY_SCHEMA_VERSION
                : interpretation.interpretation().contentSchemaVersion();
        return switch (version) {
            case LEGACY_SCHEMA_VERSION -> projectLegacy(
                    interpretation.content(), snapshot
            );
            case FLAT_SCHEMA_VERSION -> flatProjector.project(
                    interpretation.content(), snapshot
            );
            case PRIMARY_SIGNAL_SCHEMA_VERSION -> primarySignalProjector.project(
                    interpretation.content(), snapshot
            );
            default -> throw new IllegalStateException(
                    "Unsupported published interpretation schema version: " + version
            );
        };
    }

    private WeeklyInsightContentView projectLegacy(
            JsonNode content,
            PersistedWeeklySnapshot snapshot
    ) {
        Map<String, SnapshotEmployeeMembership> employeesByRef = new HashMap<>();
        for (SnapshotEmployeeMembership membership : snapshot.employees()) {
            employeesByRef.put(membership.employeeRef(), membership);
        }

        List<WeeklyInsightEmployeeView> employees = new ArrayList<>();
        for (JsonNode employeeNode : content.path("employees")) {
            String employeeRef = requiredText(employeeNode, "employeeRef");
            SnapshotEmployeeMembership membership = employeesByRef.get(employeeRef);
            if (membership == null) {
                throw new IllegalStateException(
                        "Published interpretation employee is outside its snapshot"
                );
            }
            ObjectNode publicInsight = ((ObjectNode) employeeNode.deepCopy());
            publicInsight.remove("employeeRef");
            employees.add(new WeeklyInsightEmployeeView(
                    membership.employeeId(),
                    membership.displayNameSnapshot(),
                    requiredText(employeeNode, "analysisStatus"),
                    publicInsight
            ));
        }
        if (employees.size() != snapshot.employees().size()) {
            throw new IllegalStateException(
                    "Published interpretation employee set is incomplete"
            );
        }
        return new WeeklyInsightContentView(
                requiredObject(content, "store"),
                publicTeamInsights(content, employeesByRef),
                employees,
                requiredArray(content, "dataLimitations")
        );
    }

    private JsonNode publicTeamInsights(
            JsonNode content,
            Map<String, SnapshotEmployeeMembership> employeesByRef
    ) {
        ObjectNode team = (ObjectNode) requiredObject(
                content, "teamInsights"
        ).deepCopy();
        for (JsonNode leader : team.path("competencyLeaders")) {
            addNames(
                    (ObjectNode) leader,
                    "employeeNames",
                    leader.path("employeeRefs"),
                    employeesByRef
            );
        }
        for (JsonNode improved : team.path("mostImproved")) {
            SnapshotEmployeeMembership membership = employeesByRef.get(
                    requiredText(improved, "employeeRef")
            );
            if (membership != null) {
                ((ObjectNode) improved).put(
                        "displayName", membership.displayNameSnapshot()
                );
            }
        }
        for (JsonNode opportunity : team.path("learningOpportunities")) {
            addNames(
                    (ObjectNode) opportunity,
                    "mentorNames",
                    opportunity.path("mentorEmployeeRefs"),
                    employeesByRef
            );
            addNames(
                    (ObjectNode) opportunity,
                    "targetNames",
                    opportunity.path("targetEmployeeRefs"),
                    employeesByRef
            );
        }
        return team;
    }

    private void addNames(
            ObjectNode target,
            String field,
            JsonNode employeeRefs,
            Map<String, SnapshotEmployeeMembership> employeesByRef
    ) {
        var names = target.putArray(field);
        for (JsonNode employeeRef : employeeRefs) {
            SnapshotEmployeeMembership membership = employeesByRef.get(
                    employeeRef.asText()
            );
            if (membership != null) {
                names.add(membership.displayNameSnapshot());
            }
        }
    }

    private JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) {
            throw new IllegalStateException(
                    "Published interpretation field is not an object: " + field
            );
        }
        return value;
    }

    private JsonNode requiredArray(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isArray()) {
            throw new IllegalStateException(
                    "Published interpretation field is not an array: " + field
            );
        }
        return value;
    }

    private String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException(
                    "Published interpretation field is not text: " + field
            );
        }
        return value.textValue();
    }
}
