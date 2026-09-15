import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiClientError } from "../api/client";
import type { PerformancePlan } from "../api/contracts";
import type * as Queries from "../api/queries";
import { PlanSettingsPanel } from "./PlanSettingsPanel";

const apiMocks = vi.hoisted(() => ({
  getPerformancePlan: vi.fn(),
  upsertPerformancePlan: vi.fn()
}));

vi.mock("../api/queries", async (importOriginal) => {
  const actual = await importOriginal<typeof Queries>();
  return {
    ...actual,
    getPerformancePlan: apiMocks.getPerformancePlan,
    upsertPerformancePlan: apiMocks.upsertPerformancePlan
  };
});

vi.mock("../stores/WorkspaceProvider", () => ({
  useWorkspace: () => ({
    selectedStore: {
      id: "10000000-0000-4000-8000-000000000001",
      name: "Магазин",
      timezone: "Europe/Moscow"
    },
    month: "2026-09"
  })
}));

const plan: PerformancePlan = {
  id: "10000000-0000-4000-8000-000000000010",
  storeId: "10000000-0000-4000-8000-000000000001",
  planMonth: "2026-09",
  revenueTarget: 75_000_000,
  accessoryShareTarget: 5.7,
  serviceShareTarget: 4.8,
  additionalShareTarget: 10.5,
  updatedBy: "20000000-0000-4000-8000-000000000001",
  version: 1,
  updatedAt: "2026-09-01T06:00:00Z"
};

const currentResource = { value: plan, etag: '"plan-v1"' };

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } }
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <PlanSettingsPanel />
    </QueryClientProvider>
  );
}

describe("PlanSettingsPanel", () => {
  beforeEach(() => {
    apiMocks.getPerformancePlan.mockReset().mockResolvedValue(currentResource);
    apiMocks.upsertPerformancePlan.mockReset();
  });

  it("saves all four goals and keeps additional revenue independent", async () => {
    const user = userEvent.setup();
    const savedPlan = { ...plan, additionalShareTarget: 12.3, version: 2 };
    apiMocks.upsertPerformancePlan.mockResolvedValue({ value: savedPlan, etag: '"plan-v2"' });
    renderPanel();

    await user.click(await screen.findByRole("button", { name: "Изменить цели" }));
    const additionalInput = screen.getByLabelText(/Доля доп\. выручки/u);
    await user.clear(additionalInput);
    await user.type(additionalInput, "12,3");
    await user.click(screen.getByRole("button", { name: "Сохранить изменения" }));

    await waitFor(() => expect(apiMocks.upsertPerformancePlan).toHaveBeenCalledWith(
      plan.storeId,
      "2026-09",
      {
        revenueTarget: 75_000_000,
        accessoryShareTarget: 5.7,
        serviceShareTarget: 4.8,
        additionalShareTarget: 12.3
      },
      currentResource
    ));
    expect(await screen.findByText("12,3%")).toBeInTheDocument();
  });

  it("keeps the editor open and explains a version conflict", async () => {
    const user = userEvent.setup();
    apiMocks.upsertPerformancePlan.mockRejectedValue(new ApiClientError("Conflict", {
      status: 412,
      code: "PRECONDITION_FAILED"
    }));
    renderPanel();

    await user.click(await screen.findByRole("button", { name: "Изменить цели" }));
    await user.click(screen.getByRole("button", { name: "Сохранить изменения" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "План уже изменён другим пользователем. Загружена актуальная версия. Проверьте значения повторно."
    );
    expect(screen.getByRole("button", { name: "Сохранить изменения" })).toBeInTheDocument();
    await waitFor(() => expect(apiMocks.getPerformancePlan).toHaveBeenCalledTimes(2));
  });
});
