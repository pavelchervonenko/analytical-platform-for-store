import { describe, expect, it } from "vitest";
import type { StoreQualityIssue } from "./issue-groups";
import {
  groupStoreQualityIssues,
  qualityIssueGuidance
} from "./issue-groups";

function issue(
  key: string,
  code: string,
  detectedAt: string
): StoreQualityIssue {
  return {
    key,
    source: "DATA",
    code,
    severity: "WARNING",
    entityType: "SALE_ITEM",
    message: "Data consistency issue requires review",
    detectedAt,
    recommendedAction: "REVIEW_SOURCE_DOCUMENT"
  };
}

describe("quality issue groups", () => {
  it("groups repeated entity-level warnings and keeps the latest timestamp", () => {
    const result = groupStoreQualityIssues([
      issue("first", "ZERO_UNEXPECTED_COST", "2026-08-06T10:00:00Z"),
      issue("second", "ZERO_UNEXPECTED_COST", "2026-08-06T11:00:00Z"),
      issue("third", "UNMAPPED_PRODUCT", "2026-08-06T09:00:00Z")
    ]);

    expect(result).toHaveLength(2);
    expect(result[0]).toMatchObject({
      code: "ZERO_UNEXPECTED_COST",
      eventCount: 2,
      latestDetectedAt: "2026-08-06T11:00:00Z"
    });
    expect(result[1]).toMatchObject({
      code: "UNMAPPED_PRODUCT",
      eventCount: 1
    });
  });

  it("does not merge warnings with different severity or source", () => {
    const warning = issue("warning", "ZERO_UNEXPECTED_COST", "2026-08-06T10:00:00Z");
    const error = { ...warning, key: "error", severity: "ERROR" as const };
    const returns = { ...warning, key: "returns", source: "RETURNS" as const };

    expect(groupStoreQualityIssues([warning, error, returns])).toHaveLength(3);
  });

  it("explains expected zero cost without hiding real product errors", () => {
    expect(qualityIssueGuidance("ZERO_UNEXPECTED_COST")).toContain(
      "Для услуг, гарантий и протекций"
    );
    expect(qualityIssueGuidance("FUTURE_ISSUE")).toContain(
      "Проверьте исходный документ"
    );
  });
});
