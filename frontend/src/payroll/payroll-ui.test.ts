import { describe, expect, it } from "vitest";
import type { PayrollStatement } from "../api/contracts";
import { adjustmentTypeLabel, comparisonReasonLabel, parsePayrollAmount, summarizeStatements, validateReason } from "./payroll-ui";

const statement = (values: Partial<PayrollStatement>): PayrollStatement => ({
  id: "11111111-1111-4111-8111-111111111111",
  employeeId: "22222222-2222-4222-8222-222222222222",
  employeeName: "Анна",
  shiftCount: 3,
  workedHours: 33,
  earnedAmount: 80000,
  advanceAmount: 50000,
  penaltyAmount: 0,
  inventoryAmount: 0,
  taxAmount: 0,
  payableAmount: 30000,
  ...values
});

describe("payroll UI helpers", () => {
  it("aggregates only authoritative statement values", () => {
    expect(summarizeStatements([
      statement({ penaltyAmount: 500, payableAmount: 29500 }),
      statement({ earnedAmount: 90000, inventoryAmount: 1000, payableAmount: 39000 })
    ])).toEqual({ earned: 170000, advance: 100000, deductions: 1500, payable: 68500 });
  });

  it("accepts payroll amounts with comma and at most two decimals", () => {
    expect(parsePayrollAmount(" 3500,25 ")).toBe(3500.25);
    expect(parsePayrollAmount("0.01")).toBe(0.01);
    expect(parsePayrollAmount("12.345")).toBeNull();
    expect(parsePayrollAmount("0")).toBeNull();
  });

  it("requires a concise audit reason", () => {
    expect(validateReason("  ")).toContain("причину");
    expect(validateReason("Инвентаризация 24 июля")).toBeNull();
    expect(validateReason("x".repeat(501))).toContain("500");
  });

  it("uses safe labels for future enum variants", () => {
    expect(adjustmentTypeLabel("INVENTORY")).toBe("Инвентаризация");
    expect(adjustmentTypeLabel("FUTURE_TYPE")).toBe("Другое удержание");
    expect(comparisonReasonLabel("FUTURE_REASON")).toBe("Изменились данные расчёта");
  });
});
