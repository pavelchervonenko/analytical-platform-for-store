import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiClientError } from "../api/client";
import * as api from "./api";
import * as caseApi from "./caseApi";
import { WarrantyPanel } from "./WarrantyPanel";

vi.mock("./api", async (original) => ({
  ...await original<typeof api>(),
  getWarrantyQueue: vi.fn(), getWarrantyDetail: vi.fn(), previewWarrantyDecision: vi.fn(),
  saveWarrantyDecision: vi.fn(), searchWarrantyDevices: vi.fn(), searchOriginalWarranties: vi.fn()
}));
vi.mock("./caseApi", async (original) => ({
  ...await original<typeof caseApi>(), getCaseQueue: vi.fn(), getCaseDetail: vi.fn(),
  getCaseEstimates: vi.fn(), previewCaseDecision: vi.fn(), saveCaseDecision: vi.fn()
}));
const store = "10000000-0000-4000-8000-000000000001";
const sourceId = "10000000-0000-4000-8000-000000000002";
const usedId = "10000000-0000-4000-8000-000000000003";
const newId = "10000000-0000-4000-8000-000000000004";
const source: api.WarrantyCase = {
  id: sourceId, documentId: sourceId, documentExternalId: "doc-42", documentNumber: "42",
  businessDate: "2026-09-10", documentKind: "SALE", name: "Гарантия Check", quantity: 3,
  state: "CONFLICT", conflictCode: "MIXED_DEVICE_TYPES", fingerprint: "source-v1", revision: 0,
  originalWarrantyItemId: null, financialEmployeeName: "Продавец гарантии"
};
const device = (id: string, name: string, deviceType: "USED" | "NEW"): api.WarrantyDevice => ({
  id, name, deviceType, documentId: id, documentExternalId: `doc-${id}`, documentNumber: "41",
  businessDate: "2026-08-10", employeeId: store, employeeName: "Продавец устройства",
  quantity: deviceType === "USED" ? 2 : 1, fingerprint: `fp-${id}`, allocatedQuantity: 0, returnedQuantity: 0
});
const detail: api.WarrantyDetail = { warranty: source, candidates: [device(usedId, "iPhone Б/У", "USED"),
  device(newId, "Samsung новый", "NEW")], allocations: [], history: [], warnings: [] };

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute("open", ""); };
});
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.getWarrantyQueue).mockResolvedValue({ items: [source], total: 1, documentCount: 1,
    unallocatedQuantity: 3, offset: 0, limit: 30, enabled: true });
  vi.mocked(caseApi.getCaseQueue).mockResolvedValue({ items: [], total: 0, openCount: 0,
    conflictCount: 0, openQuantity: 0, offset: 0, limit: 30 });
  vi.mocked(api.getWarrantyDetail).mockResolvedValue({ value: detail, etag: '"v1"' });
  vi.mocked(api.previewWarrantyDecision).mockImplementation(async (_store, id, command) => ({
    sourceItemId: id, action: command.action, affectedDates: ["2026-08-10"], warnings: [],
    allocations: command.allocations.map((a) => ({ deviceItemId: a.deviceItemId,
      deviceDocumentId: a.deviceItemId, deviceType: a.deviceItemId === usedId ? "USED" : "NEW",
      businessDate: "2026-08-10", employeeId: store, quantity: a.quantity }))
  }));
  vi.mocked(api.saveWarrantyDecision).mockResolvedValue({ value: detail, etag: '"v2"' });
});
async function open() {
  const user = userEvent.setup();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><WarrantyPanel storeId={store}
    periodStart="2026-09-01" periodEnd="2026-09-30" /></QueryClientProvider>);
  await user.click(await screen.findByRole("button", { name: "Разобрать конфликты" }));
  await user.click(await screen.findByRole("button", { name: /Продажа №42/ }));
  await screen.findByRole("heading", { name: "Гарантия Check 3 шт." });
  expect(screen.queryByText("Нужна проверка")).not.toBeInTheDocument();
  expect(screen.getByText("Продавец: Продавец гарантии")).toBeVisible();
  expect(screen.getByRole("heading", { name: /Почему нужна проверка/ })).toBeVisible();
  expect(screen.getByRole("heading", { name: /Укажите устройство и количество/ })).toBeVisible();
  expect(screen.getByRole("heading", { name: /Проверьте результат и сохраните/ })).toBeVisible();
  return { user, client };
}
async function allocate() {
  const result = await open();
  await result.user.type(screen.getByRole("spinbutton", { name: /iPhone Б\/У/ }), "2");
  expect(screen.getByRole("button", { name: "Проверить результат" })).toBeDisabled();
  await result.user.type(screen.getByRole("spinbutton", { name: /Samsung новый/ }), "1");
  await result.user.click(screen.getByRole("button", { name: "Проверить результат" }));
  await screen.findByRole("button", { name: "Сохранить решение" });
  return result;
}

