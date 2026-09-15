import { describe, expect, it } from "vitest";
import {
  clampAsOfDate,
  clampCompletedAsOfDate,
  completedDataThroughDate,
  effectiveMonthRange,
  isIsoMonth,
  monthRange,
  planMonthForPeriod,
  shiftMonth
} from "./date";

describe("calendar date helpers", () => {
  it("keeps inclusive leap-year month boundaries", () => {
    expect(monthRange("2028-02")).toEqual({ start: "2028-02-01", end: "2028-02-29" });
  });

  it("moves between years without constructing local-time intervals", () => {
    expect(shiftMonth("2026-01", -1)).toBe("2025-12");
    expect(shiftMonth("2026-12", 1)).toBe("2027-01");
  });

  it("rejects malformed and out-of-range calendar months", () => {
    expect(isIsoMonth("2026-09")).toBe(true);
    expect(isIsoMonth("2026-00")).toBe(false);
    expect(isIsoMonth("2026-13")).toBe(false);
    expect(isIsoMonth("2026-9")).toBe(false);
    expect(isIsoMonth(null)).toBe(false);
  });

  it("anchors partial and cross-boundary periods to the month of their end date", () => {
    expect(planMonthForPeriod("MONTH", "2026-09", "2026-09-13")).toBe("2026-09");
    expect(planMonthForPeriod("CUSTOM", "2026-08", "2026-09-13")).toBe("2026-09");
    expect(planMonthForPeriod("WEEK", "2026-12", "2027-01-03")).toBe("2027-01");
  });

  it("keeps a full selected month even when its data coverage is partial", () => {
    expect(planMonthForPeriod("MONTH", "2026-09", "2026-09-08")).toBe("2026-09");
  });

  it("keeps as-of inside the requested month", () => {
    expect(clampAsOfDate("2026-07-23", "2026-07-01", "2026-07-31")).toBe("2026-07-23");
    expect(clampAsOfDate("2026-08-02", "2026-07-01", "2026-07-31")).toBe("2026-07-31");
  });

  it("uses the last completed calendar day for the current month", () => {
    expect(clampCompletedAsOfDate("2026-08-10", "2026-08-01", "2026-08-31"))
      .toBe("2026-08-09");
  });

  it("keeps completed historical months at month end", () => {
    expect(clampCompletedAsOfDate("2026-08-10", "2026-07-01", "2026-07-31"))
      .toBe("2026-07-31");
  });

  it("uses the earlier of source coverage and the last completed day", () => {
    expect(completedDataThroughDate("2026-08-20", "2026-08-19"))
      .toBe("2026-08-19");
    expect(completedDataThroughDate("2026-08-20", "2026-08-20"))
      .toBe("2026-08-19");
    expect(completedDataThroughDate("2026-08-20", "2026-08-18"))
      .toBe("2026-08-18");
  });

  it("makes the current month match the completed covered range", () => {
    expect(effectiveMonthRange("2026-08", "2026-08-20", "2026-08-19"))
      .toEqual({ start: "2026-08-01", end: "2026-08-19" });
    expect(effectiveMonthRange("2026-07", "2026-08-20", "2026-08-19"))
      .toEqual({ start: "2026-07-01", end: "2026-07-31" });
  });
});
