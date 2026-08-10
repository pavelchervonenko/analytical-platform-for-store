import { describe, expect, it } from "vitest";
import { clampAsOfDate, clampCompletedAsOfDate, monthRange, shiftMonth } from "./date";

describe("calendar date helpers", () => {
  it("keeps inclusive leap-year month boundaries", () => {
    expect(monthRange("2028-02")).toEqual({ start: "2028-02-01", end: "2028-02-29" });
  });

  it("moves between years without constructing local-time intervals", () => {
    expect(shiftMonth("2026-01", -1)).toBe("2025-12");
    expect(shiftMonth("2026-12", 1)).toBe("2027-01");
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
});
