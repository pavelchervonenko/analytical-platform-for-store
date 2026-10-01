import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiClientError } from "../api/client";
import * as api from "./caseApi";
import { CaseReview } from "./CaseReview";

vi.mock("./caseApi", async (original) => ({
  ...await original<typeof api>(), getCaseQueue: vi.fn(), getCaseDetail: vi.fn(),
  getCaseEstimates: vi.fn(), previewCaseDecision: vi.fn(), saveCaseDecision: vi.fn()
}));

const store = "10000000-0000-4000-8000-000000000001";
const sourceId = "10000000-0000-4000-8000-000000000002";
const item: api.CaseAttachItem = {
  id: sourceId, productId: sourceId, productCode: "39", name: "Чехол Keephone X-Crystal",
  documentId: sourceId, documentNumber: "42", businessDate: "2026-09-10", quantity: 1,
  proposedTarget: "CONFLICT", hasIphone: true, hasSamsung: true,
  decisionTarget: null, decisionCurrent: false, revision: 0, fingerprint: "fp-1", categoryCode: "OTHER_CASE",
  allowedTargets: ["CASE_APPLE_IPHONE", "CASE_SAMSUNG", "CASE_OTHER_DEVICE", "DEFER"]
};
const detail: api.CaseAttachDetail = { item, history: [], affectedDates: ["2026-09-10"] };

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.getCaseQueue).mockResolvedValue({ items: [item], total: 1, openCount: 1,
    conflictCount: 1, openQuantity: 1, offset: 0, limit: 30 });
  vi.mocked(api.getCaseDetail).mockResolvedValue({ value: detail, etag: '"case-v1"' });
  vi.mocked(api.getCaseEstimates).mockResolvedValue({ periodStart: "2026-09-01", periodEnd: "2026-09-30",
    conflictCount: 1, unresolvedCount: 0, unresolvedReturnCount: 0, rates: [
      { metricCode: "CASE_APPLE_IPHONE", confirmedQuantity: 10, inferredQuantity: 2,
        denominatorQuantity: 20, confirmedRatePerHundred: 50, indicativeRatePerHundred: 60 },
      { metricCode: "CASE_SAMSUNG", confirmedQuantity: 4, inferredQuantity: 0,
        denominatorQuantity: 10, confirmedRatePerHundred: 40, indicativeRatePerHundred: 40 }
    ] });
  vi.mocked(api.previewCaseDecision).mockResolvedValue({ previousTarget: null,
    nextTarget: "CASE_APPLE_IPHONE", netQuantity: 1, affectedDates: ["2026-09-10"],
    warnings: ["В чеке есть iPhone и Samsung."] });
  vi.mocked(api.saveCaseDecision).mockResolvedValue({ value: detail, etag: '"case-v2"' });
});

async function open() {
  const user = userEvent.setup();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><CaseReview storeId={store}
    periodStart="2026-09-01" periodEnd="2026-09-30" /></QueryClientProvider>);
  await user.click(await screen.findByRole("button", { name: /Чехол Keephone X-Crystal/ }));
  await screen.findByRole("heading", { name: /Чехол Keephone X-Crystal/ });
  return { user, client };
}

