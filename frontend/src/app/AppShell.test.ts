import { describe, expect, it } from "vitest";
import { navigationGroupsFor } from "./AppShell";

describe("application navigation", () => {
  it("shows the System group only to administrators", () => {
    expect(navigationGroupsFor("MANAGER").map((group) => group.label)).not.toContain("Система");
    expect(navigationGroupsFor("UNKNOWN").map((group) => group.label)).not.toContain("Система");

    const systemGroup = navigationGroupsFor("ADMIN").find((group) => group.label === "Система");
    expect(systemGroup?.items.map((item) => item.label)).toEqual(["Качество данных", "Настройки"]);
  });
});
