package com.storeanalytics.interpretation.query;

import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Adapts content v3 to the stable dashboard presentation model. */
final class WeeklyInsightV3ContentProjector {

    static final String NEUTRAL_HEADLINE =
            "За неделю не выявлено существенных изменений, "
                    + "требующих отдельного внимания.";

    private final WeeklyInsightV2ContentProjector delegate =
            new WeeklyInsightV2ContentProjector();

    WeeklyInsightContentView project(
            JsonNode content,
            PersistedWeeklySnapshot snapshot
    ) {
        if (!(content instanceof ObjectNode root)) {
            throw new IllegalStateException(
                    "Published interpretation content is not an object"
            );
        }
        ObjectNode adapted = root.deepCopy();
        JsonNode summaries = adapted.path("summaryBlocks");
        if (!(summaries instanceof ArrayNode summaryBlocks)) {
            throw new IllegalStateException(
                    "Published interpretation field is not an array: summaryBlocks"
            );
        }
        JsonNode primarySignal = adapted.path("primarySignal");
        ObjectNode headline = summaryBlocks.insertObject(0);
        headline.put("scope", "STORE");
        headline.putNull("employeeRef");
        headline.put("section", "HEADLINE");
        if (primarySignal.isObject()) {
            headline.set(
                    "categoryCode",
                    primarySignal.path("categoryCode").deepCopy()
            );
            headline.set("text", primarySignal.path("text").deepCopy());
            headline.set(
                    "evidenceRefs",
                    primarySignal.path("evidenceRefs").deepCopy()
            );
        } else {
            headline.putNull("categoryCode");
            headline.put("text", NEUTRAL_HEADLINE);
            headline.putArray("evidenceRefs");
        }
        adapted.remove("primarySignal");
        return delegate.project(adapted, snapshot);
    }
}
