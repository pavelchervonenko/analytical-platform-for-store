import { useQuery } from "@tanstack/react-query";
import { AlertCircle, ArrowRight, CheckCircle2, CircleDollarSign, Package, ReceiptText, RefreshCw, ShieldCheck, Smartphone, Target, TrendingUp, TriangleAlert } from "lucide-react";
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
import type { PlanDirection } from "../api/contracts";
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
  ACCESSORY_IPAD_MAC: "Аксессуары к iPad / Mac",
  ACCESSORY_PODS_WATCH: "Аксессуары к Pods / Watch",
  CASE_APPLE_IPHONE: "Чехлы Apple / iPhone",
  CASE_SAMSUNG: "Чехлы Samsung",
  CHARGER_CABLE: "Зарядные устройства и кабели",
  FILM_PHONE: "Защитные пленки",
  GLASS_CAMERA_IPHONE: "Стекла и защита камеры iPhone",
  GLASS_CAMERA_SAMSUNG: "Стекла Samsung",
  PREMIUM_PROTECTION: "Премиум и протекция",
  SETUP_SERVICE: "Настройки и услуги",
  WARRANTY_GENERIC_NEW: "Гарантии — новые устройства",
  WARRANTY_GENERIC_USED: "Гарантии — устройства Б/У"
};

function toneForStatus(status: string): string {
  if (["CURRENT", "OK", "ACHIEVED", "ON_TRACK"].includes(status)) return "success";
  if (["ERROR", "MISSED", "NOT_SYNCED"].includes(status)) return "danger";
  return "warning";
}

function MetricCard({
  label,
  value,
  icon,
  children,
  featured = false,
  highlighted = false
}: {
  label: string;
  value: string;
  icon: ReactNode;
  children?: ReactNode;
  featured?: boolean;
  highlighted?: boolean;
}) {
  return (
    <article className={`metric-card ${featured ? "metric-card--featured" : ""} ${highlighted ? "metric-card--highlighted" : ""}`}>
      <div className="metric-card__heading"><span>{label}</span><i>{icon}</i></div>
      <strong className="metric-card__value">{value}</strong>
      {children}
    </article>
  );
}

function PlanCard({ direction }: { direction: PlanDirection | null }) {
  if (!direction) {
    return (
      <MetricCard label="Выполнение плана" value="План не задан" icon={<Target size={18} />}>
        <p className="metric-card__hint">Заполните месячный план, чтобы видеть темп.</p>
      </MetricCard>
    );
  }
  const completion = direction.criterionCompletionPercent;
  return (
    <MetricCard label="Выполнение плана" value={formatPercent(completion)} icon={<Target size={18} />}>
      <progress className="progress" value={Math.max(0, completion ?? 0)} max={100} aria-label="Выполнение плана по выручке" />
      <div className="metric-card__meta"><span>{formatCompactMoney(direction.actualAmount)} из {formatCompactMoney(direction.targetAmount)}</span><span className={`status status--${toneForStatus(direction.status)}`}>{directionStatusLabels[direction.status] ?? direction.status}</span></div>
      <div className="metric-card__foot"><span>Осталось</span><strong>{formatMoney(direction.remainingAmount)}</strong></div>
    </MetricCard>
  );
}

