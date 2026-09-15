import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { AdminGate, FeatureGate } from "./SessionGates";

const auth = vi.hoisted(() => ({ role: "MANAGER", features: [] as string[] }));

vi.mock("./AuthProvider", () => ({
  useAuth: () => ({ user: { role: auth.role, features: auth.features } })
}));

function OverviewRoute() {
  const location = useLocation();
  return <div>overview{location.search}</div>;
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/overview" element={<OverviewRoute />} />
      <Route element={<AdminGate />}>
        <Route path="/admin" element={<div>admin</div>} />
        <Route path="/quality" element={<div>quality</div>} />
      </Route>
      <Route element={<FeatureGate feature="PLAN" />}>
        <Route path="/plan" element={<div>plan</div>} />
      </Route>
    </Routes>
  );
}

describe("AdminGate", () => {
  it("redirects a manager before rendering the protected route and keeps workspace params", async () => {
    auth.role = "MANAGER";
    auth.features = [];
    render(<MemoryRouter initialEntries={["/admin?store=store-1&month=2026-07"]}><AppRoutes /></MemoryRouter>);

    expect(await screen.findByText("overview?store=store-1&month=2026-07")).toBeInTheDocument();
    expect(screen.queryByText("admin")).not.toBeInTheDocument();
  });

  it("renders the protected route for an administrator", async () => {
    auth.role = "ADMIN";
    auth.features = [];
    render(<MemoryRouter initialEntries={["/admin"]}><AppRoutes /></MemoryRouter>);

    expect(await screen.findByText("admin")).toBeInTheDocument();
    expect(screen.queryByText(/^overview/u)).not.toBeInTheDocument();
  });

  it("does not let a manager open data quality by URL", async () => {
    auth.role = "MANAGER";
    auth.features = [];
    render(<MemoryRouter initialEntries={["/quality?store=store-1&month=2026-07"]}><AppRoutes /></MemoryRouter>);

    expect(await screen.findByText("overview?store=store-1&month=2026-07")).toBeInTheDocument();
    expect(screen.queryByText("quality")).not.toBeInTheDocument();
  });

  it("redirects a manager without the requested feature and keeps workspace params", async () => {
    auth.role = "MANAGER";
    auth.features = ["SHIFTS"];
    render(<MemoryRouter initialEntries={["/plan?store=store-1&month=2026-07"]}><AppRoutes /></MemoryRouter>);

    expect(await screen.findByText("overview?store=store-1&month=2026-07")).toBeInTheDocument();
    expect(screen.queryByText("plan")).not.toBeInTheDocument();
  });

  it("renders a feature route for a granted manager and an administrator", async () => {
    auth.role = "MANAGER";
    auth.features = ["PLAN"];
    const manager = render(<MemoryRouter initialEntries={["/plan"]}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByText("plan")).toBeInTheDocument();
    manager.unmount();

    auth.role = "ADMIN";
    auth.features = [];
    render(<MemoryRouter initialEntries={["/plan"]}><AppRoutes /></MemoryRouter>);
    expect(await screen.findByText("plan")).toBeInTheDocument();
  });
});
