package com.storeanalytics.interpretation.review;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
final class WeeklyReviewSnapshotCodec {

    private final ObjectMapper mapper;
    private final ObjectWriter writer;

    WeeklyReviewSnapshotCodec() {
        mapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        writer = mapper.writer();
    }

    String serialize(WeeklyReviewResponse response) {
        try {
            return writer.writeValueAsString(response);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Weekly review could not be encoded", exception);
        }
    }

    WeeklyReviewResponse deserialize(String payload) {
        try {
            return mapper.readValue(payload, WeeklyReviewResponse.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Weekly review payload is not readable", exception);
        }
    }

    String contentHash(WeeklyReviewResponse response) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(writer.writeValueAsBytes(new Content(
                            response.contractVersion(),
                            response.versions(),
                            response.period(),
                            response.reportState(),
                            response.qualitySummary(),
                            response.sourceCoverage(),
                            response.summary(),
                            response.results(),
                            response.revenueDecomposition(),
                            response.factors(),
                            response.salesStructure(),
                            response.team(),
                            response.employees(),
                            response.actions(),
                            response.limitations(),
                            response.evidence(),
                            response.aiEnhancement()
                    ))));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Weekly review hash could not be created", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Content(
            int contractVersion,
            WeeklyReviewResponse.VersionSet versions,
            WeeklyReviewResponse.PeriodContext period,
            WeeklyReviewResponse.ReportState reportState,
            WeeklyReviewResponse.QualitySummary qualitySummary,
            java.util.List<WeeklyReviewResponse.SourceCoverage> sourceCoverage,
            WeeklyReviewResponse.SummaryBlock summary,
            java.util.List<WeeklyReviewResponse.MetricComparison> results,
            WeeklyReviewResponse.RevenueDecomposition revenueDecomposition,
            java.util.List<WeeklyReviewResponse.Factor> factors,
            WeeklyReviewResponse.SalesStructureBlock salesStructure,
            WeeklyReviewResponse.TeamBlock team,
            java.util.List<WeeklyReviewResponse.EmployeeCard> employees,
            java.util.List<WeeklyReviewResponse.Action> actions,
            java.util.List<WeeklyReviewResponse.Limitation> limitations,
            java.util.List<WeeklyReviewResponse.Evidence> evidence,
            WeeklyReviewResponse.AiEnhancement aiEnhancement
    ) {
    }
}
