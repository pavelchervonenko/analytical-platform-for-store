import { describe, expect, it } from "vitest";
import { navigationGroupsFor } from "./AppShell";

describe("application navigation", () => {
  it("shows plan and shifts as separate management destinations", () => {
    const management = navigationGroupsFor("MANAGER").find((group) => group.label === "Управление");

    expect(management?.items.map((item) => [item.to, item.label])).toEqual([
      ["/plan", "План"],
      ["/shifts", "Смены"],
      ["/payroll", "Зарплата"],
      ["/reports", "Отчеты"]
    ]);
  });

  it("shows the System group only to administrators", () => {
    expect(navigationGroupsFor("MANAGER").map((group) => group.label)).not.toContain("Система");
    expect(navigationGroupsFor("UNKNOWN").map((group) => group.label)).not.toContain("Система");

    const systemGroup = navigationGroupsFor("ADMIN").find((group) => group.label === "Система");
    expect(systemGroup?.items.map((item) => item.label)).toEqual(["Качество данных", "Настройки"]);
  });
});
