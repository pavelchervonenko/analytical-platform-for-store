import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
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

function renderView(
  fallback?: ReactNode,
  initialReview?: WeeklyReview | null,
  qualityHref: string | null = null
) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } }
  });
  if (initialReview !== undefined) {
    client.setQueryData(
      ["stores", "store-1", "weekly-reviews", "current"],
      initialReview
    );
  }
  return render(
    <MemoryRouter initialEntries={["/insights?store=store-1"]}>
      <QueryClientProvider client={client}>
        <WeeklyReviewView
          storeId="store-1"
          qualityHref={qualityHref}
          fallback={fallback}
        />
      </QueryClientProvider>
    </MemoryRouter>
  );
}

function renderReview(review: WeeklyReview | null, qualityHref: string | null = null) {
  getWeeklyReviewMock.mockResolvedValue(review);
  return renderView(undefined, undefined, qualityHref);
}

function limitation(review: WeeklyReview): WeeklyReview["limitations"][number] {
  return {
    limitationId: "returns-partial",
    code: "RETURNS_PARTIAL",
    severity: "WARNING",
    scope: "METRIC",
    employeePublicId: null,
    affectedBlockIds: ["summary", "results"],
    affectedMetricCodes: ["NET_REVENUE"],
    period: structuredClone(review.period.current),
    affectedCount: 1,
    summary: "Возвраты загружены не за всю неделю.",
    resolution: "Проверьте синхронизацию возвратов.",
    evidenceRefs: ["STORE.RETURN_REVENUE"]
  };
}

function partialReview(): WeeklyReview {
  const review = makeWeeklyReview();
  review.reportState = "PARTIAL";
  review.summary.state = "LIMITED";
  review.qualitySummary = {
    blockingCount: 0,
    warningCount: 1,
    affectedBlockCount: 2,
    message: "По возвратам доступны не все данные."
  };
  review.limitations = [limitation(review)];
  return review;
}

function addAttentionEmployees(review: WeeklyReview, count: number) {
  const template = review.employees[0]!;
  review.employees = Array.from({ length: count }, (_, index) => ({
    ...structuredClone(template),
    employeePublicId: `attention-${index}`,
    displayName: `Сотрудник ${index + 1}`,
    sortGroup: "ATTENTION" as const,
    attention: {
      observationId: `attention-${index}:revenue`,
      title: "Снизилась выручка в час",
      detail: "Изменение выше порога и требует проверки.",
      effect: "NEGATIVE" as const,
      evidenceRefs: template.metrics.revenuePerHour.evidenceRefs
    },
    peerComparison: template.peerComparison === null ? null : {
      ...structuredClone(template.peerComparison),
      metricCode: "REVENUE_PER_HOUR" as const
    },
    action: {
      ...structuredClone(review.actions[0]!),
      actionId: `employee-action-${index}`,
      scope: "EMPLOYEE" as const,
      employeePublicId: `attention-${index}`,
      metricCode: "REVENUE_PER_HOUR",
      title: "Проверить эффективность смен"
    }
  }));
  review.team.attentionEmployeeCount = count;
  review.team.roster.activeAssignedWithActivity = Math.max(
    review.team.roster.activeAssignedWithActivity,
    count
  );
}