describe("warranty review", () => {
  it("does not repeat a committed decision when refreshing metrics fails", async () => {
    const { user, client } = await allocate();
    const invalidate = vi.spyOn(client, "invalidateQueries").mockRejectedValueOnce(new Error("Refresh unavailable"));
    await user.click(screen.getByRole("button", { name: "Сохранить решение" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Решение сохранено, но не все показатели");
    await waitFor(() => expect(screen.getByRole("button", { name: "Сохранить решение" })).toBeDisabled());
    invalidate.mockResolvedValueOnce();
    await user.click(screen.getByRole("button", { name: "Обновить показатели" }));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    expect(api.saveWarrantyDecision).toHaveBeenCalledTimes(1);
  }, 15000);

  it("requires all quantities, previews attribution and invalidates the store after saving", async () => {
    const { user, client } = await allocate();
    const invalidate = vi.spyOn(client, "invalidateQueries");
    expect(screen.getByLabelText("Результат решения")).toHaveTextContent("Гарантия Б/У: +2");
    expect(screen.getByLabelText("Результат решения")).toHaveTextContent("Продавец: Продавец устройства");
    expect(api.previewWarrantyDecision).toHaveBeenCalledWith(store, sourceId, expect.objectContaining({
      reason: "Связь подтверждена выбором устройств"
    }));
    await user.click(screen.getByRole("button", { name: "Сохранить решение" }));
    await screen.findByRole("status");
    expect(api.saveWarrantyDecision).toHaveBeenCalledWith(store, sourceId, '"v1"', expect.objectContaining({
      allocations: [expect.objectContaining({ deviceItemId: usedId, quantity: 2 }),
        expect.objectContaining({ deviceItemId: newId, quantity: 1 })]
    }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["stores", store] }, { throwOnError: true });
  });

  it("keeps the allocation comment optional and records a custom comment when provided", async () => {
    const { user } = await open();
    await user.click(screen.getByText("Добавить комментарий (необязательно)"));
    await user.type(screen.getByLabelText("Комментарий к решению"), "Проверено по номеру продажи");
    await user.type(screen.getByRole("spinbutton", { name: /iPhone Б\/У/ }), "2");
    await user.type(screen.getByRole("spinbutton", { name: /Samsung новый/ }), "1");
    await user.click(screen.getByRole("button", { name: "Проверить результат" }));
    expect(api.previewWarrantyDecision).toHaveBeenCalledWith(store, sourceId, expect.objectContaining({
      reason: "Проверено по номеру продажи"
    }));
  });

  it("requires an exclusion reason and clears the device allocation", async () => {
    const { user } = await open();
    await user.click(screen.getByRole("button", { name: /Другие действия/ }));
    await user.click(screen.getByRole("button", { name: "Исключить из показателя" }));
    expect(screen.getByRole("button", { name: "Проверить результат" })).toBeDisabled();
    await user.type(screen.getByLabelText("Причина исключения (обязательно)"), "Гарантия для iPad");
    await user.click(screen.getByRole("button", { name: "Проверить результат" }));
    await screen.findByRole("button", { name: "Сохранить решение" });
    expect(api.previewWarrantyDecision).toHaveBeenCalledWith(store, sourceId, expect.objectContaining({
      action: "EXCLUDE", reason: "Гарантия для iPad", allocations: []
    }));
  });

  it("keeps a deferred case unresolved and asks what must be clarified", async () => {
    const { user } = await open();
    await user.click(screen.getByRole("button", { name: /Другие действия/ }));
    await user.click(screen.getByRole("button", { name: "Отложить до уточнения" }));
    expect(screen.getByRole("heading", { name: /Что нужно уточнить/ })).toBeVisible();
    expect(screen.getByRole("button", { name: "Проверить результат" })).toBeDisabled();
    await user.type(screen.getByLabelText("Что нужно уточнить (обязательно)"), "Запросить номер исходной продажи");
    await user.click(screen.getByRole("button", { name: "Проверить результат" }));
    expect(await screen.findByLabelText("Результат решения")).toHaveTextContent("останется в очереди");
  });

  it("reloads a stale target and requires another review even when the source ETag is unchanged", async () => {
    vi.mocked(api.saveWarrantyDecision).mockRejectedValueOnce(new ApiClientError("Изменено", { status: 412, code: "PRECONDITION_FAILED" }));
    const { user } = await allocate();
    await user.click(screen.getByRole("button", { name: "Сохранить решение" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Данные изменились");
    await user.click(screen.getByRole("button", { name: "Обновить карточку" }));
    await waitFor(() => expect(screen.getByRole("spinbutton", { name: /iPhone Б\/У/ })).toHaveValue(null));
    expect(screen.queryByRole("button", { name: "Сохранить решение" })).not.toBeInTheDocument();
  });

  it("invalidates the preview when the allocation changes", async () => {
    const { user } = await allocate();
    await user.clear(screen.getByRole("spinbutton", { name: /Samsung новый/ }));
    expect(screen.queryByLabelText("Результат решения")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Проверить результат" })).toBeDisabled();
  });
  it("keeps the review accessible when only resolved cases remain", async () => {
    vi.mocked(api.getWarrantyQueue).mockResolvedValue({ items: [], total: 0, documentCount: 0,
      unallocatedQuantity: 0, offset: 0, limit: 30, enabled: false });
    vi.mocked(caseApi.getCaseQueue).mockResolvedValue({ items: [], total: 1, openCount: 0,
      conflictCount: 0, openQuantity: 0, offset: 0, limit: 30 });
    vi.mocked(caseApi.getCaseEstimates).mockResolvedValue({ periodStart: "2026-09-01",
      periodEnd: "2026-09-30", conflictCount: 0, unresolvedCount: 0,
      unresolvedReturnCount: 0, rates: [] });
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><WarrantyPanel storeId={store}
      periodStart="2026-09-01" periodEnd="2026-09-30" /></QueryClientProvider>);
    await user.click(await screen.findByRole("button", { name: "Разобрать конфликты" }));
    expect(screen.getByRole("tab", { name: "Аксессуары" })).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByRole("tab", { name: "Гарантии" })).not.toBeInTheDocument();
    expect(caseApi.getCaseQueue).toHaveBeenCalledWith(store, "ALL");
  });
  it("keeps warranty review usable when the case queue fails", async () => {
    vi.mocked(caseApi.getCaseQueue).mockRejectedValue(new Error("Case queue unavailable"));
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><WarrantyPanel storeId={store}
      periodStart="2026-09-01" periodEnd="2026-09-30" /></QueryClientProvider>);
    expect(await screen.findByRole("alert")).toHaveTextContent("Очередь аксессуаров временно недоступна");
    await user.click(screen.getByRole("button", { name: "Разобрать конфликты" }));
    expect(await screen.findByRole("heading", { name: "Выберите гарантию из списка" })).toBeVisible();
    expect(screen.getByRole("tab", { name: "Гарантии" })).toHaveAttribute("aria-selected", "true");
  });

  it("keeps case review usable when the warranty queue fails", async () => {
    vi.mocked(api.getWarrantyQueue).mockRejectedValue(new Error("Warranty queue unavailable"));
    vi.mocked(caseApi.getCaseQueue).mockResolvedValue({ items: [], total: 1, openCount: 1,
      conflictCount: 0, openQuantity: 1, offset: 0, limit: 30 });
    vi.mocked(caseApi.getCaseEstimates).mockResolvedValue({ periodStart: "2026-09-01",
      periodEnd: "2026-09-30", conflictCount: 0, unresolvedCount: 0,
      unresolvedReturnCount: 0, rates: [] });
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><WarrantyPanel storeId={store}
      periodStart="2026-09-01" periodEnd="2026-09-30" /></QueryClientProvider>);
    expect(await screen.findByRole("alert")).toHaveTextContent("Очередь гарантий временно недоступна");
    await user.click(screen.getByRole("button", { name: "Разобрать конфликты" }));
    expect(await screen.findByRole("heading", { name: "Выберите аксессуар" })).toBeVisible();
    expect(screen.getByRole("tab", { name: "Аксессуары" })).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByRole("tab", { name: "Гарантии" })).not.toBeInTheDocument();
  });
});
