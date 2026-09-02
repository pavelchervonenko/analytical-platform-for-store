import { describe, expect, it } from "vitest";
import { makeWeeklyReview } from "../test/weeklyReviewFixture";
import {
  actionTargetText,
  formatValue,
  metricComparisonText,
  metricTone,
  nextWeekLabel
} from "./weekly-review-presentation";

describe("weekly review presentation", () => {
  it("formats business values without technical notation", () => {
    expect(formatValue(3_284_500, "RUB")).toBe("3 284 500 ₽");
    expect(formatValue(18.65, "PERCENT")).toBe("18,7%");
    expect(formatValue(68, "PER_100")).toBe("68 на 100");
  });

  it("uses effect, not direction, for the comparison tone", () => {
    const returns = makeWeeklyReview().revenueDecomposition.returnRevenue;

    expect(returns.direction).toBe("UP");
    expect(metricTone(returns)).toBe("negative");
    expect(metricComparisonText(returns)).toBe("+100%");
  });

  it("explains a missing comparison base", () => {
    const metric = makeWeeklyReview().results[0]!;
    metric.previous = 0;
    metric.comparisonKind = "NO_BASE";

    expect(metricComparisonText(metric)).toBe("Нет базы сравнения");
  });

  it("shows margin movement in percentage points", () => {
    const margin = makeWeeklyReview().results.find((metric) => (
      metric.code === "MARGIN_PERCENT"
    ))!;
    margin.current = 45;
    margin.previous = 40;
    margin.absoluteDelta = 5;
    margin.changePercent = 12.5;
    margin.direction = "UP";

    expect(metricComparisonText(margin)).toBe("+5 п. п.");
  });

  it("formats the full week after the completed period", () => {
    expect(nextWeekLabel("2026-08-23")).toBe("24–30 августа 2026");
    expect(nextWeekLabel("2026-08-30")).toBe("31 августа — 6 сентября 2026");
  });

  it("renders an actionable target in plain language", () => {
    const action = makeWeeklyReview().actions[0]!;

    expect(actionTargetText(action)).toBe("не выше 50 ₽");
  });
});
