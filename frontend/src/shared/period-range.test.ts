import { describe, expect, it } from "vitest";
import { inclusiveDayCount, isIsoDate, shiftDate, weekRange } from "./date";

describe("analytics period helpers", () => {
  it("builds a Monday to Sunday week", () => {
    expect(weekRange("2026-07-24")).toEqual({ start: "2026-07-20", end: "2026-07-26" });
    expect(weekRange("2026-07-26")).toEqual({ start: "2026-07-20", end: "2026-07-26" });
  });

  it("shifts dates over month and year boundaries", () => {
    expect(shiftDate("2026-01-01", -1)).toBe("2025-12-31");
    expect(shiftDate("2024-02-28", 1)).toBe("2024-02-29");
  });

  it("validates ISO dates and counts inclusive boundaries", () => {
    expect(isIsoDate("2026-02-29")).toBe(false);
    expect(isIsoDate("2024-02-29")).toBe(true);
    expect(inclusiveDayCount("2026-07-20", "2026-07-26")).toBe(7);
    expect(inclusiveDayCount("2026-07-26", "2026-07-20")).toBe(0);
  });
});
