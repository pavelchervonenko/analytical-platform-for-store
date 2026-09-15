import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiClientError } from "../api/client";
import { InlineQueryError, QueryError, StaleDataNote, queryErrorPresentation } from "./QueryState";

describe("query error presentation", () => {
  it("does not offer a pointless retry for missing or forbidden data", () => {
    const forbidden = new ApiClientError("Недостаточно прав", { status: 403, code: "ACCESS_DENIED" });
    render(<QueryError error={forbidden} onRetry={vi.fn()} />);
    expect(screen.getByText("Нет доступа")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();

    expect(queryErrorPresentation(new ApiClientError("Отчет не найден", { status: 404, code: "REPORT_NOT_FOUND" })).retryLabel).toBeNull();
  });

  it("labels a concurrent change as refreshable and shows a support reference", () => {
    const changed = new ApiClientError("Данные уже изменились", {
      status: 412,
      code: "PRECONDITION_FAILED",
      correlationId: "request-123"
    });
    render(<QueryError error={changed} onRetry={vi.fn()} />);
    expect(screen.getByText("Данные изменились")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Обновить/u })).toBeInTheDocument();
    expect(screen.getByText("Подробности")).toBeInTheDocument();
    expect(screen.getByText("Код обращения: request-123")).toBeInTheDocument();
  });

  it("renders a local failure without an urgent alert", () => {
    render(<InlineQueryError error={new ApiClientError("Временно недоступно", { status: 503, code: "INTERNAL_ERROR" })} onRetry={vi.fn()} />);
    expect(screen.getByRole("status")).toHaveTextContent("Сервис временно недоступен");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("keeps a background refresh failure quiet and actionable", () => {
    const retry = vi.fn();
    render(<StaleDataNote error={new ApiClientError("Нет соединения", { status: 0, code: "NETWORK_ERROR" })} onRetry={retry} />);
    expect(screen.getByRole("status")).toHaveTextContent("Показаны последние доступные данные");
    screen.getByRole("button", { name: "Повторить" }).click();
    expect(retry).toHaveBeenCalledOnce();
  });
});
