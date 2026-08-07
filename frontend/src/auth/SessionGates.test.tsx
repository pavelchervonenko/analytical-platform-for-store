import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { AdminGate } from "./SessionGates";

const auth = vi.hoisted(() => ({ role: "MANAGER" }));

vi.mock("./AuthProvider", () => ({
  useAuth: () => ({ user: { role: auth.role } })
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
      </Route>
    </Routes>
  );
}

describe("AdminGate", () => {
  it("redirects a manager before rendering the protected route and keeps workspace params", async () => {
    auth.role = "MANAGER";
    render(<MemoryRouter initialEntries={["/admin?store=store-1&month=2026-07"]}><AppRoutes /></MemoryRouter>);

    expect(await screen.findByText("overview?store=store-1&month=2026-07")).toBeInTheDocument();
    expect(screen.queryByText("admin")).not.toBeInTheDocument();
  });

  it("renders the protected route for an administrator", async () => {
    auth.role = "ADMIN";
    render(<MemoryRouter initialEntries={["/admin"]}><AppRoutes /></MemoryRouter>);

    expect(await screen.findByText("admin")).toBeInTheDocument();
    expect(screen.queryByText(/^overview/u)).not.toBeInTheDocument();
  });
});
