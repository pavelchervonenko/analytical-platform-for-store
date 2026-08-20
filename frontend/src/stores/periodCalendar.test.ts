import { describe, expect, it } from "vitest";
import { buildCalendarMonth, orderedRange, quickPeriods } from "./periodCalendar";

describe("period calendar helpers", () => {
  it("builds a stable Monday-first six-week grid", () => {
    const days = buildCalendarMonth("2026-08");

    expect(days).toHaveLength(42);
    expect(days[0]).toEqual({ date: "2026-07-27", inCurrentMonth: false });
    expect(days[5]).toEqual({ date: "2026-08-01", inCurrentMonth: true });
    expect(days.at(-1)).toEqual({ date: "2026-09-06", inCurrentMonth: false });
  });

  it("orders a range when the second click is earlier", () => {
    expect(orderedRange("2026-08-18", "2026-08-01"))
      .toEqual({ start: "2026-08-01", end: "2026-08-18" });
  });

  it("uses the latest covered day for rolling presets", () => {
    const presets = quickPeriods("2026-08-20", "2026-08-18");

    expect(presets.find((preset) => preset.code === "LAST_7_DAYS")?.range)
      .toEqual({ start: "2026-08-12", end: "2026-08-18" });
    expect(presets.find((preset) => preset.code === "LAST_30_DAYS")?.range)
      .toEqual({ start: "2026-07-20", end: "2026-08-18" });
    expect(presets.map((preset) => preset.label)).not.toContain("Этот год");
    expect(presets.map((preset) => preset.label)).not.toContain("Прошлый год");
  });

  it("keeps today explicit while completed-period presets follow coverage", () => {
    const presets = quickPeriods("2026-08-20", "2026-08-18");

    expect(presets.find((preset) => preset.code === "TODAY")?.range)
      .toEqual({ start: "2026-08-20", end: "2026-08-20" });
    expect(presets.find((preset) => preset.code === "THIS_MONTH")?.range)
      .toEqual({ start: "2026-08-01", end: "2026-08-18" });
  });
});
