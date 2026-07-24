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
});
