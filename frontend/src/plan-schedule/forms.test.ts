import { describe, expect, it } from "vitest";
import type { EmployeeShift } from "../api/contracts";
import { buildMonthCalendar, isSelectableShiftSeller, parseWorkedHours, rebaseWorkShiftInputs, validatePlanForm } from "./forms";

function shift(employeeId: string, workedHours: number): EmployeeShift {
  return {
    id: `shift-${employeeId}`,
    employeeId,
    employeeName: employeeId,
    workDate: "2026-09-14",
    workedHours,
    active: true,
    version: 1
  };
}

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

  it("allows only active rating participants in the shift roster", () => {
    expect(isSelectableShiftSeller({ employeeActive: true, assignmentActive: true, participatesInRanking: true })).toBe(true);
    expect(isSelectableShiftSeller({ employeeActive: true, assignmentActive: true, participatesInRanking: false })).toBe(false);
    expect(isSelectableShiftSeller({ employeeActive: false, assignmentActive: true, participatesInRanking: true })).toBe(false);
    expect(isSelectableShiftSeller({ employeeActive: true, assignmentActive: false, participatesInRanking: true })).toBe(false);
  });

  it("rebases only explicitly changed employees onto the latest day", () => {
    expect(rebaseWorkShiftInputs(
      [shift("anna", 11), shift("boris", 8)],
      [
        { employeeId: "anna", workedHours: 9 },
        { employeeId: "ilnur", workedHours: 11 }
      ],
      [shift("anna", 10), shift("boris", 7), shift("denis", 6)]
    )).toEqual([
      { employeeId: "anna", workedHours: 9 },
      { employeeId: "denis", workedHours: 6 },
      { employeeId: "ilnur", workedHours: 11 }
    ]);
  });

  it("does not restore untouched employees removed by another manager", () => {
    expect(rebaseWorkShiftInputs(
      [shift("anna", 11), shift("boris", 8)],
      [
        { employeeId: "anna", workedHours: 11 },
        { employeeId: "boris", workedHours: 8 },
        { employeeId: "ilnur", workedHours: 11 }
      ],
      [shift("boris", 8)]
    )).toEqual([
      { employeeId: "boris", workedHours: 8 },
      { employeeId: "ilnur", workedHours: 11 }
    ]);
  });

  it("builds a Monday-first calendar without dates outside the selected month", () => {
    const cells = buildMonthCalendar("2026-07");
    expect(cells.length % 7).toBe(0);
    expect(cells[2]).toBe("2026-07-01");
    expect(cells.filter(Boolean)).toHaveLength(31);
  });
});
