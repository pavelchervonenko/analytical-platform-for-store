import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { PlanPage, PlanSettingsPage, ShiftsPage } from "./PlanSchedulePage";

vi.mock("./PlanPanel", () => ({
  PlanPanel: () => <div>Содержимое плана</div>
}));

vi.mock("./PlanSettingsPanel", () => ({
  PlanSettingsPanel: () => <div>Настройки плана</div>
}));

vi.mock("./SchedulePanel", () => ({
  SchedulePanel: () => <div>Содержимое смен</div>
}));

function renderRoutes(entry: string) {
  render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path="/plan" element={<PlanPage />} />
        <Route path="/plan/settings" element={<PlanSettingsPage />} />
        <Route path="/shifts" element={<ShiftsPage />} />
      </Routes>
    </MemoryRouter>
  );
}

describe("planning routes", () => {
  it("renders plan and shifts as independent pages", () => {
    renderRoutes("/plan?store=store-1&month=2026-09");
    expect(screen.getByRole("heading", { name: /^План$/u })).toBeInTheDocument();
    expect(screen.getByText("Содержимое плана")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Обзор плана" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Настройка плана" })).toHaveAttribute(
      "href",
      "/plan/settings?store=store-1&month=2026-09"
    );
  });

  it("opens plan settings as an independent route and preserves workspace parameters", () => {
    renderRoutes("/plan/settings?store=store-1&month=2026-09");

    expect(screen.getByRole("heading", { name: /^План$/u })).toBeInTheDocument();
    expect(screen.getByText("Настройки плана")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Настройка плана" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Обзор плана" })).toHaveAttribute(
      "href",
      "/plan?store=store-1&month=2026-09"
    );
  });

  it("renders the shifts route without a section tab", () => {
    renderRoutes("/shifts?store=store-1&month=2026-09");
    expect(screen.getByRole("heading", { name: /^Смены$/u })).toBeInTheDocument();
    expect(screen.getByText("Содержимое смен")).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Разделы плана" })).not.toBeInTheDocument();
  });

  it("redirects an old shifts bookmark to the new route", async () => {
    renderRoutes("/plan?store=store-1&month=2026-09&section=shifts");
    expect(await screen.findByRole("heading", { name: /^Смены$/u })).toBeInTheDocument();
    expect(screen.getByText("Содержимое смен")).toBeInTheDocument();
  });
});
