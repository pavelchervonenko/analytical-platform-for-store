import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getWeeklyReview } from "../api/queries";
import type { WeeklyReview } from "../api/weeklyReviewContract";
import { makeWeeklyReview } from "../test/weeklyReviewFixture";
import { WeeklyReviewView } from "./WeeklyReviewView";

vi.mock("../api/queries", () => ({
  getWeeklyReview: vi.fn(),
  queryKeys: {
    weeklyReview: (storeId: string) => ["stores", storeId, "weekly-reviews", "current"]
  }
}));

const getWeeklyReviewMock = vi.mocked(getWeeklyReview);

function renderView(fallback?: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } }
  });
  return render(
    <QueryClientProvider client={client}>
      <WeeklyReviewView storeId="store-1" fallback={fallback} />
    </QueryClientProvider>
  );
}

function renderReview(review: WeeklyReview | null) {
  getWeeklyReviewMock.mockResolvedValue(review);
  return renderView();
}

function partialReview(): WeeklyReview {
  const review = makeWeeklyReview();
  review.reportState = "PARTIAL";
  review.summary.state = "LIMITED";
  review.qualitySummary = {
    blockingCount: 0,
    warningCount: 1,
    affectedBlockCount: 1,
    message: "По возвратам доступны не все данные."
  };
  const returns = review.sourceCoverage.find((source) => source.sourceCode === "RETURNS")!;
  returns.state = "PARTIAL";
  returns.message = "Возвраты загружены не за всю неделю.";
  return review;
}

