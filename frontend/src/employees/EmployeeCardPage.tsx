import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, BarChart3, CalendarDays, CheckCircle2, CircleDollarSign, Clock3, Info, Link2, Target, Trophy, WalletCards } from "lucide-react";
import { Link, useLocation, useParams } from "react-router";
import { getEmployeeCard, queryKeys, type EmployeeComparisonMode } from "../api/queries";
import { formatDate } from "../shared/date";
import { formatCompactMoney, formatMoney, formatNumber, formatPercent } from "../shared/format";
import { QueryError } from "../shared/QueryState";
import { useWorkspace, type AnalyticsPeriodMode } from "../stores/WorkspaceProvider";
import { attachRateLabels, employeeRatingReason } from "./rating-ui";

function signedNumber(value: number | null, suffix = ""): string {
  if (value == null) return "—";
  return `${value > 0 ? "+" : ""}${formatNumber(value)}${suffix}`;
}

function changeTone(value: number | null): string {
  if (value == null || value === 0) return "neutral";
  return value > 0 ? "positive" : "negative";
}

function payrollStatusLabel(status: string): string {
  if (status === "CALCULATED") return "Черновик";
  if (status === "APPROVED") return "Утвержден";
  if (status === "PAID") return "Выплачен";
  return "Статус неизвестен";
}

interface EmployeeAttachComparisonProps {
  currentLabel: string;
  previousLabel: string;
  currentRate: number | null;
  previousRate: number | null;
  storeRate: number | null;
  change: number | null;
}

export function EmployeeAttachComparison({
  currentLabel,
  previousLabel,
  currentRate,
  previousRate,
  storeRate,
  change
}: EmployeeAttachComparisonProps) {
  return (
    <dl className="employee-attach-metrics">
      <div>
        <dt>{currentLabel}</dt>
        <dd>{formatPercent(currentRate)}</dd>
      </div>
      <div>
        <dt>{previousLabel}</dt>
        <dd>{formatPercent(previousRate)}</dd>
      </div>
      <div>
        <dt>Изменение</dt>
        <dd className={`text-${changeTone(change)}`}>
          {signedNumber(change, " п.п.")}
        </dd>
      </div>
      <div>
        <dt>Магазин</dt>
        <dd>{formatPercent(storeRate)}</dd>
      </div>
    </dl>
  );
}

function CardSkeleton() {
  return <div className="employee-card-skeleton" aria-busy="true" aria-label="Загрузка карточки сотрудника"><span className="skeleton skeleton--banner" /><div className="employee-card-stat-grid">{Array.from({ length: 4 }, (_, index) => <span className="skeleton employee-summary-skeleton" key={index} />)}</div><div className="employee-card-layout"><span className="skeleton skeleton--panel" /><span className="skeleton skeleton--panel" /></div></div>;
}

