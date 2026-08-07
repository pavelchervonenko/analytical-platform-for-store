import { describe, expect, it } from "vitest";
import { queryKeys } from "./queries";

describe("queryKeys.payrollRuns", () => {
  it("returns a true prefix when optional filters are omitted", () => {
    expect(queryKeys.payrollRuns("store-1"))
      .toEqual(["stores", "store-1", "payroll", "runs"]);
  });

  it("includes concrete history filters without undefined segments", () => {
    expect(queryKeys.payrollRuns("store-1", "2026-07", 0))
      .toEqual(["stores", "store-1", "payroll", "runs", "2026-07", 0]);
  });
});