describe("WeeklyReviewView", () => {
  beforeEach(() => {
    getWeeklyReviewMock.mockReset();
  });

  it("shows a concise manager-first READY review from the backend golden response", async () => {
    const review = makeWeeklyReview();
    renderReview(review);

    expect(await screen.findByText("17–23 августа 2026", {
      selector: ".weekly-review-header__period strong"
    })).toBeInTheDocument();
    expect(screen.getByText("17–23 августа 2026", {
      selector: ".weekly-review-section-heading small"
    })).toBeInTheDocument();
    const managerSummary = "По сравнению с предыдущей полной неделей чистая выручка выросла, "
      + "валовая прибыль выросла, а маржа осталась на прежнем уровне. "
      + "Рост возвратов уменьшил чистую выручку на 50\u00a0₽.";
    expect(screen.getByRole("heading", { name: managerSummary })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Следующие шаги" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Команда" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Сотрудники" })).toBeInTheDocument();
    expect(screen.queryByText(/план месяца/iu)).not.toBeInTheDocument();

    const summary = document.querySelector<HTMLElement>(".weekly-review-summary")!;
    expect(within(summary).getByText("Выручка направления «Техника» выросла"))
      .toBeInTheDocument();
    expect(within(summary).getByText("Связь с общим ростом пока не установлена."))
      .toBeInTheDocument();
    expect(within(summary).getByText("Возвраты выросли")).toBeInTheDocument();
    expect(within(summary).getByText(
      "Это изменение напрямую уменьшило чистую выручку."
    )).toBeInTheDocument();
    expect(within(summary).getByText("Что сделать")).toBeInTheDocument();
    expect(within(summary).getByText("Разобрать рост возвратов"))
      .toBeInTheDocument();
    expect(within(summary).queryByText(review.summary.positive!.text)).not.toBeInTheDocument();
    expect(within(summary).queryByText(review.summary.risk!.text)).not.toBeInTheDocument();
  });

  it("keeps employee details separate and collapsed until requested", async () => {
    renderReview(makeWeeklyReview());

    const employeeName = await screen.findByText("Анна");
    const employeeCard = employeeName.closest("details");
    expect(employeeCard).not.toHaveAttribute("open");

    fireEvent.click(employeeCard!.querySelector("summary")!);

    expect(employeeCard).toHaveAttribute("open");
    expect(within(employeeCard!).getByRole("heading", { name: "Динамика" }))
      .toBeInTheDocument();
    expect(within(employeeCard!).getAllByText("Чистая выручка вырос")).toHaveLength(1);
  });

  it("opens formula, evidence and sales structure without losing their context", async () => {
    const user = userEvent.setup();
    renderReview(makeWeeklyReview());

    const formulaSummary = (await screen.findByText("Как рассчитана чистая выручка"))
      .closest("summary")!;
    const formula = formulaSummary.closest("details")!;
    await user.click(formulaSummary);
    expect(formula).toHaveAttribute("open");
    expect(within(formula).getByText("Продажи")).toBeInTheDocument();
    expect(within(formula).getByText("Возвраты")).toBeInTheDocument();

    const evidenceSummary = screen.getAllByText("Основание")[0]!.closest("summary")!;
    const evidence = evidenceSummary.closest("details")!;
    await user.click(evidenceSummary);
    expect(evidence).toHaveAttribute("open");

    const structureSummary = screen.getByText("Структура продаж").closest("summary")!;
    const structure = structureSummary.closest("details")!;
    await user.click(structureSummary);
    expect(structure).toHaveAttribute("open");
    expect(within(structure).getByText("Техника")).toBeInTheDocument();
    expect(within(structure).getByText("Дополнительная выручка")).toBeInTheDocument();
  });

  it("keeps a long employee roster compact until the manager expands it", async () => {
    const user = userEvent.setup();
    const review = makeWeeklyReview();
    const template = review.employees[0]!;
    review.employees = [
      ...review.employees,
      ...Array.from({ length: 6 }, (_, index) => ({
        ...template,
        employeePublicId: `employee-extra-${index + 1}`,
        displayName: `Сотрудник ${index + 4}`
      }))
    ];
    const { container } = renderReview(review);

    await screen.findByText("Анна");
    expect(container.querySelectorAll(".weekly-review-employee")).toHaveLength(8);

    await user.click(screen.getByRole("button", { name: "Показать всех — 9" }));
    expect(container.querySelectorAll(".weekly-review-employee")).toHaveLength(9);
    expect(screen.getByRole("button", { name: "Показать меньше" })).toBeInTheDocument();
  });

  it("keeps valid PARTIAL values and marks locally limited blocks", async () => {
    const review = partialReview();
    review.salesStructure.state = "LIMITED";
    review.team.state = "LIMITED";
    renderReview(review);

    expect(await screen.findByText("Есть ограничения")).toBeInTheDocument();
    expect(screen.getByText("По возвратам доступны не все данные.")).toBeInTheDocument();
    expect(screen.getAllByText("Данные ограничены").length).toBeGreaterThanOrEqual(3);
    expect(screen.getAllByText(/1[\s\u00a0]000[\s\u00a0]₽/u).length).toBeGreaterThan(0);
  });

  it("explains a block-only PARTIAL without repeating a stale quality message", async () => {
    const review = makeWeeklyReview();
    review.reportState = "PARTIAL";
    review.summary.state = "LIMITED";
    review.team.state = "LIMITED";
    renderReview(review);

    expect(await screen.findByText("Часть разделов доступна с ограничениями."))
      .toBeInTheDocument();
    expect(screen.queryByText("Данные готовы")).not.toBeInTheDocument();
  });

  it("does not repeat factor narratives after AI wording enrichment", async () => {
    const review = makeWeeklyReview();
    review.summary.generatedBy = "AI_ENHANCED";
    review.aiEnhancement.state = "READY";
    review.aiEnhancement.promptVersion = "weekly-interpretation-v22";
    review.aiEnhancement.contentSchemaVersion = 4;
    review.aiEnhancement.publishedAt = "2026-08-24T04:05:00Z";
    renderReview(review);

    await screen.findByRole("heading", { name: review.summary.outcome!.text });
    const summary = document.querySelector<HTMLElement>(".weekly-review-summary")!;
    expect(within(summary).queryByText(review.summary.positive!.text)).not.toBeInTheDocument();
    expect(within(summary).queryByText(review.summary.risk!.text)).not.toBeInTheDocument();
  });

  it.each([
    ["INSUFFICIENT", "Для этого раздела недостаточно данных."],
    ["NOT_APPLICABLE", "Этот раздел не применяется к выбранной неделе."]
  ] as const)("renders dedicated %s states instead of stale block values", async (state, text) => {
    const review = partialReview();
    review.salesStructure.state = state;
    review.team.state = state;
    renderReview(review);

    await screen.findByText("Есть ограничения");
    fireEvent.click(screen.getByText("Структура продаж").closest("summary")!);
    expect(screen.getAllByText(text).length).toBeGreaterThanOrEqual(2);
  });

  it("keeps empty business sections explicit", async () => {
    const review = makeWeeklyReview();
    review.factors = [];
    review.actions = [];
    review.team.observations = [];
    review.employees = [];
    renderReview(review);

    expect(await screen.findByText("Существенных изменений нет.")).toBeInTheDocument();
    expect(screen.getByText("Дополнительные действия не требуются.")).toBeInTheDocument();
    expect(screen.getByText("Значимых изменений нет.")).toBeInTheDocument();
    expect(screen.getByText("Нет сотрудников с продажами за эту неделю.")).toBeInTheDocument();
  });

  it("shows limited metric context without hiding the value", async () => {
    const review = partialReview();
    review.results[0]!.metricState = "LIMITED";
    renderReview(review);

    expect(await screen.findByText("Данные требуют проверки")).toBeInTheDocument();
    expect(screen.getAllByText(/1[\s\u00a0]000[\s\u00a0]₽/u).length).toBeGreaterThan(0);
  });

  it("shows PREPARING as progress rather than a data failure", async () => {
    const review = makeWeeklyReview();
    review.reportState = "PREPARING";
    review.qualitySummary.message = "Собираем результаты завершённой недели.";
    renderReview(review);

    expect(await screen.findByRole("heading", { name: "Разбор формируется" }))
      .toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Для разбора не хватает данных" }))
      .not.toBeInTheDocument();
  });

  it("announces BLOCKED as an alert", async () => {
    const blocked = makeWeeklyReview();
    blocked.reportState = "BLOCKED";
    blocked.qualitySummary = {
      blockingCount: 1,
      warningCount: 0,
      affectedBlockCount: 1,
      message: "Не загружены продажи за часть недели."
    };
    renderReview(blocked);

    const alert = await screen.findByRole("alert");
    expect(within(alert).getByRole("heading", { name: "Для разбора не хватает данных" }))
      .toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Результаты недели" }))
      .not.toBeInTheDocument();
  });

  it("lets a manager retry an empty response", async () => {
    getWeeklyReviewMock
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(makeWeeklyReview());
    renderView();

    fireEvent.click(await screen.findByRole("button", { name: "Проверить снова" }));

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "Результаты недели" }))
        .toBeInTheDocument();
    });
    expect(getWeeklyReviewMock).toHaveBeenCalledTimes(2);
  });

  it("shows a recoverable network error", async () => {
    getWeeklyReviewMock.mockRejectedValue(new Error("network"));
    renderView();

    const alert = await screen.findByRole("alert");
    expect(within(alert).getByText("Данные временно недоступны")).toBeInTheDocument();
    expect(within(alert).getByRole("button", { name: /Повторить/u })).toBeInTheDocument();
  });

  it("keeps the previous weekly view when the v22 snapshot is not ready", async () => {
    getWeeklyReviewMock.mockResolvedValue(null);
    renderView(<div>Предыдущий недельный разбор</div>);

    expect(await screen.findByText("Предыдущий недельный разбор")).toBeInTheDocument();
    expect(screen.queryByText("Разбор ещё не сформирован")).not.toBeInTheDocument();
  });

  it("keeps the previous weekly view when the v22 endpoint fails", async () => {
    getWeeklyReviewMock.mockRejectedValue(new Error("network"));
    renderView(<div>Предыдущий недельный разбор</div>);

    expect(await screen.findByText("Предыдущий недельный разбор")).toBeInTheDocument();
    expect(screen.queryByText("Данные временно недоступны")).not.toBeInTheDocument();
  });
});
