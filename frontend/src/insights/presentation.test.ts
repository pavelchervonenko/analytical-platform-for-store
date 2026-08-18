import { describe, expect, it } from "vitest";
import {
  actionHorizonLabel,
  actionTypeLabel,
  analysisStatusLabel,
  employeeAnalysisHelp,
  insightKindHelp,
  insightKindLabel,
  insightKindTone,
  limitationSummary,
  readableInsightText,
  uniqueNarratives
} from "./presentation";

describe("weekly insight presentation", () => {
  it("never exposes unknown technical codes to a manager", () => {
    expect(actionHorizonLabel("FUTURE_INTERNAL_CODE")).toBe("Ближайший период");
    expect(actionTypeLabel("INTERNAL_ACTION_V3")).toBe("Практический шаг");
    expect(analysisStatusLabel("NEW_MODEL_STATUS")).toBe("Статус анализа");
    expect(insightKindLabel("NEW_INSIGHT_KIND")).toBe("Вывод");
    expect(insightKindTone("NEW_INSIGHT_KIND")).toBe("unknown");
  });

  it("separates facts, interpretations and hypotheses for the manager", () => {
    expect(insightKindLabel("OBSERVATION")).toBe("Наблюдение");
    expect(insightKindLabel("SYNTHESIS")).toBe("Интерпретация");
    expect(insightKindLabel("HYPOTHESIS")).toBe("Гипотеза");
    expect(insightKindTone("RISK")).toBe("risk");
    expect(insightKindHelp("HYPOTHESIS"))
      .toContain("нужно проверить");
    expect(insightKindHelp("OBSERVATION")).toBeNull();
  });

  it("removes repeated narratives independent of whitespace and case", () => {
    const result = uniqueNarratives(
      [" Рост продаж ", "рост   продаж", "Фокус на сервисе"],
      (item) => item
    );
    expect(result).toEqual([" Рост продаж ", "Фокус на сервисе"]);
  });

  it("uses a human fallback for an unknown limitation shape", () => {
    expect(limitationSummary({ code: "TECHNICAL_CODE" }))
      .toBe("Части данных пока недостаточно для уверенного вывода.");
    expect(limitationSummary({ summary: "Нет смен за один день." }))
      .toBe("Нет смен за один день.");
  });

  it("explains why an insufficient employee cannot receive a detailed analysis", () => {
    expect(employeeAnalysisHelp("INSUFFICIENT"))
      .toContain("подтверждённых смен");
    expect(employeeAnalysisHelp("LIMITED"))
      .toContain("пониженную уверенность");
  });

  it("uses natural punctuation instead of a middle-dot separator", () => {
    expect(readableInsightText("Выручка · изменение +20%"))
      .toBe("Выручка, изменение +20%");
  });
});
