import { useQuery } from "@tanstack/react-query";
import { AlertCircle, ArrowRight, CheckCircle2, Package, RefreshCw, ShieldCheck, Smartphone, Target, TrendingUp, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";
import {
  getAttachRates,
  getAverageKpi,
  getCategoryKpi,
  getPeriodQuality,
  getPlanProgress,
  getStoreKpi,
  getStoreStatus,
  queryKeys
} from "../api/queries";
import { DailyPlanTable } from "../plan-schedule/DailyPlanTable";
import type { PlanDirection } from "../api/contracts";
import { averageGrossProfitPerDeviceUnit } from "./categoryPresentation";
import { qualityIssueMessage, qualityStatusLabel } from "../quality/presentation";
import { formatDate, formatMonth } from "../shared/date";
import { formatCompactMoney, formatMoney, formatNumber, formatPercent } from "../shared/format";
import { Delta, PanelSkeleton, QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";

const groupLabels: Record<string, { label: string; icon: ReactNode }> = {
  PHONES: { label: "Телефоны", icon: <Smartphone size={18} /> },
  DEVICES: { label: "Все устройства", icon: <Package size={18} /> },
  ADDITIONAL_REVENUE: { label: "Дополнительная выручка", icon: <TrendingUp size={18} /> }
};

const directionLabels: Record<string, string> = {
  REVENUE: "Выручка",
  ACCESSORY: "Аксессуары",
  SERVICE: "Услуги",
  ADDITIONAL: "Дополнительная выручка"
};

const directionStatusLabels: Record<string, string> = {
  ACHIEVED: "Выполнено",
  ON_TRACK: "По графику",
  AT_RISK: "Есть риск",
  MISSED: "Не выполнено",
  NOT_AVAILABLE: "Недостаточно данных"
};

const freshnessLabels: Record<string, string> = {
  CURRENT: "Данные актуальны",
  SYNCING: "Идет синхронизация",
  STALE: "Данные отстают",
  ERROR: "Ошибка синхронизации",
  NOT_SYNCED: "Данные не загружены"
};

const attachLabels: Record<string, string> = {
  ACCESSORY_IPAD: "Аксессуары к iPad",
  ACCESSORY_PODS_WATCH: "Аксессуары к Pods / Watch",
  CASE_APPLE_IPHONE: "Чехлы Apple / iPhone",
  CASE_SAMSUNG: "Чехлы Samsung",
  CHARGER_CABLE: "Зарядные устройства и кабели",
  FILM_PHONE: "Защитные пленки",
  GLASS_IPHONE: "Защитное стекло iPhone",
  GLASS_CAMERA_IPHONE: "Защита камеры iPhone",
  GLASS_SAMSUNG: "Защитное стекло Samsung",
  GLASS_CAMERA_SAMSUNG: "Защита камеры Samsung",
  PREMIUM_PROTECTION: "Протекция",
  SETUP_SERVICE: "Настройки и услуги",
  WARRANTY_GENERIC_NEW: "Гарантии — новые устройства",
  WARRANTY_GENERIC_USED: "Гарантии — устройства Б/У"
};

function toneForStatus(status: string): string {
  if (["CURRENT", "OK", "ACHIEVED", "ON_TRACK"].includes(status)) return "success";
  if (["ERROR", "MISSED", "NOT_SYNCED"].includes(status)) return "danger";
  return "warning";
}

function SummaryMetric({ label, value, note, children }: { label: string; value: string; note?: string; children?: ReactNode }) {
  return (
    <article className="overview-summary__metric">
      <span>{label}</span>
      <strong>{value}</strong>
      {note && <small>{note}</small>}
      {children}
    </article>
  );
}

function PlanSummary({ direction }: { direction: PlanDirection | null }) {
  if (!direction) return <SummaryMetric label="План" value="Не задан" note="Нужна цель на месяц" />;

  const completion = direction.criterionCompletionPercent;
  return (
    <SummaryMetric
      label="План"
      value={formatPercent(completion)}
      note={`${formatCompactMoney(direction.actualAmount)} из ${formatCompactMoney(direction.targetAmount)}`}
    >
      <span className={`status status--${toneForStatus(direction.status)}`}>{directionStatusLabels[direction.status] ?? "Неизвестный статус"}</span>
    </SummaryMetric>
  );
}

function OverviewSkeleton() {
  return (
    <div className="overview-skeleton" aria-label="Загружаем показатели" aria-busy="true">
      <span className="skeleton skeleton--banner" />
      <span className="skeleton skeleton--summary" />
      <div className="overview-grid"><span className="skeleton skeleton--panel" /><span className="skeleton skeleton--panel" /></div>
    </div>
  );
}

export function OverviewPage() {
  const { selectedStore, month, periodStart, periodEnd, asOfDate } = useWorkspace();
  const storeId = selectedStore.id;

  const statusQuery = useQuery({
    queryKey: queryKeys.storeStatus(storeId),
    queryFn: () => getStoreStatus(storeId),
    refetchOnWindowFocus: true,
    refetchInterval: (query) => query.state.data?.status === "SYNCING" ? 10_000 : false
  });
  const kpiQuery = useQuery({ queryKey: queryKeys.storeKpi(storeId, periodStart, periodEnd), queryFn: () => getStoreKpi(storeId, periodStart, periodEnd) });
  const categoriesQuery = useQuery({ queryKey: queryKeys.categories(storeId, periodStart, periodEnd), queryFn: () => getCategoryKpi(storeId, periodStart, periodEnd) });
  const averagesQuery = useQuery({ queryKey: queryKeys.averages(storeId, periodStart, periodEnd), queryFn: () => getAverageKpi(storeId, periodStart, periodEnd) });
  const planQuery = useQuery({ queryKey: queryKeys.planProgress(storeId, month, asOfDate), queryFn: () => getPlanProgress(storeId, month, asOfDate) });
  const qualityQuery = useQuery({ queryKey: queryKeys.periodQuality(storeId, month, asOfDate), queryFn: () => getPeriodQuality(storeId, month, asOfDate) });
  const attachQuery = useQuery({ queryKey: queryKeys.attachRates(storeId, periodStart, periodEnd), queryFn: () => getAttachRates(storeId, periodStart, periodEnd), staleTime: 2 * 60_000 });

  const criticalQueries = [statusQuery, kpiQuery, categoriesQuery, averagesQuery, planQuery, qualityQuery];
  if (criticalQueries.every((query) => query.isPending)) return <OverviewSkeleton />;

  const criticalError = criticalQueries.find((query) => query.isError);
  if (criticalError) {
    return <QueryError error={criticalError.error} onRetry={() => void Promise.all(criticalQueries.map((query) => query.refetch()))} />;
  }

  const status = statusQuery.data;
  const kpi = kpiQuery.data;
  const categories = categoriesQuery.data;
  const averages = averagesQuery.data;
  const plan = planQuery.data;
  const quality = qualityQuery.data;
  const revenueDirection = plan?.directions.find((direction) => direction.code === "REVENUE") ?? null;
  const additionalGroup = categories?.groups.find((group) => group.groupCode === "ADDITIONAL_REVENUE");
  const freshnessTone = toneForStatus(status?.status ?? "WARNING");

  return (
    <div className="overview-page">
      <header className="page-heading">
        <div><h1>Обзор</h1><p>{formatMonth(month)}, {formatDate(periodStart)} — {formatDate(periodEnd)}</p></div>
        <div className="page-heading__period"><small>Данные по</small><strong>{formatDate(asOfDate)}</strong></div>
      </header>

      {status && (
        <section className={`freshness-banner freshness-banner--${freshnessTone} ${status.status === "CURRENT" ? "freshness-banner--quiet" : ""}`} aria-live="polite">
          <span className="freshness-banner__icon">{freshnessTone === "success" ? <ShieldCheck /> : <TriangleAlert />}</span>
          <div>
            <strong>{freshnessLabels[status.status] ?? "Статус неизвестен"}</strong>
            <p>{status.dataThroughDate ? `По ${formatDate(status.dataThroughDate)}` : "Дата покрытия неизвестна"}{status.lagDays ? `, отставание ${status.lagDays} дн.` : ""}</p>
          </div>
          {status.openQualityIssueCount > 0 && <span className="freshness-banner__issues"><AlertCircle size={16} />{status.openQualityIssueCount}</span>}
          {status.synchronization.active && <span className="freshness-banner__sync"><RefreshCw size={15} />Обновление</span>}
        </section>
      )}

      {quality && !quality.readyForDecisions && (
        <section className="decision-banner" aria-label="Готовность данных для решений">
          <TriangleAlert size={20} />
          <div><strong>Данные требуют проверки</strong><p>{quality.issues.filter((issue) => issue.severity === "ERROR").length} важных замечаний</p></div>
          <a href="#quality-details">Открыть <ArrowRight size={15} /></a>
        </section>
      )}

      <section className="overview-summary" aria-label="Главные показатели">
        <article className="overview-summary__primary">
          <span>Чистая выручка</span>
          <strong>{formatMoney(kpi?.netRevenue)}</strong>
          <div><span>{formatNumber(kpi?.netQuantity)} ед.</span><span>Себестоимость {formatMoney(kpi?.costAmount)}</span></div>
        </article>
        <div className="overview-summary__metrics">
          <PlanSummary direction={revenueDirection} />
          <SummaryMetric label="Валовая прибыль" value={formatMoney(kpi?.grossProfit)} note={`Маржа ${formatPercent(kpi?.marginPercent)}`}>
            {!kpi?.dataQuality.completeCostData && <span className="quality-warning"><AlertCircle size={14} />Данные неполные</span>}
          </SummaryMetric>
          <SummaryMetric label="Средний чек" value={formatMoney(averages?.averageReceipt.current.value)} note={`${formatNumber(averages?.averageReceipt.current.denominator)} чеков`}>
            <Delta value={averages?.averageReceipt.changePercent} />
          </SummaryMetric>
          <SummaryMetric label="Допродажи" value={formatMoney(additionalGroup?.metrics.netRevenue)} note={`${formatMoney(averages?.additionalRevenuePerPhone.current.value)} на телефон`}>
            <Delta value={averages?.additionalRevenuePerPhone.changePercent} />
          </SummaryMetric>
        </div>
      </section>

      <div className="overview-grid">
        <section className="panel groups-panel">
          <div className="panel__heading"><h2>Структура продаж</h2></div>
          <div className="group-list">
            {categories?.groups.map((group) => {
              const info = groupLabels[group.groupCode] ?? { label: group.groupName, icon: <Package size={18} /> };
              return (
                <article key={group.groupCode} className="group-row">
                  <span className="group-row__icon">{info.icon}</span>
                  <div><strong>{info.label}</strong><small>{formatNumber(group.metrics.netQuantity)} ед.</small></div>
                  <div className="group-row__values"><strong>{formatMoney(group.metrics.netRevenue)}</strong><small>маржа {formatPercent(group.metrics.marginPercent)}</small></div>
                </article>
              );
            })}
          </div>
        </section>

        <section className="panel plan-panel">
          <div className="panel__heading"><h2>План месяца</h2>{plan && <span>{plan.achievedDirectionCount} из {plan.directions.length}</span>}</div>
          {!plan ? (
            <div className="panel-empty"><Target size={24} /><strong>План не задан</strong><p>Задайте цели на месяц.</p></div>
          ) : (
            <div className="direction-list">
              {plan.directions.map((direction) => {
                const completion = direction.criterionCompletionPercent;
                return (
                  <article key={direction.code} className="direction-row">
                    <div className="direction-row__top"><strong>{directionLabels[direction.code] ?? "Другое направление"}</strong><span className={`status status--${toneForStatus(direction.status)}`}>{directionStatusLabels[direction.status] ?? "Неизвестный статус"}</span></div>
                    <progress className="progress" value={Math.max(0, completion ?? 0)} max={100} aria-label={`Выполнение направления ${directionLabels[direction.code] ?? "Другое направление"}`} />
                    <div className="direction-row__meta"><span>{formatPercent(completion)} критерия</span><span>прогноз {formatCompactMoney(direction.projectedAmount)}</span></div>
                  </article>
                );
              })}
            </div>
          )}
        </section>
      </div>

      {plan && <DailyPlanTable targets={plan.dailyTargets} />}

      <section className="overview-details" aria-label="Подробные показатели">
        <details className="disclosure-panel">
          <summary><span>Категории продаж</span><small>{categories?.categories.length ?? 0}</small></summary>
          <div className="disclosure-panel__content table-scroll">
            <table>
              <thead><tr><th>Категория</th><th>Выручка</th><th>Количество</th><th>Валовая прибыль</th><th>Вал / ед. техники</th><th>Маржа</th><th>Качество</th></tr></thead>
              <tbody>
                {categories?.categories.map((category) => (
                  <tr key={category.categoryCode} className={!category.categoryActive ? "row-muted" : ""}>
                    <td><strong>{category.categoryName}</strong></td>
                    <td>{formatMoney(category.metrics.netRevenue)}</td>
                    <td>{formatNumber(category.metrics.netQuantity)}</td>
                    <td>{formatMoney(category.metrics.grossProfit)}</td>
                    <td>{formatMoney(averageGrossProfitPerDeviceUnit(category))}</td>
                    <td>{formatPercent(category.metrics.marginPercent)}</td>
                    <td>{category.metrics.dataQuality.completeCostData ? <span className="quality-ok"><CheckCircle2 size={14} />Полные</span> : <span className="quality-warning"><AlertCircle size={14} />Проверьте</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>

        <details className="disclosure-panel" open>
          <summary><span>Attach-rate · показатели допродаж</span><small>{attachQuery.data?.rates.length ?? 0}</small></summary>
          <div className="disclosure-panel__content">
            <div className="attach-intro">
              <strong>Количество допов на 100 единиц релевантной техники</strong>
              <span>
                Каждая проданная единица учитывается отдельно; возвраты уменьшают результат.
              </span>
            </div>
            {attachQuery.isPending && <PanelSkeleton rows={5} />}
            {attachQuery.isError && <QueryError error={attachQuery.error} onRetry={() => void attachQuery.refetch()} compact />}
            {attachQuery.data && (
              <div className="attach-list">
                {attachQuery.data.rates.map((rate) => (
                  <article key={rate.metricCode}>
                    <div><strong>{attachLabels[rate.metricCode] ?? "Другой показатель"}</strong><small>{formatNumber(rate.numeratorQuantity ?? rate.numeratorReceiptCount)} на {formatNumber(rate.denominatorQuantity ?? rate.denominatorReceiptCount)} единиц техники</small></div>
                    <span>{formatPercent(rate.ratePerHundred)}</span>
                  </article>
                ))}
              </div>
            )}
          </div>
        </details>

        <details id="quality-details" className="disclosure-panel" open={Boolean(quality?.issues.length)}>
          <summary><span>Качество данных</span>{quality && <small className={`status status--${toneForStatus(quality.status)}`}>{qualityStatusLabel(quality.status)}</small>}</summary>
          <div className="disclosure-panel__content">
            {quality?.issues.length === 0 ? (
              <div className="disclosure-empty"><ShieldCheck size={20} /><span>Критичных замечаний нет</span></div>
            ) : (
              <div className="quality-list">
                {quality?.issues.slice(0, 5).map((issue) => (
                  <article key={issue.key}>
                    <span className={`quality-list__icon quality-list__icon--${toneForStatus(issue.severity)}`}>{issue.severity === "ERROR" ? <AlertCircle size={17} /> : <TriangleAlert size={17} />}</span>
                    <div><strong>{qualityIssueMessage(issue.code)}</strong>{issue.affectedCount != null && <small>Затронуто: {issue.affectedCount}</small>}</div>
                  </article>
                ))}
              </div>
            )}
          </div>
        </details>
      </section>
    </div>
  );
}
