import { describe, expect, it } from "vitest";
import { navigationGroupsFor, navigationSearchFor } from "./AppShell";

describe("application navigation", () => {
  it("shows plan and shifts as separate management destinations", () => {
    const management = navigationGroupsFor("MANAGER", ["PLAN", "SHIFTS", "PAYROLL"])
      .find((group) => group.label === "Управление");

    expect(management?.items.map((item) => [item.to, item.label])).toEqual([
      ["/plan", "План"],
      ["/shifts", "Смены"],
      ["/payroll", "Зарплата"],
      ["/reports", "Отчеты"]
    ]);
  });

  it("hides only the operational sections not granted to a manager", () => {
    const management = navigationGroupsFor("MANAGER", ["SHIFTS"])
      .find((group) => group.label === "Управление");

    expect(management?.items.map((item) => item.label)).toEqual(["Смены", "Отчеты"]);
  });

  it("shows the System group only to administrators", () => {
    expect(navigationGroupsFor("MANAGER").map((group) => group.label)).not.toContain("Система");
    expect(navigationGroupsFor("UNKNOWN").map((group) => group.label)).not.toContain("Система");

    const systemGroup = navigationGroupsFor("ADMIN").find((group) => group.label === "Система");
    expect(systemGroup?.items.map((item) => item.label)).toEqual(["Качество данных", "Настройки"]);
    expect(navigationGroupsFor("ADMIN")
      .find((group) => group.label === "Управление")?.items.map((item) => item.label))
      .toEqual(["План", "Смены", "Зарплата", "Отчеты"]);
  });

  it("opens plan on the anchor month without carrying an analytics range or scope", () => {
    const search = navigationSearchFor(
      "/plan",
      "/overview",
      "?store=store-1&month=2026-08&range=CUSTOM&periodStart=2026-08-15&periodEnd=2026-09-13&overviewScope=STORE",
      "2026-09"
    );

    expect(search).toBe("store=store-1&month=2026-09");
  });

  it("keeps the selected plan scope when navigating inside the plan section", () => {
    expect(navigationSearchFor(
      "/plan",
      "/plan/settings",
      "?store=store-1&month=2026-09&planScope=STORE",
      "2026-09"
    )).toBe("store=store-1&month=2026-09&planScope=STORE");
  });
});
