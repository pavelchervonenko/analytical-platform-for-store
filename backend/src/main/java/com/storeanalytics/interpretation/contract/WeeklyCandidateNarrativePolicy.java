package com.storeanalytics.interpretation.contract;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateSignal;
import java.util.Map;

/** Supplies safe user-facing copy for exact backend candidate signals. */
public final class WeeklyCandidateNarrativePolicy {

    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("FINANCIAL_RESULT", "Финансовый результат"),
            Map.entry("REVENUE_DYNAMICS", "Динамика выручки"),
            Map.entry("PROFITABILITY", "Динамика валовой прибыли"),
            Map.entry("PLAN", "Выполнение плана"),
            Map.entry("CATEGORY_MIX", "Динамика категории"),
            Map.entry("ADDITIONAL_SALES", "Дополнительные продажи"),
            Map.entry("ATTACH_RATE", "Прикрепление дополнительных позиций"),
            Map.entry("RETURNS", "Динамика возвратов"),
            Map.entry("TIME_EFFICIENCY", "Эффективность рабочего времени"),
            Map.entry("TEAM_PERFORMANCE", "Командный результат"),
            Map.entry(
                    "EMPLOYEE_PERFORMANCE",
                    "Динамика результата сотрудника"
            ),
            Map.entry("SALES_QUALITY", "Качество продаж"),
            Map.entry("DATA_QUALITY", "Качество данных"),
            Map.entry("OTHER", "Подтверждённый бизнес-сигнал")
    );

    private WeeklyCandidateNarrativePolicy() {
    }

    public static Narrative forCandidate(CandidateSignal candidate) {
        String title = TITLES.getOrDefault(
                candidate.theme(),
                TITLES.get("OTHER")
        );
        String summary = switch (candidate.kind()) {
            case RISK -> title + " требует внимания.";
            case OPPORTUNITY ->
                    title + ": подтверждён положительный сигнал.";
            case OBSERVATION -> title + ": подтверждено изменение.";
        };
        return new Narrative(title, summary);
    }

    public record Narrative(String title, String summary) {
    }
}
