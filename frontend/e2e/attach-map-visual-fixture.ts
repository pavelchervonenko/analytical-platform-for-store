import type { EmployeeKpiEntry, EmployeeRatingEntry } from "../src/api/contracts";

export const attachMapVisualEmployees: EmployeeRatingEntry[] = [{
  employeeId: "40000000-0000-4000-8000-000000000001",
  displayName: "Анна (тест)",
  employeeActive: true,
  assignmentActive: true,
  participatesInRanking: true,
  ratingEligible: true,
  shiftCount: 4,
  workedHours: 32,
  netRevenue: 100_000,
  storeRevenueSharePercent: 10,
  revenuePerShift: 25_000,
  revenuePerHour: 3_125,
  accessoryRevenue: 8_000,
  accessorySharePercent: 8,
  serviceRevenue: 4_000,
  serviceSharePercent: 4,
  additionalRevenue: 12_000,
  additionalSharePercent: 12,
  scores: {
    contributionScore: 100, contributionWeightedPoints: 25,
    efficiencyScore: 100, efficiencyWeightedPoints: 25,
    structureScore: 100, structureWeightedPoints: 25,
    attachScore: 100, attachWeightedPoints: 25,
    coveragePercent: 100, overallScore: 100
  },
  ranked: true,
  rank: 1,
  attachRates: [{
    metricCode: "CHARGER_CABLE", numeratorCategoryCode: "CHARGER_CABLE", denominatorCode: "PHONE",
    numeratorReceiptCount: 2, denominatorReceiptCount: 5,
    numeratorQuantity: 2, denominatorQuantity: 5,
    ratePercent: 40, storeRatePercent: 30, includedInScore: true, score: 133.33
  }, {
    metricCode: "POWER_BANK", numeratorCategoryCode: "POWER_BANK", denominatorCode: "PHONE",
    numeratorReceiptCount: 1, denominatorReceiptCount: 5,
    numeratorQuantity: 1, denominatorQuantity: 5,
    ratePercent: 20, storeRatePercent: 20, includedInScore: true, score: 100
  }]
}];

export const attachMapVisualKpiEmployees: EmployeeKpiEntry[] = attachMapVisualEmployees.map((employee) => ({
  employeeId: employee.employeeId,
  displayName: employee.displayName,
  employeeActive: true,
  assignedToStore: true,
  assignmentActive: true,
  participatesInRanking: true,
  rankingEligible: true,
  unassigned: false,
  netRevenue: employee.netRevenue,
  netQuantity: 5,
  costAmount: 60_000,
  grossProfit: 40_000,
  marginPercent: 40,
  dataQuality: {
    completeCostData: true, includedItemCount: 5, unmappedItemCount: 0,
    missingCostItemCount: 0, unexpectedZeroCostItemCount: 0
  }
}));
