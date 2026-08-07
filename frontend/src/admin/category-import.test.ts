import { describe, expect, it } from "vitest";
import { instantToReportingDateTime, parseCategoryAssignments, reportingDateTimeToInstant } from "./category-import";

const validItem = {
  externalProductId: "4310",
  productName: "Cable",
  categoryCode: "CHARGER_CABLE",
  conditionType: "NOT_APPLICABLE"
};

describe("parseCategoryAssignments", () => {
  it("accepts a valid strict payload", () => {
    const result = parseCategoryAssignments(JSON.stringify([validItem]));
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.assignments).toEqual([validItem]);
  });

  it("accepts a complete approved artifact and exposes its metadata", () => {
    const result = parseCategoryAssignments(JSON.stringify({
      validFrom: "2025-12-31T22:00:00Z",
      ruleVersion: "customer-approved-2026-07-20-v1",
      changeReason: "Initial customer-approved classification",
      assignments: [validItem]
    }));
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.assignments).toEqual([validItem]);
      expect(result.metadata).toEqual({
        validFrom: "2025-12-31T22:00:00Z",
        ruleVersion: "customer-approved-2026-07-20-v1",
        changeReason: "Initial customer-approved classification"
      });
    }
  });

  it("rejects duplicate external ids before sending", () => {
    const result = parseCategoryAssignments(JSON.stringify([validItem, validItem]));
    expect(result).toEqual({
      ok: false,
      message: "Строка 1: externalProductId «4310» повторяется."
    });
  });

  it("rejects unknown properties", () => {
    const result = parseCategoryAssignments(JSON.stringify([{ ...validItem, secret: "no" }]));
    expect(result.ok).toBe(false);
  });
});

describe("reporting classification time conversions", () => {
  it("converts Kaliningrad wall time to an explicit instant", () => {
    expect(reportingDateTimeToInstant("2026-07-01T00:00")).toBe("2026-06-30T22:00:00.000Z");
  });

  it("converts an artifact instant back to Kaliningrad wall time", () => {
    expect(instantToReportingDateTime("2025-12-31T22:00:00Z")).toBe("2026-01-01T00:00");
  });
});