function OverviewSkeleton() {
  return (
    <div className="overview-skeleton" aria-label="Загружаем показатели" aria-busy="true">
      <span className="skeleton skeleton--banner" />
      <div className="metric-grid">{Array.from({ length: 5 }, (_, index) => <span className="skeleton skeleton--metric" key={index} />)}</div>
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
        <div><p className="eyebrow">{selectedStore.name}</p><h1>Обзор магазина</h1><p>Ключевые результаты и качество данных за {formatMonth(month)}.</p></div>
        <div className="page-heading__period"><small>Срез показателей</small><strong>по {formatDate(asOfDate)}</strong></div>
      </header>

      {status && (
        <section className={`freshness-banner freshness-banner--${freshnessTone}`} aria-live="polite">
          <span className="freshness-banner__icon">{freshnessTone === "success" ? <ShieldCheck /> : <TriangleAlert />}</span>
          <div>
            <strong>{freshnessLabels[status.status] ?? "Статус данных неизвестен"}</strong>
            <p>{status.dataThroughDate ? `Продажи и возвраты подтверждены по ${formatDate(status.dataThroughDate)}.` : "Полная дата покрытия пока неизвестна."}{status.lagDays ? ` Отставание: ${status.lagDays} дн.` : ""}</p>
          </div>
          {status.openQualityIssueCount > 0 && <span className="freshness-banner__issues"><AlertCircle size={16} />{status.openQualityIssueCount} проблем</span>}
          {status.synchronization.active && <span className="freshness-banner__sync"><RefreshCw size={15} />Обновляется автоматически</span>}
        </section>
      )}

      {quality && !quality.readyForDecisions && (
        <section className="decision-banner" aria-label="Готовность данных для решений">
          <TriangleAlert size={20} />
          <div><strong>Перед управленческим решением проверьте данные</strong><p>{quality.issues.filter((issue) => issue.severity === "ERROR").length} блокирующих причин за выбранный месяц.</p></div>
          <a href="#quality-details">Показать причины <ArrowRight size={15} /></a>
        </section>
      )}

      <section className="metric-grid" aria-label="Ключевые показатели">
        <MetricCard label="Чистая выручка" value={formatMoney(kpi?.netRevenue)} icon={<CircleDollarSign size={19} />} featured>
          <div className="metric-card__meta"><span>Продано: {formatNumber(kpi?.netQuantity)} ед.</span><span>{kpi?.formulaVersion}</span></div>
          <div className="metric-card__foot"><span>Себестоимость</span><strong>{formatMoney(kpi?.costAmount)}</strong></div>
        </MetricCard>

        <PlanCard direction={revenueDirection} />

        <MetricCard label="Маржа" value={formatPercent(kpi?.marginPercent)} icon={<TrendingUp size={19} />} highlighted>
          <div className="metric-card__meta">{kpi?.dataQuality.completeCostData ? <span className="quality-ok"><CheckCircle2 size={14} />Себестоимость полная</span> : <span className="quality-warning"><AlertCircle size={14} />Неполные данные</span>}</div>
          <div className="metric-card__foot"><span>Валовая прибыль</span><strong>{formatMoney(kpi?.grossProfit)}</strong></div>
        </MetricCard>

        <MetricCard label="Средний чек" value={formatMoney(averages?.averageReceipt.current.value)} icon={<ReceiptText size={19} />}>
          <div className="metric-card__meta"><Delta value={averages?.averageReceipt.changePercent} /><span>к прошлому периоду</span></div>
          <div className="metric-card__foot"><span>Чеков</span><strong>{formatNumber(averages?.averageReceipt.current.denominator)}</strong></div>
        </MetricCard>

        <MetricCard label="Дополнительная выручка" value={formatMoney(additionalGroup?.metrics.netRevenue)} icon={<TrendingUp size={19} />}>
          <div className="metric-card__meta"><Delta value={averages?.additionalRevenuePerPhone.changePercent} /><span>на телефон</span></div>
          <div className="metric-card__foot"><span>На один телефон</span><strong>{formatMoney(averages?.additionalRevenuePerPhone.current.value)}</strong></div>
        </MetricCard>
      </section>

      <div className="overview-grid">
        <section className="panel groups-panel">
          <div className="panel__heading"><div><p className="eyebrow">Структура продаж</p><h2>Бизнес-группы</h2></div><span>Группы пересекаются и не суммируются</span></div>
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
          <div className="panel__heading"><div><p className="eyebrow">Темп месяца</p><h2>Направления плана</h2></div>{plan && <span>{plan.achievedDirectionCount} из {plan.directions.length} выполнено</span>}</div>
          {!plan ? (
            <div className="panel-empty"><Target size={24} /><strong>План на месяц не задан</strong><p>Добавьте общую цель магазина и три целевые доли.</p></div>
          ) : (
            <div className="direction-list">
              {plan.directions.map((direction) => {
                const completion = direction.criterionCompletionPercent;
                return (
                  <article key={direction.code} className="direction-row">
                    <div className="direction-row__top"><strong>{directionLabels[direction.code] ?? direction.code}</strong><span className={`status status--${toneForStatus(direction.status)}`}>{directionStatusLabels[direction.status] ?? direction.status}</span></div>
                    <progress className="progress" value={Math.max(0, completion ?? 0)} max={100} aria-label={`Выполнение направления ${directionLabels[direction.code] ?? direction.code}`} />
                    <div className="direction-row__meta"><span>{formatPercent(completion)} критерия</span><span>прогноз {formatCompactMoney(direction.projectedAmount)}</span></div>
                  </article>
                );
              })}
            </div>
          )}
        </section>
      </div>

      <section className="panel categories-panel">
        <div className="panel__heading"><div><p className="eyebrow">Детализация</p><h2>Категории продаж</h2></div><span>{categories?.categories.length ?? 0} категорий</span></div>
        <div className="table-scroll">
          <table>
            <thead><tr><th>Категория</th><th>Выручка</th><th>Количество</th><th>Валовая прибыль</th><th>Маржа</th><th>Качество</th></tr></thead>
            <tbody>
              {categories?.categories.map((category) => (
                <tr key={category.categoryCode} className={!category.categoryActive ? "row-muted" : ""}>
                  <td><strong>{category.categoryName}</strong><small>{category.categoryCode}</small></td>
                  <td>{formatMoney(category.metrics.netRevenue)}</td>
                  <td>{formatNumber(category.metrics.netQuantity)}</td>
                  <td>{formatMoney(category.metrics.grossProfit)}</td>
                  <td>{formatPercent(category.metrics.marginPercent)}</td>
                  <td>{category.metrics.dataQuality.completeCostData ? <span className="quality-ok"><CheckCircle2 size={14} />Полные</span> : <span className="quality-warning"><AlertCircle size={14} />Проверьте</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <div className="overview-grid overview-grid--lower">
        <section className="panel attach-panel">
          <div className="panel__heading"><div><p className="eyebrow">Допродажи</p><h2>Attach-rate на 100 устройств</h2></div><span>Значение может быть выше 100%</span></div>
          {attachQuery.isPending && <PanelSkeleton rows={5} />}
          {attachQuery.isError && <QueryError error={attachQuery.error} onRetry={() => void attachQuery.refetch()} compact />}
          {attachQuery.data && (
            <div className="attach-list">
              {attachQuery.data.rates.map((rate) => (
                <article key={rate.metricCode}>
                  <div><strong>{attachLabels[rate.metricCode] ?? rate.metricCode}</strong><small>{formatNumber(rate.numeratorQuantity)} из {formatNumber(rate.denominatorQuantity)} ед.</small></div>
                  <span>{formatPercent(rate.ratePerHundred)}</span>
                </article>
              ))}
            </div>
          )}
        </section>

        <section id="quality-details" className="panel quality-panel">
          <div className="panel__heading"><div><p className="eyebrow">Надежность</p><h2>Качество периода</h2></div>{quality && <span className={`status status--${toneForStatus(quality.status)}`}>{quality.status}</span>}</div>
          {quality?.issues.length === 0 ? (
            <div className="panel-empty panel-empty--success"><ShieldCheck size={26} /><strong>Критичных замечаний нет</strong><p>Данные выбранного месяца готовы для управленческих решений.</p></div>
          ) : (
            <div className="quality-list">
              {quality?.issues.slice(0, 8).map((issue) => (
                <article key={issue.key}>
                  <span className={`quality-list__icon quality-list__icon--${toneForStatus(issue.severity)}`}>{issue.severity === "ERROR" ? <AlertCircle size={17} /> : <TriangleAlert size={17} />}</span>
                  <div><strong>{issue.message}</strong><small>{issue.area} · {issue.code}{issue.affectedCount != null ? ` · ${issue.affectedCount}` : ""}</small></div>
                </article>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