export function EmployeeCardPage() {
  const { employeeId = "" } = useParams();
  const location = useLocation();
  const { selectedStore, periodStart, periodEnd, periodMode } = useWorkspace();
  const storeId = selectedStore.id;
  const comparisonMode = comparisonModeForPeriod(periodMode);
  const cardQuery = useQuery({
    queryKey: queryKeys.employeeCard(storeId, employeeId, periodStart, periodEnd, comparisonMode),
    queryFn: () => getEmployeeCard(storeId, employeeId, periodStart, periodEnd, comparisonMode),
    enabled: Boolean(employeeId)
  });

  if (cardQuery.isPending) return <CardSkeleton />;
  if (cardQuery.isError) return <QueryError error={cardQuery.error} onRetry={() => void cardQuery.refetch()} />;

  const card = cardQuery.data;
  const employee = card.current;
  const previous = card.previous;
  const currentComparisonLabel = periodMode === "WEEK" ? "Текущая неделя" : "Текущий период";
  const previousComparisonLabel = periodMode === "WEEK" ? "Прошлая неделя" : "Прошлый период";
  const comparisonDescription = periodMode === "WEEK"
    ? "Неделя к неделе"
    : "Сравнение с предыдущим равным периодом";
  const scoreRows = [
    { label: "Коммерческий вклад", description: "Выручка относительно средней выручки участников", score: employee.scores.contributionScore, points: employee.scores.contributionWeightedPoints, weight: card.formula.contributionWeight },
    { label: "Эффективность времени", description: "Выручка за фактически отработанный час", score: employee.scores.efficiencyScore, points: employee.scores.efficiencyWeightedPoints, weight: card.formula.efficiencyWeight },
    { label: "Структура продаж", description: "Выполнение долей аксессуаров и услуг", score: employee.scores.structureScore, points: employee.scores.structureWeightedPoints, weight: card.formula.structureWeight },
    { label: "Интенсивность допродаж", description: "Частота допродаж относительно базы магазина", score: employee.scores.attachScore, points: employee.scores.attachWeightedPoints, weight: card.formula.attachWeight }
  ];

  return (
    <div className="employee-card-page">
      <Link className="back-link" to={{ pathname: "/employees", search: location.search }}><ArrowLeft size={16} />К списку сотрудников</Link>
      <header className="employee-card-header">
        <div className="employee-card-header__identity"><span>{employee.displayName.slice(0, 1).toUpperCase()}</span><div><h1>{employee.displayName}</h1><div className="employee-card-statuses"><span className={`status status--${employee.employeeActive && employee.assignmentActive ? "success" : "warning"}`}>{employee.employeeActive && employee.assignmentActive ? "Активен" : "Неактивен"}</span><span className={`status status--${employee.participatesInRanking ? "success" : "warning"}`}>{employee.participatesInRanking ? "Участвует в рейтинге" : "Вне рейтинга"}</span></div></div></div>
        <div className="employee-card-header__period"><small>{currentComparisonLabel}</small><strong>{formatDate(card.periodStart)} — {formatDate(card.periodEnd)}</strong><span>{previousComparisonLabel}: {formatDate(card.previousPeriodStart)} — {formatDate(card.previousPeriodEnd)}</span></div>
      </header>

      <section className="employee-card-stat-grid" aria-label="Основные показатели сотрудника">
        <article className="employee-card-stat employee-card-stat--rank"><span><Trophy /></span><div><small>Место</small><strong>{employee.rank ?? "—"}</strong><p>{employeeRatingReason(employee, card.formula.minimumCoveragePercent)}</p></div><i className={`change-chip change-chip--${changeTone(card.dynamics.rankImprovement)}`}>{card.dynamics.rankImprovement == null ? "нет сравнения" : card.dynamics.rankImprovement === 0 ? "без изменений" : `${card.dynamics.rankImprovement > 0 ? "↑" : "↓"} ${Math.abs(card.dynamics.rankImprovement)}`}</i></article>
        <article className="employee-card-stat"><span><BarChart3 /></span><div><small>Общий балл</small><strong>{formatNumber(employee.scores.overallScore)}</strong><p>Покрытие {formatPercent(employee.scores.coveragePercent)}</p></div><i className={`change-chip change-chip--${changeTone(card.dynamics.overallScoreChange)}`}>{signedNumber(card.dynamics.overallScoreChange, " п.")}</i></article>
        <article className="employee-card-stat"><span><CircleDollarSign /></span><div><small>Чистая выручка</small><strong>{formatMoney(employee.netRevenue)}</strong><p>{formatPercent(employee.storeRevenueSharePercent)} магазина</p></div><i className={`change-chip change-chip--${changeTone(card.dynamics.revenueChange)}`}>{card.dynamics.revenueChange == null ? "—" : `${card.dynamics.revenueChange > 0 ? "+" : ""}${formatCompactMoney(card.dynamics.revenueChange)}`}</i></article>
        <article className="employee-card-stat"><span><Clock3 /></span><div><small>Эффективность</small><strong>{formatMoney(employee.revenuePerHour)}</strong><p>за час, {employee.shiftCount} смен</p></div><i className={`change-chip change-chip--${changeTone(card.dynamics.revenuePerHourChange)}`}>{card.dynamics.revenuePerHourChange == null ? "—" : `${card.dynamics.revenuePerHourChange > 0 ? "+" : ""}${formatCompactMoney(card.dynamics.revenuePerHourChange)}`}</i></article>
      </section>

      <div className="employee-card-layout">
        <div className="employee-card-main">
          <section className="panel score-breakdown-panel">
            <div className="panel__heading"><h2>Результат по направлениям</h2></div>
            <div className="score-breakdown-list">{scoreRows.map((row) => (
              <article key={row.label}>
                <div><strong>{row.label}</strong><small>{row.description}</small></div>
                <div className="score-breakdown-meter"><progress value={Math.max(0, row.score ?? 0)} max={card.formula.scoreCap} aria-label={`${row.label}: ${formatNumber(row.score)}`} /><span><strong>{formatNumber(row.score)}</strong><small>{formatNumber(row.points)} п., вес {formatPercent(row.weight)}</small></span></div>
              </article>
            ))}</div>
          </section>

          <section className="panel employee-structure-panel">
            <div className="panel__heading"><div><p className="eyebrow">Структура</p><h2>Продажи и доли</h2></div><span>{comparisonDescription}; изменение в процентных пунктах</span></div>
            <div className="employee-comparison-head"><span>Показатель</span><span>{currentComparisonLabel}</span><span>{previousComparisonLabel}</span><span>Изменение</span></div>
            {[
              ["Аксессуары", employee.accessoryRevenue, employee.accessorySharePercent, previous?.accessoryRevenue, previous?.accessorySharePercent, card.dynamics.accessoryShareChange],
              ["Услуги", employee.serviceRevenue, employee.serviceSharePercent, previous?.serviceRevenue, previous?.serviceSharePercent, card.dynamics.serviceShareChange],
              ["Дополнительная выручка", employee.additionalRevenue, employee.additionalSharePercent, previous?.additionalRevenue, previous?.additionalSharePercent, card.dynamics.additionalShareChange]
            ].map(([label, currentRevenue, currentShare, previousRevenue, previousShare, change]) => (
              <article className="employee-comparison-row" key={String(label)}><strong>{label}</strong><span>{formatMoney(currentRevenue as number)}<small>{formatPercent(currentShare as number | null)}</small></span><span>{formatMoney(previousRevenue as number)}<small>{formatPercent(previousShare as number | null)}</small></span><i className={`change-chip change-chip--${changeTone(change as number | null)}`}>{signedNumber(change as number | null, " п.п.")}</i></article>
            ))}
          </section>

          <section className="panel employee-attach-panel">
            <div className="panel__heading">
              <div>
                <p className="eyebrow">Attach-rate</p>
                <h2>Показатели допродаж сотрудника</h2>
              </div>
              <span>{comparisonDescription} · значение магазина за текущий период</span>
            </div>
            {employee.attachRates.length === 0 ? (
              <div className="panel-empty">
                <Link2 size={24} />
                <strong>Нет данных о допродажах</strong>
                <p>Для выбранного периода не сформированы релевантные базы устройств.</p>
              </div>
            ) : (
              <div className="employee-attach-list">
                {employee.attachRates.map((rate) => {
                  const dynamics = card.dynamics.attachRateChanges.find(
                    (item) => item.metricCode === rate.metricCode
                  );
                  return (
                    <article key={rate.metricCode}>
                      <div className="employee-attach-name">
                        <strong>
                          {attachRateLabels[rate.metricCode] ?? "Другой показатель"}
                        </strong>
                        <small>
                          {formatNumber(rate.numeratorQuantity ?? rate.numeratorReceiptCount)} на{" "}
                          {formatNumber(rate.denominatorQuantity ?? rate.denominatorReceiptCount)} единиц техники
                        </small>
                      </div>
                      <EmployeeAttachComparison
                        currentLabel={currentComparisonLabel}
                        previousLabel={previousComparisonLabel}
                        currentRate={rate.ratePercent}
                        previousRate={dynamics?.previousRate ?? null}
                        storeRate={rate.storeRatePercent}
                        change={dynamics?.change ?? null}
                      />
                      <i className={`employee-attach-status status status--${rate.includedInScore ? "success" : "warning"}`}>
                        {rate.includedInScore
                          ? `В балле, ${formatNumber(rate.score)}`
                          : "Не входит в балл"}
                      </i>
                    </article>
                  );
                })}
              </div>
            )}
          </section>
        </div>

        <aside className="employee-card-aside">
          <section className="panel employee-context-panel"><span className="context-icon"><Target /></span><p className="eyebrow">План магазина</p><h2>{card.plan.complete ? formatPercent(card.plan.revenueAchievementPercent) : "Неполный план"}</h2><p>{formatMoney(card.plan.actualStoreRevenue)} из {formatMoney(card.plan.proratedRevenueTarget)}</p><dl><div><dt>Аксессуары</dt><dd>{formatPercent(card.plan.accessoryShareTarget)}</dd></div><div><dt>Услуги</dt><dd>{formatPercent(card.plan.serviceShareTarget)}</dd></div><div><dt>Доп. выручка</dt><dd>{formatPercent(card.plan.additionalShareTarget)}</dd></div></dl><small><Info size={13} />План общий для магазина, персональных планов нет.</small></section>
          <section className="panel employee-context-panel"><span className="context-icon"><CalendarDays /></span><p className="eyebrow">Рабочее время</p><h2>{formatNumber(employee.workedHours)} ч</h2><p>{employee.shiftCount} смен, {formatMoney(employee.revenuePerShift)} за смену</p><dl><div><dt>Прошлый период</dt><dd>{formatNumber(previous?.workedHours)} ч</dd></div><div><dt>Выручка / час</dt><dd>{formatMoney(employee.revenuePerHour)}</dd></div></dl></section>
          <section className="panel employee-context-panel employee-payroll-card">
            <span className="context-icon"><WalletCards /></span><p className="eyebrow">Зарплата</p>
            {card.payroll ? <>
              <h2>{formatMoney(card.payroll.statement.payableAmount)}</h2>
              <p>К выплате, версия {card.payroll.run.revision}</p>
              <dl>
                <div><dt>Начислено</dt><dd>{formatMoney(card.payroll.statement.earnedAmount)}</dd></div>
                <div><dt>Аванс</dt><dd>{formatMoney(card.payroll.statement.advanceAmount)}</dd></div>
                <div><dt>Удержания</dt><dd>{formatMoney(card.payroll.statement.penaltyAmount + card.payroll.statement.inventoryAmount + card.payroll.statement.taxAmount)}</dd></div>
                <div><dt>Статус</dt><dd>{payrollStatusLabel(card.payroll.run.status)}</dd></div>
              </dl>
              {card.payroll.run.freshness.requiresRecalculation && <p className="employee-payroll-warning">Расчет устарел и требует пересчета.</p>}
              <Link className="context-link" to={{ pathname: "/payroll", search: location.search }}>Открыть ведомость</Link>
            </> : <>
              <h2>{periodMode === "MONTH" ? "Нет расчета" : "Только за полный месяц"}</h2>
              <p>{periodMode === "MONTH" ? "Для выбранного месяца ведомость сотрудника не рассчитана." : "Выберите календарный месяц, чтобы увидеть начисления и сумму к выплате."}</p>
            </>}
            <small><CheckCircle2 size={13} />Рейтинг не влияет на формулу зарплаты.</small>
          </section>
        </aside>
      </div>
    </div>
  );
}

export function comparisonModeForPeriod(mode: AnalyticsPeriodMode): EmployeeComparisonMode {
  return mode === "WEEK" ? "PREVIOUS_WEEK" : "PREVIOUS_PERIOD";
}
