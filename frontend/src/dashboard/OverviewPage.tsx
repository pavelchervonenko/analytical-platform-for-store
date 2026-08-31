import { useQuery } from "@tanstack/react-query";
import { AlertCircle, ArrowRight, CheckCircle2, Package, RefreshCw, ShieldCheck, Smartphone, Target, TrendingUp, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";
import { useSearchParams } from "react-router";
import type { CategoryKpi, OverviewMetricScope } from "../api/contracts";
import {
  getAttachRates,
  getCategoryKpi,
  getEmployeeKpi,
  getEmployeeRating,
  getOverviewMetrics,
  getPeriodQuality,
  getPlanProgress,
  getStoreStatus,
  queryKeys
} from "../api/queries";
import { averageGrossProfitPerDeviceUnit } from "./categoryPresentation";
import { qualityIssueMessage, qualityStatusLabel } from "../quality/presentation";
import { formatDate } from "../shared/date";
import { formatCompactMoney, formatMoney, formatNumber, formatPercent } from "../shared/format";
import { PanelSkeleton, QueryError } from "../shared/QueryState";
import { useWorkspace, type AnalyticsPeriodMode } from "../stores/WorkspaceProvider";
import { AttachRateMatrix, EmployeePerformanceSection, ManagementSummary } from "./OverviewManagementSections";

const groupLabels: Record<string, { label: string; icon: ReactNode }> = {
  PHONES: { label: "Телефоны", icon: <Smartphone size={18} /> },
  DEVICES: { label: "Техника", icon: <Package size={18} /> },
  ACCESSORY: { label: "Аксессуары", icon: <Package size={18} /> },
  SERVICE: { label: "Услуги", icon: <ShieldCheck size={18} /> },
  ADDITIONAL_REVENUE: { label: "Дополнительная выручка", icon: <TrendingUp size={18} /> }
};

type SalesGroup = CategoryKpi["groups"][number];

function SalesGroupRow({ group, relation }: { group: SalesGroup; relation: string }) {
  const info = groupLabels[group.groupCode] ?? { label: group.groupName, icon: <Package size={18} /> };
  return (
    <article className={`group-row ${relation === "В том числе" ? "group-row--child" : "group-row--total"}`}>
      <span className="group-row__icon">{info.icon}</span>
      <div className="group-row__copy">
        <span className="group-row__relation">{relation}</span>
        <strong>{info.label}</strong>
        <small>{formatNumber(group.metrics.netQuantity)} ед.</small>
      </div>
      <div className="group-row__values"><strong>{formatMoney(group.metrics.netRevenue)}</strong><small>маржа {formatPercent(group.metrics.marginPercent)}</small></div>
    </article>
  );
}

export function SalesStructure({ groups }: { groups: SalesGroup[] }) {
  const byCode = new Map(groups.map((group) => [group.groupCode, group]));
  const devices = byCode.get("DEVICES");
  const phones = byCode.get("PHONES");
  const additionalRevenue = byCode.get("ADDITIONAL_REVENUE");
  const accessory = byCode.get("ACCESSORY");
  const service = byCode.get("SERVICE");
  const structuredCodes = new Set(["DEVICES", "PHONES", "ADDITIONAL_REVENUE", "ACCESSORY", "SERVICE"]);
  const otherGroups = groups.filter((group) => !structuredCodes.has(group.groupCode));

  return (
    <div className="group-list group-list--hierarchical">
      {(devices || phones) && (
        <section className="group-branch" aria-label="Техника и её состав">
          {devices && <SalesGroupRow group={devices} relation="Итого по категории" />}
          {phones && <SalesGroupRow group={phones} relation="В том числе" />}
        </section>
      )}
      {(additionalRevenue || accessory || service) && (
        <section className="group-branch" aria-label="Дополнительная выручка и её состав">
          {additionalRevenue && <SalesGroupRow group={additionalRevenue} relation="Подытог" />}
          {accessory && <SalesGroupRow group={accessory} relation="В том числе" />}
          {service && <SalesGroupRow group={service} relation="В том числе" />}
        </section>
      )}
      {otherGroups.map((group) => <SalesGroupRow group={group} relation="Отдельная категория" key={group.groupCode} />)}
    </div>
  );
}

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

function toneForStatus(status: string): string {
  if (["CURRENT", "OK", "ACHIEVED", "ON_TRACK"].includes(status)) return "success";
  if (["ERROR", "MISSED", "NOT_SYNCED"].includes(status)) return "danger";
  return "warning";
}

export function formatOverviewPeriodLabel(mode: AnalyticsPeriodMode, label: string): string {
  if (mode !== "MONTH" || label.length === 0) return label;
  return `${label.charAt(0).toLocaleUpperCase("ru-RU")}${label.slice(1)}`;
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
  const { selectedStore, month, periodMode, periodStart, periodEnd, periodLabel, asOfDate } = useWorkspace();
  const [searchParams, setSearchParams] = useSearchParams();
  const storeId = selectedStore.id;
  const metricScope: OverviewMetricScope = searchParams.get("overviewScope") === "STORE"
    ? "STORE"
    : "SELLERS";
  const selectMetricScope = (scope: OverviewMetricScope) => {
    setSearchParams((current) => {
      const next = new URLSearchParams(current);
      next.set("overviewScope", scope);
      return next;
    });
  };

  const statusQuery = useQuery({
    queryKey: queryKeys.storeStatus(storeId),
    queryFn: () => getStoreStatus(storeId),
    refetchOnWindowFocus: true,
    refetchInterval: (query) => query.state.data?.status === "SYNCING" ? 10_000 : false
  });
  const overviewMetricsQuery = useQuery({ queryKey: queryKeys.overviewMetrics(storeId, periodStart, periodEnd, metricScope), queryFn: () => getOverviewMetrics(storeId, periodStart, periodEnd, metricScope) });
  const categoriesQuery = useQuery({ queryKey: queryKeys.categories(storeId, periodStart, periodEnd), queryFn: () => getCategoryKpi(storeId, periodStart, periodEnd) });
  const planQuery = useQuery({ queryKey: queryKeys.planProgress(storeId, month, asOfDate, metricScope), queryFn: () => getPlanProgress(storeId, month, asOfDate, metricScope) });
  const qualityQuery = useQuery({ queryKey: queryKeys.periodQuality(storeId, month, asOfDate), queryFn: () => getPeriodQuality(storeId, month, asOfDate) });
  const attachQuery = useQuery({ queryKey: queryKeys.attachRates(storeId, periodStart, periodEnd), queryFn: () => getAttachRates(storeId, periodStart, periodEnd), staleTime: 2 * 60_000 });

  const employeeRatingQuery = useQuery({ queryKey: queryKeys.employeeRating(storeId, periodStart, periodEnd), queryFn: () => getEmployeeRating(storeId, periodStart, periodEnd), staleTime: 2 * 60_000 });
  const employeeKpiQuery = useQuery({ queryKey: queryKeys.employeeKpi(storeId, periodStart, periodEnd), queryFn: () => getEmployeeKpi(storeId, periodStart, periodEnd), staleTime: 2 * 60_000 });
  const criticalQueries = [statusQuery, overviewMetricsQuery, categoriesQuery, planQuery, qualityQuery];
  if (criticalQueries.every((query) => query.isPending)) return <OverviewSkeleton />;

  const criticalError = criticalQueries.find((query) => query.isError);
  if (criticalError) {
    return <QueryError error={criticalError.error} onRetry={() => void Promise.all(criticalQueries.map((query) => query.refetch()))} />;
  }

  const status = statusQuery.data;
  const overviewMetrics = overviewMetricsQuery.data;
  const categories = categoriesQuery.data;
  const plan = planQuery.data;
  const quality = qualityQuery.data;
  const freshnessTone = toneForStatus(status?.status ?? "WARNING");

  return (
    <div className="overview-page">
      <header className="page-heading">
        <div><h1>Обзор</h1><p>{formatOverviewPeriodLabel(periodMode, periodLabel)}</p></div>
        <div className="page-heading__period"><small>Данные по</small><strong>{formatDate(asOfDate)}</strong></div>
      </header>

      {status && (
        <section className={`freshness-banner freshness-banner--${freshnessTone} ${status.status === "CURRENT" ? "freshness-banner--quiet" : ""}`} aria-live="polite">
          <span className="freshness-banner__icon">{freshnessTone === "success" ? <ShieldCheck /> : <TriangleAlert />}</span>
          <div>
            <strong>{freshnessLabels[status.status] ?? "Статус неизвестен"}</strong>
            <p>{status.dataThroughDate ? `По ${formatDate(status.dataThroughDate)}` : "Дата покрытия неизвестна"}{status.lagDays ? `, отставание ${status.lagDays} дн.` : ""}</p>
          </div>
          {status.synchronization.active && <span className="freshness-banner__sync"><RefreshCw size={15} />Обновление</span>}
          {status.status !== "CURRENT" && quality && !quality.readyForDecisions && <a className="freshness-banner__action" href="#quality-details">Проверить <ArrowRight size={15} /></a>}
        </section>
      )}

      {quality && !quality.readyForDecisions && status?.status === "CURRENT" && (
        <section className="decision-banner" aria-label="Готовность данных для решений">
          <TriangleAlert size={20} />
          <div><strong>Данные требуют проверки</strong><p>{quality.issues.filter((issue) => issue.severity === "ERROR").length} важных замечаний</p></div>
          <a href="#quality-details">Открыть <ArrowRight size={15} /></a>
        </section>
      )}

      <ManagementSummary
        metrics={overviewMetrics}
        plan={plan}
        scope={metricScope}
        onScopeChange={selectMetricScope}
        showMonthlyPlan={periodMode === "MONTH"}
      />

      <div className="overview-grid">
        <section className="panel groups-panel">
          <div className="panel__heading"><h2>Структура продаж — {metricScope === "SELLERS" ? "только продавцы" : "весь магазин"}</h2></div>
          <SalesStructure groups={overviewMetrics?.salesGroups ?? []} />
        </section>

        <section className="panel plan-panel">
          <div className="panel__heading"><h2>План месяца — {metricScope === "SELLERS" ? "только продавцы" : "весь магазин"}</h2>{plan && <span>{plan.achievedDirectionCount} из {plan.directions.length}</span>}</div>
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

      {employeeRatingQuery.isPending || employeeKpiQuery.isPending || (!employeeRatingQuery.isError && !employeeRatingQuery.data) || (!employeeKpiQuery.isError && !employeeKpiQuery.data) ? (
        <section className="panel overview-team-panel"><PanelSkeleton rows={5} /></section>
      ) : employeeRatingQuery.isError || employeeKpiQuery.isError ? (
        <QueryError
          error={employeeRatingQuery.error ?? employeeKpiQuery.error}
          onRetry={() => void Promise.all([employeeRatingQuery.refetch(), employeeKpiQuery.refetch()])}
          compact
        />
      ) : (
        <EmployeePerformanceSection rating={employeeRatingQuery.data} employeeKpi={employeeKpiQuery.data} />
      )}

      {attachQuery.isPending || employeeRatingQuery.isPending || (!attachQuery.isError && !attachQuery.data) || (!employeeRatingQuery.isError && !employeeRatingQuery.data) ? (
        <section className="panel attach-map-panel"><PanelSkeleton rows={8} /></section>
      ) : attachQuery.isError || employeeRatingQuery.isError ? (
        <QueryError
          error={attachQuery.error ?? employeeRatingQuery.error}
          onRetry={() => void Promise.all([attachQuery.refetch(), employeeRatingQuery.refetch()])}
          compact
        />
      ) : (
        <AttachRateMatrix
          attach={attachQuery.data}
          rating={employeeRatingQuery.data}
          storeName={selectedStore.name}
        />
      )}

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
