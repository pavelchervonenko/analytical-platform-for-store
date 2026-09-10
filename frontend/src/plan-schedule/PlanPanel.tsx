import { useQuery } from "@tanstack/react-query";
import { RefreshCw, Settings2 } from "lucide-react";
import { Link, useLocation } from "react-router";
import type { PlanDirection } from "../api/contracts";
import { getPlanProgress, queryKeys } from "../api/queries";
import { formatDate, formatMonth } from "../shared/date";
import { formatCompactMoney, formatMoney, formatPercent } from "../shared/format";
import { InlineQueryError, StaleDataNote } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { DailyPlanTable } from "./DailyPlanTable";

const directionLabels: Record<string, string> = {
  REVENUE: "Выручка",
  ACCESSORY: "Аксессуары",
  SERVICE: "Услуги",
  ADDITIONAL: "Доп. выручка"
};

const directionPriority: Record<string, number> = {
  MISSED: 0,
  AT_RISK: 1,
  NOT_AVAILABLE: 2,
  ON_TRACK: 3,
  ACHIEVED: 4
};

function statusTone(status: string): string {
  if (["ACHIEVED", "ON_TRACK"].includes(status)) return "success";
  if (status === "MISSED") return "danger";
  if (status === "NOT_AVAILABLE") return "neutral";
  return "warning";
}

function isRevenueDirection(direction: PlanDirection): boolean {
  return direction.criterionType === "AMOUNT" || direction.code === "REVENUE";
}

export function directionStatusLabel(direction: PlanDirection, monthClosed: boolean): string {
  if (direction.status === "ACHIEVED") {
    return monthClosed ? "Цель выполнена" : "Цель достигнута на текущую дату";
  }
  if (direction.status === "MISSED") return "Цель не выполнена";
  if (direction.status === "NOT_AVAILABLE") return "Нет данных для расчёта";
  if (direction.status === "ON_TRACK") {
    return isRevenueDirection(direction) ? "По текущему темпу цель будет выполнена" : "По графику";
  }
  return isRevenueDirection(direction) ? "Темп ниже плана" : "Ниже цели";
}

export function primaryPlanAction(direction: PlanDirection | undefined): string {
  if (!direction) return "";
  const label = directionLabels[direction.code] ?? "Направление";
  if (direction.status === "MISSED") return `${label}: итог месяца, цель не выполнена.`;
  if (direction.status === "NOT_AVAILABLE") return `${label}: расчёт появится после обновления данных.`;
  if (direction.remainingAmount <= 0) return `${label}: цель достигнута на текущую дату.`;
  if (direction.requiredPerRemainingDay == null) return `${label}: расчёт темпа пока недоступен.`;
  const dailyAmount = formatCompactMoney(direction.requiredPerRemainingDay);
  return isRevenueDirection(direction)
    ? `${label}: для выполнения месячной цели нужно ${dailyAmount} в день.`
    : `${label}: для сокращения текущего отставания нужно ${dailyAmount} в день.`;
}

export function getPlanSummary(
  directions: PlanDirection[],
  allAchieved: boolean,
  remainingDays: number
): { label: string; description: string; tone: string } {
  const monthClosed = remainingDays === 0;
  const sortedDirections = [...directions].sort(
    (left, right) => (directionPriority[left.status] ?? 99) - (directionPriority[right.status] ?? 99)
  );
  if (allAchieved) {
    return monthClosed
      ? { label: "План выполнен", description: "Все цели месяца выполнены.", tone: "success" }
      : {
          label: "Все цели достигнуты на текущую дату",
          description: "Продолжайте следить за темпом до конца месяца.",
          tone: "success"
        };
  }
  if (monthClosed && directions.some((direction) => direction.status === "MISSED")) {
    return { label: "План не выполнен", description: primaryPlanAction(sortedDirections[0]), tone: "danger" };
  }
  if (directions.some((direction) => direction.status === "AT_RISK")) {
    return { label: "План требует внимания", description: primaryPlanAction(sortedDirections[0]), tone: "warning" };
  }
  if (directions.some((direction) => direction.status === "NOT_AVAILABLE")) {
    return { label: "Недостаточно данных", description: primaryPlanAction(sortedDirections[0]), tone: "neutral" };
  }
  return {
    label: "План выполняется по графику",
    description: primaryPlanAction(sortedDirections[0]),
    tone: "success"
  };
}

