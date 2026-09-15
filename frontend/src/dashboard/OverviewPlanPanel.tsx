import { Target } from "lucide-react";
import { Link } from "react-router";
import type { OverviewMetricScope, PlanDirection, PlanProgress } from "../api/contracts";
import { formatCompactMoney, formatPercent } from "../shared/format";
import { InlineQueryError, PanelSkeleton } from "../shared/QueryState";
import {
  formatAbsolutePercentagePoints,
  hasRevenueForecast,
  planDirectionLabels,
  planScopeLabel,
  revenueForecastValue
} from "../plan-schedule/planPresentation";

function statusTone(status: string): string {
  if (["ACHIEVED", "ON_TRACK"].includes(status)) return "success";
  if (status === "MISSED") return "danger";
  return "warning";
}

export function overviewPlanStatusLabel(status: string, monthClosed: boolean): string {
  if (status === "ACHIEVED") return monthClosed ? "Выполнено" : "Цель достигнута";
  if (status === "ON_TRACK") return "По графику";
  if (status === "AT_RISK") return "Ниже цели";
  if (status === "MISSED") return "Не выполнено";
  if (status === "NOT_AVAILABLE") return "Недостаточно данных";
  return "Статус неизвестен";
}

function revenueDifference(actual: number, target: number): string {
  const difference = actual - target;
  if (difference === 0) return "Точно по плану";
  return difference > 0
    ? `Выше плана на ${formatCompactMoney(difference)}`
    : `Не хватило ${formatCompactMoney(Math.abs(difference))}`;
}

function shareDifference(value: number | null): string | null {
  if (value == null) return null;
  if (value === 0) return "На уровне цели";
  const difference = formatAbsolutePercentagePoints(value);
  return value > 0 ? `Выше цели на ${difference}` : `Ниже цели на ${difference}`;
}

function directionSummary(direction: PlanDirection, monthClosed: boolean, forecastAvailable: boolean): {
  primary: string;
  secondary: string | null;
} {
  const moment = monthClosed ? "Итог" : "Сейчас";
  if (direction.criterionType === "AMOUNT" || direction.code === "REVENUE") {
    const completion = formatPercent(direction.criterionCompletionPercent);
    return {
      primary: `${moment} ${formatCompactMoney(direction.actualAmount)} из ${formatCompactMoney(direction.targetAmount)}, ${completion} плана`,
      secondary: monthClosed
        ? revenueDifference(direction.actualAmount, direction.targetAmount)
        : forecastAvailable && hasRevenueForecast(direction)
          ? `Прогноз ${revenueForecastValue(direction)}`
          : null
    };
  }

  if (direction.actualSharePercent == null) {
    return { primary: "Доля пока недоступна", secondary: null };
  }

  return {
    primary: `${moment} ${formatPercent(direction.actualSharePercent)} при цели ${formatPercent(direction.targetSharePercent)}. ${shareDifference(direction.shareGapPercentagePoints) ?? ""}`.trim(),
    secondary: monthClosed || !forecastAvailable || direction.status === "NOT_AVAILABLE"
      ? null
      : `Прогноз суммы ${formatCompactMoney(direction.projectedAmount)}`
  };
}

function DirectionRow({
  direction,
  monthClosed,
  forecastAvailable
}: {
  direction: PlanDirection;
  monthClosed: boolean;
  forecastAvailable: boolean;
}) {
  const label = planDirectionLabels[direction.code] ?? "Другое направление";
  const completion = Math.min(100, Math.max(0, direction.criterionCompletionPercent ?? 0));
  const summary = directionSummary(direction, monthClosed, forecastAvailable);

  return (
    <article className="direction-row">
      <div className="direction-row__top">
        <strong>{label}</strong>
        <span className={`status status--${statusTone(direction.status)}`}>
          {overviewPlanStatusLabel(direction.status, monthClosed)}
        </span>
      </div>
      <progress
        className="progress"
        value={completion}
        max={100}
        aria-label={`Выполнение направления ${label}`}
        aria-valuetext={summary.primary}
      />
      <div className="direction-row__meta">
        <span>{summary.primary}</span>
        {summary.secondary && <span>{summary.secondary}</span>}
      </div>
    </article>
  );
}

export function OverviewPlanPanel({
  scope,
  plan,
  isPending,
  isError,
  error,
  onRetry,
  planSearch
}: {
  scope: OverviewMetricScope;
  plan: PlanProgress | null | undefined;
  isPending: boolean;
  isError: boolean;
  error: unknown;
  onRetry: () => void;
  planSearch: string;
}) {
  const monthClosed = plan?.remainingDays === 0;
  const coverageComplete = plan?.dataQuality?.completeThroughAsOf ?? true;
  const classificationComplete = plan?.dataQuality?.classificationComplete ?? true;

  return (
    <section className="panel plan-panel">
      <div className="panel__heading">
        <h2>План месяца — {planScopeLabel(scope)}</h2>
        <Link className="plan-panel__link" to={{ pathname: "/plan", search: planSearch }}>
          Открыть план
        </Link>
      </div>
      {plan === undefined && isPending ? (
        <PanelSkeleton rows={3} />
      ) : plan === undefined && isError ? (
        <InlineQueryError error={error} onRetry={onRetry} />
      ) : !plan ? (
        <div className="panel-empty">
          <Target size={24} />
          <strong>План не задан</strong>
          <p>Задайте цели на месяц.</p>
        </div>
      ) : (
        <div className="direction-list">
          {plan.directions.map((direction) => (
            <DirectionRow
              key={direction.code}
              direction={direction}
              monthClosed={monthClosed}
              forecastAvailable={coverageComplete && (
                direction.criterionType === "AMOUNT" || classificationComplete
              )}
            />
          ))}
          {(!coverageComplete || !classificationComplete) && (
            <p className="plan-panel__data-note">
              {!coverageComplete
                ? monthClosed
                  ? "Итог обновится после загрузки всех данных месяца."
                  : "Прогноз обновится после загрузки завершённых дней."
                : monthClosed
                  ? "Итог по направлениям обновится после распределения товаров."
                  : "Прогнозы по направлениям обновятся после распределения товаров."}
            </p>
          )}
        </div>
      )}
    </section>
  );
}
