import { useQuery } from "@tanstack/react-query";
import { ChevronDown, Package, RefreshCw, ShieldCheck, Smartphone, TrendingUp, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";
import { useSearchParams } from "react-router";
import { hasUserFeature, type CategoryKpi, type OverviewMetricScope } from "../api/contracts";
import {
  getAttachRates,
  getCategoryKpi,
  getEmployeeKpi,
  getEmployeeRating,
  getOverviewMetrics,
  getPlanProgress,
  getStoreStatus,
  queryKeys
} from "../api/queries";
import { useAuth } from "../auth/AuthProvider";
import { averageGrossProfitPerDeviceUnit } from "./categoryPresentation";
import { formatDate } from "../shared/date";
import { formatMoney, formatNumber, formatPercent } from "../shared/format";
import { InlineQueryError, PanelSkeleton, QueryError, StaleDataNote } from "../shared/QueryState";
import { useWorkspace, type AnalyticsPeriodMode } from "../stores/WorkspaceProvider";
import { AttachRateMatrix, EmployeePerformanceSection, ManagementSummary } from "./OverviewManagementSections";
import { OverviewPlanPanel } from "./OverviewPlanPanel";

const groupLabels: Record<string, { label: string; icon: ReactNode }> = {
  PHONES: { label: "Телефоны", icon: <Smartphone size={18} /> },
  DEVICES: { label: "Техника", icon: <Package size={18} /> },
  ACCESSORY: { label: "Аксессуары", icon: <Package size={18} /> },
  SERVICE: { label: "Услуги", icon: <ShieldCheck size={18} /> },
  ADDITIONAL_REVENUE: { label: "Дополнительная выручка", icon: <TrendingUp size={18} /> }
};

type SalesGroup = CategoryKpi["groups"][number];

function SalesGroupRow({ group, nested = false }: { group: SalesGroup; nested?: boolean }) {
  const info = groupLabels[group.groupCode] ?? { label: group.groupName, icon: <Package size={18} /> };
  return (
    <article className={`group-row ${nested ? "group-row--child" : "group-row--total"}`}>
      <span className="group-row__icon">{info.icon}</span>
      <div className="group-row__copy">
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
        <section className="group-branch" aria-label="Техника и ее состав">
          {devices && <SalesGroupRow group={devices} />}
          {phones && <SalesGroupRow group={phones} nested />}
        </section>
      )}
      {(additionalRevenue || accessory || service) && (
        <section className="group-branch" aria-label="Дополнительная выручка и ее состав">
          {additionalRevenue && <SalesGroupRow group={additionalRevenue} />}
          {accessory && <SalesGroupRow group={accessory} nested />}
          {service && <SalesGroupRow group={service} nested />}
        </section>
      )}
      {otherGroups.map((group) => <SalesGroupRow group={group} key={group.groupCode} />)}
    </div>
  );
}

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
  const { user } = useAuth();
  const { selectedStore, periodMode, periodStart, periodEnd, periodLabel, planMonth, planAsOfDate } = useWorkspace();
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
    refetchInterval: (query) => query.state.data?.updating ? 10_000 : false
  });
  const overviewMetricsQuery = useQuery({ queryKey: queryKeys.overviewMetrics(storeId, periodStart, periodEnd, metricScope), queryFn: () => getOverviewMetrics(storeId, periodStart, periodEnd, metricScope) });
  const categoriesQuery = useQuery({ queryKey: queryKeys.categories(storeId, periodStart, periodEnd), queryFn: () => getCategoryKpi(storeId, periodStart, periodEnd) });
  const planAllowed = hasUserFeature(user, "PLAN");
  const planQuery = useQuery({ queryKey: queryKeys.planProgress(storeId, planMonth, planAsOfDate, metricScope), queryFn: () => getPlanProgress(storeId, planMonth, planAsOfDate, metricScope), enabled: planAllowed });
  const attachQuery = useQuery({ queryKey: queryKeys.attachRates(storeId, periodStart, periodEnd), queryFn: () => getAttachRates(storeId, periodStart, periodEnd), staleTime: 2 * 60_000 });

  const employeeRatingQuery = useQuery({ queryKey: queryKeys.employeeRating(storeId, periodStart, periodEnd), queryFn: () => getEmployeeRating(storeId, periodStart, periodEnd), staleTime: 2 * 60_000 });
  const employeeKpiQuery = useQuery({ queryKey: queryKeys.employeeKpi(storeId, periodStart, periodEnd), queryFn: () => getEmployeeKpi(storeId, periodStart, periodEnd), staleTime: 2 * 60_000 });
  const pageQueries = [statusQuery, overviewMetricsQuery, categoriesQuery, attachQuery, employeeRatingQuery, employeeKpiQuery, ...(planAllowed ? [planQuery] : [])];
  if (overviewMetricsQuery.data === undefined && overviewMetricsQuery.isPending) return <OverviewSkeleton />;
  if (overviewMetricsQuery.data === undefined && overviewMetricsQuery.isError) {
    return <QueryError error={overviewMetricsQuery.error} onRetry={() => void overviewMetricsQuery.refetch()} />;
  }
  const staleQuery = pageQueries.find((query) => query.isError && query.data !== undefined);

  const status = statusQuery.data;
  const overviewMetrics = overviewMetricsQuery.data;
  const categories = categoriesQuery.data;
  const plan = planQuery.data;
  const freshnessTone = toneForStatus(status?.status ?? "WARNING");
  const planSearch = new URLSearchParams({ store: storeId, month: planMonth }).toString();

  return (
    <div className="overview-page">
      <header className="page-heading">
        <div><h1>Обзор</h1><p>{formatOverviewPeriodLabel(periodMode, periodLabel)}</p></div>
        <div className="page-heading__period"><small>Данные по</small><strong>{formatDate(periodEnd)}</strong></div>
      </header>

      {staleQuery && <StaleDataNote
        error={staleQuery.error}
        onRetry={() => void Promise.all(pageQueries.filter((query) => query.isError).map((query) => query.refetch()))}
      />}

      {statusQuery.data === undefined && statusQuery.isError && (
        <InlineQueryError error={statusQuery.error} onRetry={() => void statusQuery.refetch()} />
      )}

      {status && status.status !== "CURRENT" && (
        <section className={`freshness-banner freshness-banner--${freshnessTone}`} aria-live="polite">
          <span className="freshness-banner__icon">{freshnessTone === "success" ? <ShieldCheck /> : <TriangleAlert />}</span>
          <div>
            <strong>{freshnessLabels[status.status] ?? "Статус неизвестен"}</strong>
            <p>{status.dataThroughDate ? `По ${formatDate(status.dataThroughDate)}` : "Дата покрытия неизвестна"}{status.lagDays ? `, отставание ${status.lagDays} дн.` : ""}</p>
          </div>
          {status.updating && <span className="freshness-banner__sync"><RefreshCw size={15} />Обновление</span>}
        </section>
      )}

      <ManagementSummary
        metrics={overviewMetrics}
        plan={plan}
        scope={metricScope}
        onScopeChange={selectMetricScope}
        showMonthlyPlan={planAllowed && periodMode === "MONTH" && planQuery.data !== undefined}
      />

      <div className={`overview-grid ${planAllowed ? "" : "overview-grid--single"}`}>
        <section className="panel groups-panel">
          <div className="panel__heading">
            <h2>Структура продаж — {metricScope === "SELLERS" ? "только продавцы" : "весь магазин"}</h2>
          </div>
          <SalesStructure groups={overviewMetrics?.salesGroups ?? []} />
        </section>

        {planAllowed && <OverviewPlanPanel
          scope={metricScope}
          plan={plan}
          isPending={planQuery.isPending}
          isError={planQuery.isError}
          error={planQuery.error}
          onRetry={() => void planQuery.refetch()}
          planSearch={planSearch}
        />}
      </div>

      {employeeRatingQuery.data === undefined && employeeRatingQuery.isError ? (
        <InlineQueryError error={employeeRatingQuery.error} onRetry={() => void employeeRatingQuery.refetch()} />
      ) : employeeKpiQuery.data === undefined && employeeKpiQuery.isError ? (
        <InlineQueryError error={employeeKpiQuery.error} onRetry={() => void employeeKpiQuery.refetch()} />
      ) : employeeRatingQuery.data === undefined || employeeKpiQuery.data === undefined ? (
        <section className="panel overview-team-panel"><PanelSkeleton rows={5} /></section>
      ) : (
        <EmployeePerformanceSection rating={employeeRatingQuery.data} employeeKpi={employeeKpiQuery.data} />
      )}

      {employeeRatingQuery.data === undefined && employeeRatingQuery.isError ? null : attachQuery.data === undefined && attachQuery.isError ? (
        <InlineQueryError error={attachQuery.error} onRetry={() => void attachQuery.refetch()} />
      ) : attachQuery.data === undefined || employeeRatingQuery.data === undefined ? (
        <section className="panel attach-map-panel"><PanelSkeleton rows={8} /></section>
      ) : (
        <AttachRateMatrix
          attach={attachQuery.data}
          rating={employeeRatingQuery.data}
          storeName={selectedStore.name}
        />
      )}

      <section className="overview-details" aria-label="Подробные показатели">
        <details className="panel overview-disclosure">
          <summary className="overview-disclosure__summary">
            <h2>Категории продаж</h2>
            <span className="overview-disclosure__summary-meta">
              <small>{categories?.categories.length ?? 0}</small>
              <ChevronDown className="overview-disclosure__chevron" size={18} aria-hidden="true" />
            </span>
          </summary>
          <div className="overview-disclosure__content overview-disclosure__content--table table-scroll">
            {categoriesQuery.data === undefined && categoriesQuery.isPending ? <PanelSkeleton rows={4} />
              : categoriesQuery.data === undefined && categoriesQuery.isError ? <InlineQueryError error={categoriesQuery.error} onRetry={() => void categoriesQuery.refetch()} />
                : <>{categories?.categories.some((category) => !category.metrics.dataQuality.completeCostData) && <p className="overview-data-note">Знак «—» в прибыли означает, что себестоимости пока недостаточно для расчета.</p>}<table>
              <thead><tr><th>Категория</th><th>Выручка</th><th>Количество</th><th>Валовая прибыль</th><th>Вал / ед. техники</th><th>Маржа</th></tr></thead>
              <tbody>
                {categories?.categories.map((category) => (
                  <tr key={category.categoryCode} className={!category.categoryActive ? "row-muted" : ""}>
                    <td><strong>{category.categoryName}</strong></td>
                    <td>{formatMoney(category.metrics.netRevenue)}</td>
                    <td>{formatNumber(category.metrics.netQuantity)}</td>
                    <td>{formatMoney(category.metrics.grossProfit)}</td>
                    <td>{formatMoney(averageGrossProfitPerDeviceUnit(category))}</td>
                    <td>{formatPercent(category.metrics.marginPercent)}</td>
                  </tr>
                ))}
              </tbody>
            </table></>}
          </div>
        </details>
      </section>
    </div>
  );
}