function formatPercentagePoints(value: number | null): string {
  if (value == null) return "—";
  const formatted = new Intl.NumberFormat("ru-RU", {
    maximumFractionDigits: 1,
    signDisplay: "exceptZero"
  }).format(value).replace("-", "−");
  return `${formatted} п. п.`;
}

export function RevenuePlanBlock({ direction, monthClosed }: { direction: PlanDirection; monthClosed: boolean }) {
  const completion = direction.criterionCompletionPercent;
  const dailyRequirement = direction.remainingAmount > 0
    ? formatCompactMoney(direction.requiredPerRemainingDay)
    : "Не требуется";
  return (
    <section className="panel plan-revenue-panel" aria-labelledby="plan-revenue-title">
      <div className="plan-section-heading">
        <div>
          <p className="eyebrow">Выручка</p>
          <h2 id="plan-revenue-title">Месячная цель</h2>
        </div>
        <span className={`status status--${statusTone(direction.status)}`}>
          {directionStatusLabel(direction, monthClosed)}
        </span>
      </div>
      <div className="plan-revenue-content">
        <div className="plan-revenue-primary">
          <small>Факт к цели</small>
          <strong>{formatMoney(direction.actualAmount)} <span>из {formatMoney(direction.targetAmount)}</span></strong>
          <div className="plan-progress-line">
            <progress
              value={Math.min(100, Math.max(0, completion ?? 0))}
              max={100}
              aria-label={`Выполнение плана выручки: ${formatPercent(completion)}`}
            />
            <b>{formatPercent(completion)}</b>
          </div>
        </div>
        <dl className="plan-revenue-metrics">
          {monthClosed ? (
            <div>
              <dt>Итоговый недобор</dt>
              <dd>{direction.remainingAmount > 0 ? formatCompactMoney(direction.remainingAmount) : "Недобора нет"}</dd>
            </div>
          ) : (
            <>
              <div><dt>Прогноз на конец месяца</dt><dd>{formatCompactMoney(direction.projectedAmount)}</dd></div>
              <div><dt>Нужно в день до конца месяца</dt><dd>{dailyRequirement}</dd></div>
            </>
          )}
        </dl>
      </div>
    </section>
  );
}

export function StructureDirectionRow({
  direction,
  monthClosed
}: {
  direction: PlanDirection;
  monthClosed: boolean;
}) {
  const label = directionLabels[direction.code] ?? "Направление";
  return (
    <details className="plan-structure-row">
      <summary>
        <span className="plan-structure-row__name">
          <strong>{label}</strong>
          <small className={`plan-structure-row__status plan-structure-row__status--${statusTone(direction.status)}`}>
            {directionStatusLabel(direction, monthClosed)}
          </small>
        </span>
        <span className="plan-structure-row__share">
          <strong>{formatPercent(direction.actualSharePercent)} <small>из {formatPercent(direction.targetSharePercent)}</small></strong>
          <small>Текущая доля</small>
        </span>
        <span className={`plan-structure-row__gap plan-structure-row__gap--${statusTone(direction.status)}`}>
          <strong>{formatPercentagePoints(direction.shareGapPercentagePoints)}</strong>
          <small>Отклонение</small>
        </span>
        <span className="plan-structure-row__toggle" aria-hidden="true" />
      </summary>
      <dl className="plan-structure-details">
        <div><dt>Фактическая сумма</dt><dd>{formatMoney(direction.actualAmount)}</dd></div>
        <div><dt>Ориентир при текущей выручке</dt><dd>{formatMoney(direction.targetAmount)}</dd></div>
        {!monthClosed && (
          <div><dt>Прогноз суммы на конец месяца</dt><dd>{formatCompactMoney(direction.projectedAmount)}</dd></div>
        )}
        <div>
          <dt>{monthClosed ? "Итоговое отставание" : "Для сокращения отставания в день"}</dt>
          <dd>
            {direction.remainingAmount > 0
              ? monthClosed
                ? formatCompactMoney(direction.remainingAmount)
                : formatCompactMoney(direction.requiredPerRemainingDay)
              : monthClosed
                ? "Отставания нет"
                : "Не требуется"}
          </dd>
        </div>
      </dl>
    </details>
  );
}

