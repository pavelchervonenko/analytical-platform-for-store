import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { Link } from "react-router";
import type {
  WeeklyReview,
  WeeklyReviewEmployee
} from "../../api/weeklyReviewContract";
import {
  actionTargetText,
  formatEvidenceValue,
  formatValue,
  metricComparisonText
} from "../weekly-review-presentation";
import {
  employeeEfficiencyComparison,
  evidenceFor,
  type EvidenceIndex
} from "./weeklyReviewViewModel";

export type ReviewDetailContext =
  | { kind: "evidence"; title: string; evidenceRefs: readonly string[] }
  | { kind: "revenue"; title: string }
  | {
    kind: "limitations";
    title: string;
    limitations: WeeklyReview["limitations"];
    messages?: readonly string[];
  }
  | { kind: "employee"; title: string; employee: WeeklyReviewEmployee };

type Props = {
  context: ReviewDetailContext | null;
  review: WeeklyReview;
  evidenceByRef: EvidenceIndex;
  qualityHref: string | null;
  onClose: () => void;
};

const FOCUSABLE = [
  "button:not([disabled])",
  "a[href]",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[tabindex]:not([tabindex='-1'])"
].join(",");

const ADMIN_OWNED_LIMITATION_CODES = new Set([
  "COST_DATA_MISSING",
  "PRODUCTS_UNCLASSIFIED",
  "RETURN_EMPLOYEE_UNATTRIBUTED",
  "RETURNS_PARTIAL",
  "SALES_MISSING",
  "SALES_OR_RETURNS_CONSISTENCY_ISSUE",
  "UNEXPECTED_ZERO_COST"
]);

function limitationOwnerMessage(code: string): string | null {
  if (!ADMIN_OWNED_LIMITATION_CODES.has(code) && !code.endsWith("_COVERAGE_INCOMPLETE")) {
    return null;
  }
  if (code === "PRODUCTS_UNCLASSIFIED") {
    return "Исправление выполняет администратор или ответственный за классификацию товаров.";
  }
  if (code === "COST_DATA_MISSING" || code === "UNEXPECTED_ZERO_COST") {
    return "Исправление выполняет администратор или ответственный за себестоимость.";
  }
  return "Исправление выполняет администратор или ответственный за загрузку данных.";
}

export function ReviewDetailPanel({
  context,
  review,
  evidenceByRef,
  qualityHref,
  onClose
}: Props) {
  const panelRef = useRef<HTMLElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (context === null) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    closeRef.current?.focus();

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== "Tab" || panelRef.current === null) return;
      const focusable = [...panelRef.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
      if (focusable.length === 0) {
        event.preventDefault();
        panelRef.current.focus();
        return;
      }
      const first = focusable[0]!;
      const last = focusable.at(-1)!;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [context, onClose]);

  if (context === null) return null;

  return createPortal(
    <div
      className="weekly-review-detail-layer"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <aside
        aria-labelledby="weekly-review-detail-title"
        aria-modal="true"
        className="weekly-review-detail-panel"
        id="weekly-review-detail-panel"
        ref={panelRef}
        role="dialog"
        tabIndex={-1}
      >
        <header>
          <div>
            <span>Детали разбора</span>
            <h2 id="weekly-review-detail-title">{context.title}</h2>
          </div>
          <button ref={closeRef} type="button" onClick={onClose} aria-label="Закрыть детали">
            <X aria-hidden="true" />
          </button>
        </header>
        <div className="weekly-review-detail-panel__body">
          {context.kind === "evidence" && (
            <EvidenceDetails
              evidenceRefs={context.evidenceRefs}
              evidenceByRef={evidenceByRef}
            />
          )}
          {context.kind === "revenue" && <RevenueDetails review={review} />}
          {context.kind === "limitations" && (
            <LimitationDetails
              limitations={context.limitations}
              messages={context.messages ?? []}
              qualityHref={qualityHref}
            />
          )}
          {context.kind === "employee" && (
            <EmployeeDetails employee={context.employee} />
          )}
        </div>
      </aside>
    </div>,
    document.body
  );
}

function EvidenceDetails({
  evidenceRefs,
  evidenceByRef
}: {
  evidenceRefs: readonly string[];
  evidenceByRef: EvidenceIndex;
}) {
  const evidence = evidenceFor(evidenceRefs, evidenceByRef);
  if (evidence.length === 0) {
    return <p className="weekly-review-detail-empty">Подробные значения недоступны.</p>;
  }
  return (
    <div className="weekly-review-detail-list">
      {evidence.map((item) => (
        <article key={item.evidenceRef}>
          <span>{item.label}</span>
          <strong>{formatEvidenceValue(item.currentValue, item.unit)}</strong>
          {item.previousPeriod !== null && (
            <small>Было {formatEvidenceValue(item.previousValue, item.unit)}</small>
          )}
          {item.sufficiency !== "SUFFICIENT" && (
            <em>Вывод ограничен доступной выборкой</em>
          )}
        </article>
      ))}
    </div>
  );
}

