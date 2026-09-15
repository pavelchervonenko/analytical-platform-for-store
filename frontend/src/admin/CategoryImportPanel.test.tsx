import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CategoryImportPanel } from "./CategoryImportPanel";
import { generateWeeklyReview, importProductCategories } from "./api";

vi.mock("../stores/WorkspaceProvider", () => ({
  useWorkspace: () => ({ month: "2026-09" })
}));

vi.mock("./api", () => ({
  generateWeeklyReview: vi.fn(),
  importProductCategories: vi.fn()
}));

const importMock = vi.mocked(importProductCategories);
const generateMock = vi.mocked(generateWeeklyReview);
const STORE_A = "00000000-0000-4000-8000-000000000001";
const STORE_B = "00000000-0000-4000-8000-000000000002";

function renderPanel() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false }
    }
  });
  const invalidate = vi.spyOn(client, "invalidateQueries");
  render(
    <QueryClientProvider client={client}>
      <CategoryImportPanel />
    </QueryClientProvider>
  );
  return { invalidate };
}

async function submitImport() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Версия правила"), "approved-v1");
  await user.click(screen.getByRole("checkbox"));
  await user.click(screen.getByRole("button", { name: "Проверить и импортировать" }));
}

describe("CategoryImportPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(window, "confirm").mockReturnValue(true);
    importMock.mockResolvedValue({
      requested: 1,
      productsCreated: 0,
      assignmentsCreated: 1,
      assignmentsUnchanged: 0,
      affectedStoreIds: [STORE_A, STORE_B]
    });
    generateMock.mockResolvedValue({});
  });

  it("refreshes every store affected by an analytics import", async () => {
    const { invalidate } = renderPanel();

    await submitImport();

    await waitFor(() => expect(generateMock).toHaveBeenCalledTimes(2));
    expect(generateMock).toHaveBeenCalledWith(STORE_A);
    expect(generateMock).toHaveBeenCalledWith(STORE_B);
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ["data-quality", "summary"]
    });
    expect(screen.queryByText(/ИИ-разбор не обновлён/u)).not.toBeInTheDocument();
  });

  it("shows a working retry when one affected review fails to refresh", async () => {
    importMock.mockResolvedValue({
      requested: 1,
      productsCreated: 0,
      assignmentsCreated: 1,
      assignmentsUnchanged: 0,
      affectedStoreIds: [STORE_A]
    });
    generateMock.mockRejectedValueOnce(new Error("network")).mockResolvedValueOnce({});
    renderPanel();

    await submitImport();

    expect(await screen.findByText(/ИИ-разбор не обновлён для магазинов: 1/u))
      .toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Повторить" }));

    await waitFor(() => expect(generateMock).toHaveBeenCalledTimes(2));
    await waitFor(() => {
      expect(screen.queryByText(/ИИ-разбор не обновлён/u)).not.toBeInTheDocument();
    });
  });
});