export function RevenueStructureBlock({
  directions,
  monthClosed
}: {
  directions: PlanDirection[];
  monthClosed: boolean;
}) {
  const shareDirections = ["ACCESSORY", "SERVICE", "ADDITIONAL"]
    .map((code) => directions.find((direction) => direction.code === code))
    .filter((direction): direction is PlanDirection => direction != null);
  return (
    <section className="panel plan-structure-panel" aria-labelledby="plan-structure-title">
      <div className="plan-section-heading">
        <div>
          <p className="eyebrow">Структура выручки</p>
          <h2 id="plan-structure-title">Доли направлений</h2>
          <p>Сравнение фактической доли с отдельной целью каждого направления.</p>
        </div>
      </div>
      <div className="plan-structure-list">
        {shareDirections.map((direction) => (
          <StructureDirectionRow direction={direction} monthClosed={monthClosed} key={direction.code} />
        ))}
      </div>
    </section>
  );
}

function PlanSkeleton() {
  return (
    <div className="plan-panel-skeleton" aria-busy="true" aria-label="Загрузка плана">
      <span className="skeleton skeleton--banner" />
      <span className="skeleton skeleton--panel" />
      <span className="skeleton skeleton--panel" />
    </div>
  );
}

export function PlanPanel() {
  const { selectedStore, month, asOfDate } = useWorkspace();
  const location = useLocation();
  const storeId = selectedStore.id;
  const progressQuery = useQuery({
    queryKey: queryKeys.planProgress(storeId, month, asOfDate),
    queryFn: () => getPlanProgress(storeId, month, asOfDate)
  });

  if (progressQuery.isPending && progressQuery.data === undefined) return <PlanSkeleton />;
  if (progressQuery.isError && progressQuery.data === undefined) {
    return <InlineQueryError error={progressQuery.error} onRetry={() => void progressQuery.refetch()} />;
  }

  const progress = progressQuery.data;
  const settingsSearch = new URLSearchParams(location.search);
  settingsSearch.delete("section");

  if (!progress) {
    return (
      <section className="panel plan-empty-panel">
        <span className="plan-empty-panel__icon"><Settings2 /></span>
        <div>
          <p className="eyebrow">Обзор плана</p>
          <h2>План на {formatMonth(month)} ещё не задан</h2>
          <p>Укажите цели магазина, чтобы видеть темп выполнения и ориентиры по дням.</p>
        </div>
        <Link className="button button--primary" to={{ pathname: "/plan/settings", search: settingsSearch.toString() }}>
          Настроить план
        </Link>
      </section>
    );
  }

  const summary = getPlanSummary(progress.directions, progress.allDirectionsAchieved, progress.remainingDays);
  const revenue = progress.directions.find((direction) => direction.code === "REVENUE");
  const monthClosed = progress.remainingDays === 0;

  return (
    <div className="plan-panel-view">
      {progressQuery.isError && progressQuery.data !== undefined && (
        <StaleDataNote error={progressQuery.error} onRetry={() => void progressQuery.refetch()} />
      )}
      {(!progress.dataQuality.completeThroughAsOf || !progress.dataQuality.classificationComplete) && (
        <section className="plan-data-note" role="status">
          <RefreshCw />
          <p>
            {!progress.dataQuality.completeThroughAsOf
              ? "Расчёт обновится, когда данные на выбранную дату будут загружены. "
              : ""}
            {!progress.dataQuality.classificationComplete
              ? "Некоторые направления появятся после обновления категорий товаров."
              : ""}
          </p>
        </section>
      )}
      <section className={`plan-progress-heading plan-progress-heading--${summary.tone}`}>
        <div>
          <p className="eyebrow">План на {formatMonth(month)}. Данные на {formatDate(progress.asOfDate)}</p>
          <h2>{summary.label}</h2>
          <p>{summary.description}</p>
          <small>{progress.remainingDays > 0 ? `До конца месяца ${progress.remainingDays} дн.` : "Месяц завершён."}</small>
        </div>
      </section>
      <div className="plan-overview-grid">
        {revenue && <RevenuePlanBlock direction={revenue} monthClosed={monthClosed} />}
        <RevenueStructureBlock directions={progress.directions} monthClosed={monthClosed} />
      </div>
      <DailyPlanTable targets={progress.dailyTargets} />
    </div>
  );
}
