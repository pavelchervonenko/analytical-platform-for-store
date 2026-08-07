package com.storeanalytics.interpretation.query;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.snapshot.PersistedWeeklySnapshot;
import org.springframework.stereotype.Component;

@Component
public class WeeklyInsightFallbackFactory {

    public WeeklyInsightFallbackView create(PersistedWeeklySnapshot snapshot) {
        QualityStatus quality = snapshot.qualityStatus();
        String summary = quality == QualityStatus.BLOCKED
                ? "Данных недостаточно для надёжной интерпретации. "
                    + "Проверьте показатели и качество данных в кабинете."
                : "Числовые показатели за неделю доступны в кабинете. "
                    + "Автоматическая интерпретация временно недоступна.";
        return new WeeklyInsightFallbackView(
                "Результаты недели",
                summary,
                quality,
                snapshot.payload().manifest().limitations().stream()
                        .map(limitation -> limitation.code())
                        .distinct()
                        .sorted()
                        .toList()
        );
    }
}
