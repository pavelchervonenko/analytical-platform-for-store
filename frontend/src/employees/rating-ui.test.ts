import { describe, expect, it } from "vitest";
import type { EmployeeDirectoryEntry, EmployeeRatingEntry } from "../api/contracts";
import { employeeRatingReason, selectEmployeeEntries } from "./rating-ui";

function entry(name: string, rank: number | null, revenue: number, options: Partial<EmployeeRatingEntry> = {}): EmployeeDirectoryEntry {
  const current: EmployeeRatingEntry = {
    employeeId: crypto.randomUUID(),
    displayName: name,
    employeeActive: true,
    assignmentActive: true,
    participatesInRanking: true,
    ratingEligible: true,
    shiftCount: 10,
    workedHours: 100,
    netRevenue: revenue,
    storeRevenueSharePercent: 20,
    revenuePerShift: revenue / 10,
    revenuePerHour: revenue / 100,
    accessoryRevenue: 0,
    accessorySharePercent: 0,
    serviceRevenue: 0,
    serviceSharePercent: 0,
    additionalRevenue: 0,
    additionalSharePercent: 0,
    scores: {
      contributionScore: 100,
      contributionWeightedPoints: 25,
      efficiencyScore: 100,
      efficiencyWeightedPoints: 25,
      structureScore: 100,
      structureWeightedPoints: 25,
      attachScore: 100,
      attachWeightedPoints: 25,
      coveragePercent: 100,
      overallScore: 100
    },
    ranked: rank != null,
    rank,
    attachRates: [],
    ...options
  };
  return {
    current,
    dynamics: {
      previousRank: null,
      currentRank: rank,
      rankImprovement: null,
      overallScoreChange: null,
      revenueChange: null,
      revenuePerHourChange: null,
      accessoryShareChange: null,
      serviceShareChange: null,
      additionalShareChange: null,
      attachRateChanges: []
    }
  };
}

describe("employee rating UI selection", () => {
  it("sorts visually without changing authoritative dense ranks", () => {
    const entries = [entry("Анна", 1, 100), entry("Борис", 2, 300)];
    const result = selectEmployeeEntries(entries, "", "all", "revenue");
    expect(result.map((item) => item.current.displayName)).toEqual(["Борис", "Анна"]);
    expect(result.map((item) => item.current.rank)).toEqual([2, 1]);
  });

  it("keeps employees without a rank after ranked employees", () => {
    const entries = [entry("Без места", null, 500), entry("С местом", 2, 100)];
    const result = selectEmployeeEntries(entries, "", "all", "rank");
    expect(result.map((item) => item.current.rank)).toEqual([2, null]);
  });

  it("hides non-participants from every employee list", () => {
    const entries = [entry("Анна", null, 0, { participatesInRanking: false }), entry("Борис", 1, 300)];
    expect(selectEmployeeEntries(entries, "", "all", "rank").map((item) => item.current.displayName)).toEqual(["Борис"]);
    expect(selectEmployeeEntries(entries, "АН", "all", "rank")).toHaveLength(0);
  });

  it("explains why an employee has no rank", () => {
    expect(employeeRatingReason(entry("Анна", null, 0, { shiftCount: 0 }).current)).toBe("Нет смен за период");
    expect(employeeRatingReason(entry("Анна", null, 0, { scores: { ...entry("X", 1, 1).current.scores, coveragePercent: 50, overallScore: null } }).current)).toBe("Недостаточно данных");
  });
});