describe("case attach review", () => {
  it.each([
    ["GLASS_PHONE_UNRESOLVED", "GLASS_IPHONE"],
    ["PROTECTIVE_FILM", "FILM_PHONE"],
    ["CASE_UNIVERSAL", "CASE_APPLE_IPHONE"],
    ["CHARGER_CABLE", "ACCESSORY_APPLE_WATCH"],
    ["ACCESSORY_AIRPODS", "ACCESSORY_AIRPODS"],
    ["ACCESSORY_APPLE_WATCH", "NO_ATTACH"]
  ] as const)("restricts %s to its own attach targets", async (categoryCode, target) => {
    vi.mocked(api.getCaseDetail).mockResolvedValue({ value: { ...detail, item: { ...item, categoryCode, allowedTargets: [target, "DEFER"] } }, etag: '"typed-v1"' });
    const { user } = await open();
    await user.selectOptions(screen.getByLabelText("Решение"), target);
    if (categoryCode !== "CASE_UNIVERSAL") {
      expect(screen.queryByRole("option", { name: "Чехол iPhone" })).not.toBeInTheDocument();
    }
    expect(screen.getByLabelText("Решение")).toHaveValue(target);
  });

  it("reports committed save separately from a failed metrics refresh and retries only refresh", async () => {
    const { user, client } = await open();
    const invalidate = vi.spyOn(client, "invalidateQueries").mockRejectedValueOnce(new Error("Refresh unavailable"));
    await user.type(screen.getByLabelText("Что нужно уточнить"), "Проверить маркировку");
    await user.click(screen.getByRole("button", { name: "Проверить результат" }));
    await user.click(await screen.findByRole("button", { name: "Сохранить решение" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Решение сохранено, но не все показатели");
    await waitFor(() => expect(screen.getByRole("button", { name: "Сохранить решение" })).toBeDisabled());
    invalidate.mockResolvedValueOnce();
    await user.click(screen.getByRole("button", { name: "Обновить показатели" }));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    expect(api.saveCaseDecision).toHaveBeenCalledTimes(1);
  });

  it("keeps receipt inference separate and requires an evidenced decision", async () => {
    const { user, client } = await open();
    expect(screen.getByText(/с предположением: 60%/)).toBeVisible();
    expect(screen.getAllByText(/Конфликт: оба бренда/)).toHaveLength(2);
    expect(screen.getByRole("button", { name: "Проверить результат" })).toBeDisabled();
    await user.selectOptions(screen.getByLabelText("Решение"), "CASE_APPLE_IPHONE");
    await user.type(screen.getByLabelText(/Основание: маркировка/), "На упаковке указана модель iPhone 15 Pro");
    await user.click(screen.getByRole("button", { name: "Проверить результат" }));
    await screen.findByRole("button", { name: "Сохранить решение" });
    expect(api.previewCaseDecision).toHaveBeenCalledWith(store, sourceId, {
      targetCode: "CASE_APPLE_IPHONE", reason: "На упаковке указана модель iPhone 15 Pro"
    });
    const invalidate = vi.spyOn(client, "invalidateQueries");
    await user.click(screen.getByRole("button", { name: "Сохранить решение" }));
    await waitFor(() => expect(api.saveCaseDecision).toHaveBeenCalledWith(store, sourceId,
      '"case-v1"', expect.objectContaining({ targetCode: "CASE_APPLE_IPHONE" })));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["stores", store] }, { throwOnError: true });
  });

  it("requires a fresh review after an optimistic concurrency conflict", async () => {
    vi.mocked(api.saveCaseDecision).mockRejectedValueOnce(new ApiClientError("Изменено", {
      status: 412, code: "PRECONDITION_FAILED"
    }));
    const { user } = await open();
    await user.type(screen.getByLabelText("Что нужно уточнить"), "Проверить артикул поставщика");
    await user.click(screen.getByRole("button", { name: "Проверить результат" }));
    await user.click(await screen.findByRole("button", { name: "Сохранить решение" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Данные изменились");
    expect(screen.getByRole("button", { name: "Сохранить решение" })).toBeDisabled();
  });

  it("warns when a return cannot be linked to the original case sale", async () => {
    const estimate = await api.getCaseEstimates(store, "2026-09-01", "2026-09-30");
    vi.mocked(api.getCaseEstimates).mockResolvedValue({ ...estimate, unresolvedReturnCount: 2 });
    await open();
    expect(screen.getByRole("alert")).toHaveTextContent("Возвратов аксессуаров без надёжной связи");
    expect(screen.getByRole("alert")).toHaveTextContent("2");
  });
});