function RevenueDetails({ review }: { review: WeeklyReview }) {
  const value = review.revenueDecomposition;
  return (
    <>
      <p className="weekly-review-detail-intro">
        Чистая выручка считается после вычета возвратов из продаж завершённой недели.
      </p>
      <div className="weekly-review-formula-row">
        <article>
          <span>Продажи</span>
          <strong>{formatValue(value.salesRevenue.current, "RUB")}</strong>
          <small>{formatValue(value.saleDocumentCount.current, "COUNT")} документов</small>
        </article>
        <b aria-hidden="true">−</b>
        <article>
          <span>Возвраты</span>
          <strong>{formatValue(value.returnRevenue.current, "RUB")}</strong>
          <small>{formatValue(value.returnDocumentCount.current, "COUNT")} документов</small>
        </article>
        <b aria-hidden="true">=</b>
        <article>
          <span>Чистая выручка</span>
          <strong>{formatValue(value.netRevenue.current, "RUB")}</strong>
        </article>
      </div>
    </>
  );
}

function LimitationDetails({
  limitations,
  messages,
  qualityHref
}: {
  limitations: WeeklyReview["limitations"];
  messages: readonly string[];
  qualityHref: string | null;
}) {
  if (limitations.length === 0 && messages.length === 0) {
    return <p className="weekly-review-detail-empty">Дополнительных ограничений нет.</p>;
  }
  const hasAdminOwnedLimitation = limitations.some((limitation) => (
    limitationOwnerMessage(limitation.code) !== null
      || limitation.severity === "BLOCKING"
  ));
  return (
    <div className="weekly-review-limitation-list">
      {limitations.map((limitation) => {
        const owner = limitationOwnerMessage(limitation.code)
          ?? (limitation.severity === "BLOCKING"
            ? "Исправление выполняет администратор или ответственный за загрузку данных."
            : null);
        return (
          <article key={limitation.limitationId}>
            <strong>{limitation.summary}</strong>
            {owner && <p className="weekly-review-limitation-owner">{owner}</p>}
            {limitation.resolution && <p>{limitation.resolution}</p>}
          </article>
        );
      })}
      {[...new Set(messages)].map((message) => (
        <article key={message}>
          <strong>{message}</strong>
        </article>
      ))}
      {qualityHref !== null && hasAdminOwnedLimitation && (
        <Link className="weekly-review-quality-link" to={qualityHref}>
          Открыть качество данных
        </Link>
      )}
    </div>
  );
}

function EmployeeDetails({ employee }: { employee: WeeklyReviewEmployee }) {
  const efficiency = employeeEfficiencyComparison(employee);
  const metrics = [
    employee.metrics.netRevenue,
    employee.metrics.completedSales,
    employee.metrics.additionalShare,
    employee.metrics.revenuePerHour
  ].filter((metric) => metric.metricState !== "UNAVAILABLE");
  return (
    <div className="weekly-review-employee-details">
      {employee.attention && (
        <section aria-labelledby="weekly-review-employee-attention-title">
          <h3 id="weekly-review-employee-attention-title">Почему сотрудник требует внимания</h3>
          <strong>{employee.attention.title}</strong>
          <p>{employee.attention.detail}</p>
        </section>
      )}
      <div className="weekly-review-detail-list">
        {metrics.map((metric) => (
          <article key={metric.metricId}>
            <span>{metric.label}</span>
            <strong>{formatValue(metric.current, metric.unit)}</strong>
            <small>{metricComparisonText(metric)}</small>
          </article>
        ))}
      </div>
      {efficiency && (
        <section aria-labelledby="weekly-review-efficiency-title">
          <h3 id="weekly-review-efficiency-title">Сравнение эффективности</h3>
          <p>
            {formatValue(efficiency.employeeValue, "RUB")} в час при медиане магазина{" "}
            {formatValue(efficiency.benchmarkValue, "RUB")} в час.
          </p>
          <small>В сравнении: {efficiency.eligibleCount} сотрудников с достаточными сменами.</small>
        </section>
      )}
      {employee.action && (
        <section aria-labelledby="weekly-review-employee-action-title">
          <h3 id="weekly-review-employee-action-title">Что проверить</h3>
          <strong>{employee.action.title}</strong>
          <p>Ориентир: {actionTargetText(employee.action)}</p>
          <p>{employee.action.check}</p>
        </section>
      )}
    </div>
  );
}