describe("WeeklyReviewView", () => {
  beforeEach(() => {
    getWeeklyReviewMock.mockReset();
  });

  it("puts the managerial conclusion, one action and four KPIs on the first level", async () => {
    const review = makeWeeklyReview();
    const { container } = renderReview(review);

    expect(await screen.findByRole("heading", { name: review.summary.outcome!.text }))
      .toBeInTheDocument();
    expect(screen.getByText(review.period.currentLabel, {
      selector: ".weekly-review-header__period strong"
    })).toBeInTheDocument();
    expect(screen.getByText(`Сравнение: ${review.period.previousLabel}`)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: review.actions[0]!.title })).toBeInTheDocument();
    expect(screen.getByText(/Ориентир/u).parentElement).toHaveTextContent(/не выше 50\s*₽/u);
    expect(screen.getByText(review.actions[0]!.check)).toBeInTheDocument();
    expect(container.querySelectorAll(".weekly-review-metric")).toHaveLength(4);
    expect(screen.getAllByText(review.summary.positive!.text)).toHaveLength(1);
    expect(screen.queryByText(review.summary.risk!.text)).not.toBeInTheDocument();
  });

  it("opens one evidence dialog and returns focus on Escape", async () => {
    const user = userEvent.setup();
    const review = makeWeeklyReview();
    renderReview(review);

    const trigger = await screen.findByRole("button", { name: "Почему такой вывод" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    await user.click(trigger);
    const dialog = screen.getByRole("dialog", { name: "Основание главного вывода" });
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(within(dialog).getByText("Чистая выручка")).toBeInTheDocument();
    expect(within(dialog).getByText("Валовая прибыль")).toBeInTheDocument();
    expect(document.querySelector(".weekly-review-screen"))
      .toHaveAttribute("aria-hidden", "true");
    expect(within(dialog).getByRole("button", { name: "Закрыть детали" })).toHaveFocus();
    await user.keyboard("{Tab}");
    expect(within(dialog).getByRole("button", { name: "Закрыть детали" })).toHaveFocus();

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it("closes the detail panel from its backdrop", async () => {
    const user = userEvent.setup();
    renderReview(makeWeeklyReview());

    await user.click(await screen.findByRole("button", { name: "Почему такой вывод" }));
    expect(screen.getByRole("dialog", { name: "Основание главного вывода" }))
      .toBeInTheDocument();
    fireEvent.mouseDown(document.querySelector(".weekly-review-detail-layer")!);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("uses the detail surface for the revenue formula and keeps structure collapsed", async () => {
    const user = userEvent.setup();
    renderReview(makeWeeklyReview());

    const formulaTrigger = await screen.findByRole("button", {
      name: "Как рассчитана чистая выручка"
    });
    await user.click(formulaTrigger);
    const dialog = screen.getByRole("dialog", { name: "Расчёт чистой выручки" });
    expect(within(dialog).getByText("Продажи")).toBeInTheDocument();
    expect(within(dialog).getByText("Возвраты")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Закрыть детали" }));

    const structureSummary = screen.getByText("Структура продаж").closest("summary")!;
    expect(structureSummary.closest("details")).not.toHaveAttribute("open");
    await user.click(structureSummary);
    expect(structureSummary.closest("details")).toHaveAttribute("open");
    expect(screen.getByText("Техника")).toBeInTheDocument();
  });

  it("shows at most three employee exceptions and compares efficiency only per hour", async () => {
    const user = userEvent.setup();
    const review = makeWeeklyReview();
    addAttentionEmployees(review, 5);
    const { container } = renderReview(review);

    await screen.findByText("Сотрудник 1");
    expect(container.querySelectorAll(".weekly-review-exception")).toHaveLength(3);
    expect(screen.queryByText("Сотрудник 4")).not.toBeInTheDocument();
    expect(screen.getByText("5 из 5 требуют проверки")).toBeInTheDocument();
    expect(screen.queryByText("Требует проверки")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", {
      name: "Почему сотрудник в списке: Сотрудник 1"
    }));
    const dialog = screen.getByRole("dialog", { name: "Сотрудник 1" });
    expect(within(dialog).getByRole("heading", { name: "Сравнение эффективности" }))
      .toBeInTheDocument();
    expect(within(dialog).getByText(/в час при медиане магазина/u)).toBeInTheDocument();
  });

  it("marks only the unavailable time assessment when shifts are incomplete", async () => {
    const review = makeWeeklyReview();
    addAttentionEmployees(review, 1);
    const employee = review.employees[0]!;
    employee.metrics.shiftCount.metricState = "LIMITED";
    employee.metrics.workedHours.metricState = "UNAVAILABLE";
    employee.metrics.revenuePerHour.metricState = "UNAVAILABLE";
    employee.metrics.revenuePerHour.current = null;
    renderReview(review);

    expect(await screen.findByText(
      "Часть смен не заполнена — оценка по часам недоступна"
    )).toBeInTheDocument();
    expect(screen.getByText("1 из 3 требует проверки")).toBeInTheDocument();
  });

  it("keeps one DOM while toggling additional changes and employees", async () => {
    const review = makeWeeklyReview();
    const additionalFactor = structuredClone(review.factors[1]!);
    additionalFactor.factorId = "factor:accessories_revenue";
    additionalFactor.title = "Выручка направления «Аксессуары» выросла";
    additionalFactor.evidenceRefs = ["STORE.STRUCTURE.ACCESSORIES.REVENUE"];
    additionalFactor.comparison.evidenceRefs = additionalFactor.evidenceRefs;
    review.factors.push(additionalFactor);
    addAttentionEmployees(review, 3);
    const { container } = renderReview(review);

    await screen.findByRole("heading", { name: "Команда" });
    const changesToggle = container.querySelector<HTMLButtonElement>(
      'button[aria-controls="weekly-review-changes-list"]'
    );
    const employeesToggle = container.querySelector<HTMLButtonElement>(
      'button[aria-controls="weekly-review-employees-list"]'
    );
    expect(changesToggle).toHaveTextContent("Ещё 1 изменение");
    expect(employeesToggle).toHaveTextContent("Ещё 2 сотрудника");
    expect(changesToggle).toHaveAttribute("aria-expanded", "false");
    expect(employeesToggle).toHaveAttribute("aria-expanded", "false");
    expect(container.querySelectorAll(".weekly-review-factor")).toHaveLength(2);
    expect(container.querySelectorAll(".weekly-review-exception")).toHaveLength(3);

    fireEvent.click(changesToggle!);
    fireEvent.click(employeesToggle!);

    expect(changesToggle).toHaveTextContent("Скрыть дополнительные изменения");
    expect(employeesToggle).toHaveTextContent("Скрыть дополнительных сотрудников");
    expect(changesToggle).toHaveAttribute("aria-expanded", "true");
    expect(employeesToggle).toHaveAttribute("aria-expanded", "true");
    expect(container.querySelectorAll(".weekly-review-factor")).toHaveLength(2);
    expect(container.querySelectorAll(".weekly-review-exception")).toHaveLength(3);
  });

  it("keeps reliable PARTIAL values and centralizes the quality explanation", async () => {
    const user = userEvent.setup();
    const review = partialReview();
    const { container } = renderReview(review);

    expect(await screen.findByText("Разбор по доступным данным")).toBeInTheDocument();
    expect(container.querySelectorAll(".weekly-review-quality-summary")).toHaveLength(1);
    expect(screen.getByText("По возвратам доступны не все данные.")).toBeInTheDocument();
    expect(container.querySelectorAll(".weekly-review-metric")).toHaveLength(4);
    expect(screen.getByRole("button", { name: "Вывод ограничен" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Подробнее об ограничениях" }));
    expect(screen.getByRole("dialog", { name: "Ограничения данных" })).toHaveTextContent(
      "Проверьте синхронизацию возвратов."
    );
    expect(screen.getByRole("dialog", { name: "Ограничения данных" })).toHaveTextContent(
      "Исправление выполняет администратор или ответственный за загрузку данных."
    );
  });

  it("explains PARTIAL caused only by a local team limitation", async () => {
    const user = userEvent.setup();
    const review = makeWeeklyReview();
    review.reportState = "PARTIAL";
    review.qualitySummary = {
      blockingCount: 0,
      warningCount: 1,
      affectedBlockCount: 1,
      message: "Надёжные показатели сохранены; ограничений: 1"
    };
    review.limitations = [];
    review.team.state = "LIMITED";
    review.team.limitations = ["Для части сотрудников недостаточно продаж для сравнения"];
    renderReview(review);

    expect(await screen.findByText("Надёжные показатели сохранены; ограничений: 1"))
      .toBeInTheDocument();
    const qualityTrigger = screen.getByRole("button", { name: "Подробнее об ограничениях" });
    await user.click(qualityTrigger);
    expect(screen.getByRole("dialog", { name: "Ограничения данных" })).toHaveTextContent(
      "Для части сотрудников недостаточно продаж для сравнения"
    );
  });

  it("does not promote missing shifts to a report warning", async () => {
    const review = makeWeeklyReview();
    review.limitations = [{
      ...limitation(review),
      limitationId: "missing-shifts",
      code: "WORKLOAD_DATA_MISSING",
      affectedBlockIds: ["employees"],
      affectedMetricCodes: ["SHIFT_COUNT", "WORKED_HOURS", "REVENUE_PER_HOUR"],
      summary: "Не заполнены смены"
    }];
    review.employees[0]!.metrics.workedHours.metricState = "UNAVAILABLE";
    review.employees[0]!.metrics.revenuePerHour.metricState = "UNAVAILABLE";
    review.employees[0]!.peerComparison = null;
    renderReview(review);

    await screen.findByRole("heading", { name: "Результаты недели" });
    expect(screen.queryByText("Часть выводов ограничена")).not.toBeInTheDocument();
    expect(screen.queryByText("Не заполнены смены")).not.toBeInTheDocument();
  });

  it("does not repeat backend narratives after AI enrichment", async () => {
    const review = makeWeeklyReview();
    review.summary.generatedBy = "AI_ENHANCED";
    review.aiEnhancement.state = "READY";
    review.aiEnhancement.promptVersion = "weekly-interpretation-v22";
    review.aiEnhancement.contentSchemaVersion = 4;
    review.aiEnhancement.publishedAt = "2026-08-24T04:05:00Z";
    renderReview(review);

    await screen.findByRole("heading", { name: review.summary.outcome!.text });
    expect(screen.getByText("Дополнено ИИ")).toBeInTheDocument();
    expect(screen.getAllByText(review.summary.positive!.text)).toHaveLength(1);
    expect(screen.queryByText(review.summary.risk!.text)).not.toBeInTheDocument();
  });

  it("omits the changes section when its only factor already owns the primary action", async () => {
    const review = makeWeeklyReview();
    review.factors = [review.factors[0]!];
    renderReview(review);

    await screen.findByRole("heading", { name: review.actions[0]!.title });
    expect(screen.queryByRole("heading", { name: "Что изменилось" })).not.toBeInTheDocument();
    expect(screen.queryByText("Существенных изменений по доступным данным нет."))
      .not.toBeInTheDocument();
  });

  it("keeps empty business sections compact and explicit", async () => {
    const review = makeWeeklyReview();
    review.factors = [];
    review.actions = [];
    review.team.observations = [];
    review.employees = [];
    review.team.attentionEmployeeCount = 0;
    renderReview(review);

    expect(await screen.findByText("Существенных изменений по доступным данным нет."))
      .toBeInTheDocument();
    expect(screen.getByText(/Дополнительная проверка не требуется/u)).toBeInTheDocument();
    expect(screen.getByText(
      "Значимых отрицательных изменений на достаточной базе не обнаружено."
    ))
      .toBeInTheDocument();
  });

  it("does not claim that no check is needed for PARTIAL without actions", async () => {
    const review = partialReview();
    review.actions = [];
    renderReview(review);

    expect(await screen.findByText(
      "Приоритетная проверка не сформирована по доступной части данных. Сначала уточните ограничения."
    )).toBeInTheDocument();
    expect(screen.queryByText(/Дополнительная проверка не требуется/u))
      .not.toBeInTheDocument();
  });

  it("does not render stale structure values when the block is insufficient", async () => {
    const user = userEvent.setup();
    const review = partialReview();
    review.salesStructure.state = "INSUFFICIENT";
    renderReview(review);

    await user.click((await screen.findByText("Структура продаж")).closest("summary")!);
    expect(screen.getByText("Для структуры продаж недостаточно данных.")).toBeInTheDocument();
    expect(screen.queryByText("Техника")).not.toBeInTheDocument();
  });

  it("separates PREPARING from a data failure", async () => {
    const review = makeWeeklyReview();
    review.reportState = "PREPARING";
    review.qualitySummary.message = "Собираем результаты завершённой недели.";
    renderReview(review);

    expect(await screen.findByRole("heading", { name: "Разбор формируется" }))
      .toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Для разбора не хватает данных" }))
      .not.toBeInTheDocument();
  });

  it("announces BLOCKED, names the responsible role and hides the unreliable report", async () => {
    const user = userEvent.setup();
    const review = makeWeeklyReview();
    review.reportState = "BLOCKED";
    review.qualitySummary.message = "Не загружены продажи за часть недели.";
    review.limitations = [{
      ...limitation(review),
      severity: "BLOCKING",
      summary: "Продажи загружены не за всю неделю."
    }];
    renderReview(review);

    const alert = await screen.findByRole("alert");
    expect(within(alert).getByRole("heading", { name: "Для разбора не хватает данных" }))
      .toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Результаты недели" }))
      .not.toBeInTheDocument();
    await user.click(within(alert).getByRole("button", { name: "Что нужно исправить" }));
    expect(screen.getByRole("dialog", { name: "Что нужно исправить" })).toHaveTextContent(
      "Исправление выполняет администратор или ответственный за загрузку данных."
    );
    expect(screen.queryByRole("link", { name: "Открыть качество данных" }))
      .not.toBeInTheDocument();
  });

  it("offers an administrator the existing data-quality route", async () => {
    const user = userEvent.setup();
    const review = makeWeeklyReview();
    review.reportState = "BLOCKED";
    review.qualitySummary.message = "Не загружены продажи за часть недели.";
    review.limitations = [{
      ...limitation(review),
      severity: "BLOCKING",
      summary: "Продажи загружены не за всю неделю."
    }];
    renderReview(review, "/quality?store=store-1");

    await user.click(await screen.findByRole("button", { name: "Что нужно исправить" }));
    expect(screen.getByRole("link", { name: "Открыть качество данных" }))
      .toHaveAttribute("href", "/quality?store=store-1");
  });

  it("lets a manager retry an empty response", async () => {
    getWeeklyReviewMock.mockResolvedValueOnce(null).mockResolvedValueOnce(makeWeeklyReview());
    renderView();

    fireEvent.click(await screen.findByRole("button", { name: "Проверить снова" }));
    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "Результаты недели" })).toBeInTheDocument();
    });
    expect(getWeeklyReviewMock).toHaveBeenCalledTimes(2);
  });

  it("shows a recoverable network error", async () => {
    getWeeklyReviewMock.mockRejectedValue(new Error("network"));
    renderView();

    const alert = await screen.findByRole("alert");
    expect(within(alert).getByText("Не удалось загрузить данные")).toBeInTheDocument();
    expect(within(alert).getByRole("button", { name: /Повторить/u })).toBeInTheDocument();
  });

  it("keeps the latest review visible when a background refresh fails", async () => {
    getWeeklyReviewMock.mockRejectedValue(new Error("network"));
    renderView(undefined, makeWeeklyReview());

    expect(await screen.findByRole("heading", { name: "Результаты недели" }))
      .toBeInTheDocument();
    expect(await screen.findByText(/Показаны последние доступные данные/u))
      .toBeInTheDocument();
  });

  it("keeps the previous view while the new snapshot is absent", async () => {
    getWeeklyReviewMock.mockResolvedValue(null);
    renderView(<div>Предыдущий недельный разбор</div>);

    expect(await screen.findByText("Предыдущий недельный разбор")).toBeInTheDocument();
    expect(screen.getByText(/Показан предыдущий формат/u)).toBeInTheDocument();
  });
});
