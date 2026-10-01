package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.review.PersistedWeeklyReviewV3Snapshot;
import com.storeanalytics.interpretation.review.SellerWeeklyV3AssemblerTest;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SellerWeeklyReviewAiTest {
    private static final Instant NOW = Instant.parse("2026-08-24T08:00:00Z");
    private final SellerWeeklyReviewAiInputCompactor compactor = new SellerWeeklyReviewAiInputCompactor();
    private final WeeklyReviewAiContentCodec codec = new WeeklyReviewAiContentCodec();

    @Test
    void providerReceivesOnlyVersionedSellerAggregatesAndNoRosterOrNames() {
        var report = SellerWeeklyV3AssemblerTest.syntheticResponse();
        var snapshot = new PersistedWeeklyReviewV3Snapshot(UUID.randomUUID(), UUID.randomUUID(), 1, null,
                report, "a".repeat(64), NOW);
        var factory = new WeeklyReviewAiProviderRequestFactory(new WeeklyReviewAiInputCompactor(), codec);
        var prepared = factory.prepare(new WeeklyReviewAiProviderRequestCommand(UUID.randomUUID(), snapshot,
                "YANDEX", "synthetic-model", new BigDecimal("0.1"), 1400, NOW, Duration.ofSeconds(180),
                NOW.plusSeconds(600), List.of()));

        assertThat(prepared.input().contractVersion()).isEqualTo(5);
        assertThat(prepared.input().promptVersion()).isEqualTo(SellerWeeklyReviewAiContract.PROMPT_VERSION);
        String input = codec.canonical(prepared.input());
        assertThat(input).contains("\"scope\":\"SELLERS\"", "\"reportContractVersion\":3")
                .doesNotContain("STORE.", "Synthetic", "displayName", "employeePublicId", "membership");
        report.employees().forEach(item -> assertThat(input).doesNotContain(item.card().employeePublicId()));
    }

    @Test
    void selectorValidationAndReadTimeEnrichmentPreserveAllSellerFacts() throws Exception {
        var report = SellerWeeklyV3AssemblerTest.syntheticResponse();
        var input = compactor.compact(report);
        var selection = new WeeklyReviewAiSelection(1, new WeeklyReviewAiSelection.SummarySelection(
                input.summary().allowedSelectors().getFirst(),
                input.factors().isEmpty() ? null : input.factors().getFirst().factorId(), null),
                input.factors().stream().map(item -> new WeeklyReviewAiSelection.FactorSelection(item.factorId(),
                        item.allowedSelectors().getFirst())).toList());
        var validation = new WeeklyReviewAiSemanticValidator(new WeeklyReviewAiStructuralValidator())
                .validate(input, JsonMapper.builder().build().writeValueAsString(selection));
        assertThat(validation.semanticValidated()).isTrue();
        String canonical = codec.canonical(validation.content());
        var enrichment = new PersistedWeeklyReviewAiEnrichment(UUID.randomUUID(), UUID.randomUUID(),
                SellerWeeklyReviewAiContract.PROMPT_VERSION, 4, codec.hash(codec.canonical(input)),
                validation.content(), canonical, codec.hash(canonical), NOW, NOW, NOW);
        var enhanced = new SellerWeeklyReviewAiEnricher(compactor, codec)
                .applyIfCompatible(report, enrichment).orElseThrow();

        assertThat(enhanced.results()).isEqualTo(report.results());
        assertThat(enhanced.additionalSales()).isEqualTo(report.additionalSales());
        assertThat(enhanced.employees()).isEqualTo(report.employees());
        assertThat(enhanced.actions()).isEqualTo(report.actions());
        assertThat(enhanced.membership()).isEqualTo(report.membership());
        assertThat(enhanced.evidence()).isEqualTo(report.evidence());
        assertThat(enhanced.aiEnhancement().state()).isEqualTo(WeeklyReviewResponse.AiState.READY);
        var legacyCache = new PersistedWeeklyReviewAiEnrichment(enrichment.id(), enrichment.snapshotId(),
                "weekly-interpretation-v25", 4, enrichment.inputHash(), validation.content(), canonical,
                codec.hash(canonical), NOW, NOW, NOW);
        assertThat(new SellerWeeklyReviewAiEnricher(compactor, codec).applyIfCompatible(report, legacyCache)).isEmpty();
        var otherInput = new PersistedWeeklyReviewAiEnrichment(enrichment.id(), enrichment.snapshotId(),
                enrichment.promptVersion(), 4, "f".repeat(64), validation.content(), canonical,
                codec.hash(canonical), NOW, NOW, NOW);
        assertThat(new SellerWeeklyReviewAiEnricher(compactor, codec).applyIfCompatible(report, otherInput)).isEmpty();
        var invalid = new WeeklyReviewAiSemanticValidator(new WeeklyReviewAiStructuralValidator())
                .validate(input, "{\"selectionSchemaVersion\":1,\"summary\":{\"selector\":\"SUMMARY_OUTCOME\","
                        + "\"primaryFactorId\":\"STORE.X\",\"secondaryFactorId\":null},\"factorSelections\":[]}");
        assertThat(invalid.semanticValidated()).isFalse();
    }

    @Test
    void offlineSellerV26CorpusRejectsInventedAndMisorderedManagementClaims() throws Exception {
        var input = balancedSellerInput();
        var validator = new WeeklyReviewAiSemanticValidator(new WeeklyReviewAiStructuralValidator());
        var valid = new WeeklyReviewAiSelection(1,
                new WeeklyReviewAiSelection.SummarySelection(
                        "SUMMARY_BALANCED", "factor:strength", "factor:risk"),
                List.of(new WeeklyReviewAiSelection.FactorSelection("factor:strength", "FACTOR_SIGNAL"),
                        new WeeklyReviewAiSelection.FactorSelection("factor:risk", "FACTOR_CONTROL")));
        var accepted = validator.validate(input, JsonMapper.builder().build().writeValueAsString(valid));
        assertThat(accepted.semanticValidated()).isTrue();
        assertThat(accepted.content().summary().text()).contains("доступной части данных");
        assertThat(codec.canonical(accepted.content())).doesNotContain("STORE.", "employeePublicId");

        assertRejected(validator, input, new WeeklyReviewAiSelection(1,
                new WeeklyReviewAiSelection.SummarySelection(
                        "SUMMARY_BALANCED", "STORE.NET_REVENUE", "factor:risk"),
                valid.factorSelections()), "SUMMARY_FOCUS_NOT_ALLOWED");
        assertRejected(validator, input, new WeeklyReviewAiSelection(1, valid.summary(),
                List.of(new WeeklyReviewAiSelection.FactorSelection("factor:strength", "FACTOR_SIGNAL"),
                        new WeeklyReviewAiSelection.FactorSelection("factor:risk", "FACTOR_STRENGTH"))),
                "FACTOR_SELECTOR_NOT_ALLOWED");
        assertRejected(validator, input, new WeeklyReviewAiSelection(1, valid.summary(),
                List.of(valid.factorSelections().getFirst())), "FACTOR_SET_MISMATCH");
        assertRejected(validator, input, new WeeklyReviewAiSelection(1, valid.summary(),
                List.of(valid.factorSelections().getLast(), valid.factorSelections().getFirst())),
                "FACTOR_SET_MISMATCH");
        assertRejected(validator, input, new WeeklyReviewAiSelection(1,
                new WeeklyReviewAiSelection.SummarySelection(
                        "SUMMARY_BALANCED", "factor:risk", "factor:strength"),
                valid.factorSelections()), "SUMMARY_FOCUS_EFFECT_MISMATCH");
    }

    private void assertRejected(WeeklyReviewAiSemanticValidator validator,
                                SellerWeeklyReviewAiInput input, WeeklyReviewAiSelection selection,
                                String expectedCode) throws Exception {
        var result = validator.validate(input, JsonMapper.builder().build().writeValueAsString(selection));
        assertThat(result.semanticValidated()).isFalse();
        assertThat(result.violations()).extracting(value -> value.code()).contains(expectedCode);
    }

    private SellerWeeklyReviewAiInput balancedSellerInput() {
        return new SellerWeeklyReviewAiInput(5, SellerWeeklyReviewAiContract.PROMPT_VERSION, 4,
                "PARTIAL", 3, "SELLERS",
                new WeeklyReviewAiInput.SummarySource("MIXED", List.of("SUMMARY_BALANCED"),
                        List.of("factor:strength", "factor:risk"), List.of("SELLERS.NET_REVENUE")),
                List.of(new WeeklyReviewAiInput.FactorSource("factor:strength", "REVENUE_CHANGE",
                                "Положительный результат продавцов", "UP", "POSITIVE", false,
                                List.of("FACTOR_SIGNAL", "FACTOR_STRENGTH"), List.of("SELLERS.NET_REVENUE")),
                        new WeeklyReviewAiInput.FactorSource("factor:risk", "RETURN_CHANGE",
                                "Сумма возвратов продавцов", "UP", "NEGATIVE", false,
                                List.of("FACTOR_RISK", "FACTOR_CONTROL"), List.of("SELLERS.NET_REVENUE"))),
                List.of(new WeeklyReviewAiInput.ActionSource("action:team", "Проверить итог команды",
                        "Сверить доступные агрегаты", List.of("SELLERS.NET_REVENUE"))),
                List.of(new WeeklyReviewAiInput.EvidenceSource("SELLERS.NET_REVENUE",
                        "Чистая выручка продавцов", "RUB", "100", "90")));
    }
}
