import { describe, expect, it } from "vitest";
import { buildMonthCalendar, parseWorkedHours, validatePlanForm } from "./forms";

describe("plan and schedule forms", () => {
  it("accepts comma decimals and creates the exact plan payload", () => {
    const result = validatePlanForm({ revenueTarget: "24 000 000,50", accessoryShareTarget: "3,9", serviceShareTarget: "3", additionalShareTarget: "7,25" });
    expect(result.errors).toEqual({});
    expect(result.data).toEqual({ revenueTarget: 24_000_000.5, accessoryShareTarget: 3.9, serviceShareTarget: 3, additionalShareTarget: 7.25 });
  });

  it("rejects invalid shares and excess precision", () => {
    const result = validatePlanForm({ revenueTarget: "0", accessoryShareTarget: "100.01", serviceShareTarget: "2.123", additionalShareTarget: "-1" });
    expect(Object.keys(result.errors)).toHaveLength(4);
    expect(result.data).toBeNull();
  });

  it("validates worked hours at the backend boundaries", () => {
    expect(parseWorkedHours("0,01")).toBe(0.01);
    expect(parseWorkedHours("11.00")).toBe(11);
    expect(parseWorkedHours("11.01")).toBeNull();
    expect(parseWorkedHours("6.555")).toBeNull();
  });

  it("builds a Monday-first calendar without dates outside the selected month", () => {
    const cells = buildMonthCalendar("2026-07");
    expect(cells.length % 7).toBe(0);
    expect(cells[2]).toBe("2026-07-01");
    expect(cells.filter(Boolean)).toHaveLength(31);
  });
});
