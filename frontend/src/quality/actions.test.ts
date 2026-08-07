import { describe, expect, it } from "vitest";
import { describeQualityAction } from "./actions";

describe("quality action routing", () => {
  it("keeps administrator-only actions fail closed", () => {
    expect(describeQualityAction("RUN_SYNC", false)?.route).toBeUndefined();
    expect(describeQualityAction("RUN_SYNC", false)?.unavailableReason).toEqual(expect.any(String));
    expect(describeQualityAction("CLASSIFY_PRODUCTS", true)).toMatchObject({ route: "/admin", view: "classification" });
  });

  it("routes correction actions by stable action code", () => {
    expect(describeQualityAction("SET_STORE_PLAN", false)).toMatchObject({ route: "/plan", view: "plan" });
    expect(describeQualityAction("UPDATE_WORK_SCHEDULE", false)).toMatchObject({ route: "/plan", view: "shifts" });
    expect(describeQualityAction("CALCULATE_PAYROLL", false)).toMatchObject({ route: "/payroll" });
  });

  it("does not invent an unsupported cost mutation", () => {
    expect(describeQualityAction("PROVIDE_COST_DATA", true)?.route).toBeUndefined();
    expect(describeQualityAction("PROVIDE_COST_DATA", true)?.unavailableReason).toEqual(expect.any(String));
  });

  it("opens source issues and source synchronization instead of a no-op route", () => {
    expect(describeQualityAction("REVIEW_DATA_ISSUES", false)).toMatchObject({
      route: "/quality",
      hash: "#quality-source-issues"
    });
    expect(describeQualityAction("REVIEW_SOURCE_DOCUMENT", true)).toMatchObject({
      route: "/admin",
      view: "sync"
    });
    expect(describeQualityAction("REVIEW_SOURCE_DOCUMENT", false)?.route).toBeUndefined();
  });
});
