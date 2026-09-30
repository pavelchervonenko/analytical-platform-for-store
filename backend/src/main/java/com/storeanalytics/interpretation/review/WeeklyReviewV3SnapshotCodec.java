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

/** Separate v3 serialization and semantic hash; v2 codec bytes remain frozen. */
@Component
final class WeeklyReviewV3SnapshotCodec {

    private final ObjectMapper mapper;
    private final ObjectWriter writer;

    WeeklyReviewV3SnapshotCodec() {
        mapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        writer = mapper.writer();
    }

    String serialize(WeeklyReviewV3Response response) {
        WeeklyReviewV3ScopeValidator.validate(response);
        try {
            return writer.writeValueAsString(response);
        } catch (JacksonException exception) {
            throw new IllegalStateException("V3 weekly review could not be encoded", exception);
        }
    }

    WeeklyReviewV3Response deserialize(String payload) {
        try {
            WeeklyReviewV3Response response = mapper.readValue(payload, WeeklyReviewV3Response.class);
            WeeklyReviewV3ScopeValidator.validate(response);
            return response;
        } catch (JacksonException exception) {
            throw new IllegalStateException("V3 weekly review payload is not readable", exception);
        }
    }

    String contentHash(WeeklyReviewV3Response response) {
        WeeklyReviewV3ScopeValidator.validate(response);
        WeeklyReviewV3Response.Membership membership = response.membership();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(writer.writeValueAsBytes(new ContentV3(
                            response.contractVersion(), response.versions(), response.period(),
                            response.reportState(), response.qualitySummary(),
                            response.sourceCoverage(), response.scope(),
                            new MembershipContent(membership.basis(),
                                    membership.currentCohortHash(),
                                    membership.previousCohortHash(),
                                    membership.actionabilityRosterHash(),
                                    membership.selectedSellerCount()),
                            response.summary(), response.results(), response.revenueDecomposition(),
                            response.additionalSales(), response.factors(), response.salesStructure(),
                            response.team(), response.teamDisplay(),
                            response.employees().stream().map(this::withoutPresentationName).toList(),
                            response.actions(), response.limitations(), response.evidence(),
                            response.aiEnhancement()))));
        } catch (JacksonException exception) {
            throw new IllegalStateException("V3 weekly review hash could not be created", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private WeeklyReviewV3Response.EmployeeCard withoutPresentationName(
            WeeklyReviewV3Response.EmployeeCard employee
    ) {
        WeeklyReviewResponse.EmployeeCard card = employee.card();
        WeeklyReviewResponse.EmployeeCard semantic = new WeeklyReviewResponse.EmployeeCard(
                card.employeePublicId(), "PRESENTATION_NAME_EXCLUDED", card.participatesInBenchmark(),
                card.sortGroup(), card.metrics(), card.ownDynamics(), card.peerComparison(),
                card.strength(), card.attention(), card.action(), card.limitations());
        return new WeeklyReviewV3Response.EmployeeCard(semantic, employee.actionableNow());
    }

    private record MembershipContent(
            String basis,
            String currentCohortHash,
            String previousCohortHash,
            String actionabilityRosterHash,
            int selectedSellerCount
    ) {
    }

    private record ContentV3(
            int contractVersion,
            WeeklyReviewResponse.VersionSet versions,
            WeeklyReviewResponse.PeriodContext period,
            WeeklyReviewResponse.ReportState reportState,
            WeeklyReviewResponse.QualitySummary qualitySummary,
            java.util.List<WeeklyReviewV3Response.SellerSourceCoverage> sourceCoverage,
            String scope,
            MembershipContent membership,
            WeeklyReviewResponse.SummaryBlock summary,
            java.util.List<WeeklyReviewResponse.MetricComparison> results,
            WeeklyReviewResponse.RevenueDecomposition revenueDecomposition,
            WeeklyReviewV3Response.AdditionalSales additionalSales,
            java.util.List<WeeklyReviewResponse.Factor> factors,
            WeeklyReviewResponse.SalesStructureBlock salesStructure,
            WeeklyReviewResponse.TeamBlock team,
            WeeklyReviewV3Response.TeamDisplay teamDisplay,
            java.util.List<WeeklyReviewV3Response.EmployeeCard> employees,
            java.util.List<WeeklyReviewResponse.Action> actions,
            java.util.List<WeeklyReviewResponse.Limitation> limitations,
            java.util.List<WeeklyReviewResponse.Evidence> evidence,
            WeeklyReviewResponse.AiEnhancement aiEnhancement
    ) {
    }
}
