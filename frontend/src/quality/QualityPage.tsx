import { useQuery } from "@tanstack/react-query";
import { AlertCircle, CheckCircle2, Clock3, DatabaseZap, RefreshCw, ShieldAlert, TriangleAlert } from "lucide-react";
import { Link, useLocation } from "react-router";
import { useAuth } from "../auth/AuthProvider";
import { formatDate } from "../shared/date";
import { formatNumber } from "../shared/format";
import { QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { describeQualityAction } from "./actions";
import { getQualityOverview, getStorePeriodQuality, getStoreQuality, qualityKeys, type QualityAction } from "./api";

const statusLabels = { OK: "Готово", WARNING: "Нужно внимание", ERROR: "Есть блокеры", UNKNOWN: "Неизвестно" } as const;
const areaLabels = { SOURCE_DATA: "Исходные данные", STORE_PLAN: "План магазина", EMPLOYEE_RATING: "Рейтинг сотрудников", PAYROLL: "Расчет зарплаты", UNKNOWN: "Неизвестное направление" } as const;
const severityLabels = { ERROR: "Ошибка", WARNING: "Предупреждение", INFO: "Информация", UNKNOWN: "Неизвестно" } as const;

function actionSearch(currentSearch: string, route: string, view?: string): string {
  const params = new URLSearchParams(currentSearch);
  if (view) {
    const key = route === "/admin" ? "adminView" : route === "/plan" ? "section" : "qualityView";
    params.set(key, view);
  }
  return params.toString();
}

function ActionControl({ action, refresh }: { action: QualityAction; refresh: () => void }) {
  const { user } = useAuth();
  const location = useLocation();
  const descriptor = describeQualityAction(action, user?.role === "ADMIN");
  if (!descriptor) return null;
  if (descriptor.refresh) return <button className="quality-action" type="button" onClick={refresh}><RefreshCw size={14} />{descriptor.label}</button>;
  if (descriptor.route) return <Link className="quality-action" to={{ pathname: descriptor.route, search: actionSearch(location.search, descriptor.route, descriptor.view) }}>{descriptor.label}</Link>;
  return <span className="quality-action quality-action--disabled" title={descriptor.unavailableReason}>{descriptor.label}</span>;
}

function QualitySkeleton() {
  return <div className="quality-page" aria-busy="true"><span className="skeleton skeleton--banner" /><div className="quality-area-grid">{Array.from({ length: 4 }, (_, index) => <span className="skeleton skeleton--panel" key={index} />)}</div><span className="skeleton skeleton--panel" /></div>;
}

export function QualityPage() {
  const { selectedStore, month, asOfDate } = useWorkspace();
  const overviewQuery = useQuery({ queryKey: qualityKeys.overview, queryFn: getQualityOverview, staleTime: 60_000 });
  const storeQuery = useQuery({
    queryKey: qualityKeys.store(selectedStore.id), queryFn: () => getStoreQuality(selectedStore.id),
    refetchInterval: (query) => query.state.data?.dataStatus.synchronization.active ? 5_000 : false
  });
  const periodQuery = useQuery({ queryKey: qualityKeys.period(selectedStore.id, month, asOfDate), queryFn: () => getStorePeriodQuality(selectedStore.id, month, asOfDate) });
  const refresh = () => { void overviewQuery.refetch(); void storeQuery.refetch(); void periodQuery.refetch(); };

  if (overviewQuery.isPending || storeQuery.isPending || periodQuery.isPending) return <QualitySkeleton />;
  const error = overviewQuery.error ?? storeQuery.error ?? periodQuery.error;
  if (error) return <QueryError error={error} onRetry={refresh} />;
  if (!overviewQuery.data || !storeQuery.data || !periodQuery.data) return <QualitySkeleton />;

  const overview = overviewQuery.data;
  const store = storeQuery.data;
  const period = periodQuery.data;

  return (
    <div className="quality-page">
      <header className="page-heading quality-heading">
        <div><p className="eyebrow">Контроль пригодности данных</p><h1>Центр качества данных</h1><p>Единая точка проверки источников, плана, рейтинга и зарплаты перед управленческими решениями.</p></div>
        <button className="button button--secondary" type="button" onClick={refresh} disabled={overviewQuery.isFetching || storeQuery.isFetching || periodQuery.isFetching}><RefreshCw size={16} className={storeQuery.isFetching ? "is-spinning" : ""} />Обновить</button>
      </header>

      <section className={`quality-verdict quality-verdict--${period.status.toLowerCase()}`}>
        <span>{period.readyForDecisions ? <CheckCircle2 /> : <ShieldAlert />}</span>
        <div><p className="eyebrow">{selectedStore.name} · {month}</p><h2>{period.readyForDecisions ? "Данные готовы для решений" : "Перед решением устраните замечания"}</h2><p>{period.issues.length === 0 ? "Контроль не обнаружил проблем за выбранный месяц." : `Открыто ${period.issues.length} замечаний; критичные действия показаны первыми.`}</p></div>
        <dl><div><dt>Данные по</dt><dd>{formatDate(period.sourceData.dataThroughDate)}</dd></div><div><dt>Проверено</dt><dd>{new Date(period.checkedAt).toLocaleString("ru-RU")}</dd></div></dl>
      </section>

      <section className="quality-area-grid" aria-label="Готовность по направлениям">
        {period.areas.map((area) => <article className={`quality-area quality-area--${area.status.toLowerCase()}`} key={area.code}><div><span>{area.ready ? <CheckCircle2 /> : area.status === "ERROR" ? <AlertCircle /> : <TriangleAlert />}</span><i className={`status status--${area.status === "OK" ? "success" : "warning"}`}>{statusLabels[area.status]}</i></div><h2>{areaLabels[area.code]}</h2><p>{area.issueCount === 0 ? "Замечаний нет" : `${area.issueCount} замечаний · ${area.errorCount} критичных`}</p></article>)}
      </section>

      <div className="quality-layout">
        <section className="panel quality-issues" id="quality-issues">
          <div className="panel__heading"><div><p className="eyebrow">Выбранный месяц</p><h2>Что требует действия</h2></div><span>{period.issues.length}</span></div>
          {period.issues.length === 0 ? <div className="panel-empty"><CheckCircle2 size={28} /><strong>Месяц готов</strong><p>Все проверки источников и расчетов пройдены.</p></div> : <div className="quality-issue-list">{period.issues.map((issue) => <article key={issue.key}><span className={`quality-severity quality-severity--${issue.severity.toLowerCase()}`}>{severityLabels[issue.severity]}</span><div><strong>{issue.message}</strong><small>{areaLabels[issue.area as keyof typeof areaLabels] ?? issue.area} · {issue.code}{issue.affectedCount != null ? ` · затронуто ${formatNumber(issue.affectedCount)}` : ""}</small></div><ActionControl action={issue.recommendedAction} refresh={refresh} /></article>)}</div>}
        </section>

        <aside className="quality-aside">
          <section className="panel quality-source-card"><div className="panel__heading"><div><p className="eyebrow">Источник</p><h2>Свежесть магазина</h2></div><DatabaseZap /></div><strong className={`quality-source-status quality-source-status--${store.summary.status.toLowerCase()}`}>{statusLabels[store.summary.status]}</strong><dl><div><dt>Покрытие продаж</dt><dd>{formatDate(store.dataStatus.salesDataThroughDate)}</dd></div><div><dt>Покрытие возвратов</dt><dd>{formatDate(store.dataStatus.returnsDataThroughDate)}</dd></div><div><dt>Отставание</dt><dd>{store.summary.lagDays == null ? "—" : `${store.summary.lagDays} дн.`}</dd></div></dl>{store.dataStatus.synchronization.active && <p className="quality-syncing"><Clock3 size={15} />Синхронизация выполняется; статус обновляется автоматически.</p>}</section>
          <section className="panel quality-portfolio"><div className="panel__heading"><div><p className="eyebrow">Все доступные магазины</p><h2>Общий контур</h2></div><span>{overview.storeCount}</span></div><div className="quality-portfolio__stats"><span><strong>{overview.okStoreCount}</strong><small>готово</small></span><span><strong>{overview.warningStoreCount}</strong><small>внимание</small></span><span><strong>{overview.errorStoreCount}</strong><small>блокеры</small></span></div><p>{overview.openIssueCount} открытых замечаний во всех доступных магазинах.</p></section>
        </aside>
      </div>

      <section className="panel quality-store-issues">
        <div className="panel__heading"><div><p className="eyebrow">Независимо от месяца</p><h2>Проблемы источника и документов</h2></div><span>{store.issues.length}</span></div>
        {store.issues.length === 0 ? <div className="panel-empty"><CheckCircle2 size={24} /><strong>Открытых проблем нет</strong></div> : <div className="quality-issue-list">{store.issues.map((issue) => <article key={issue.key}><span className={`quality-severity quality-severity--${issue.severity.toLowerCase()}`}>{severityLabels[issue.severity]}</span><div><strong>{issue.message}</strong><small>{issue.source} · {issue.code} · {issue.detectedAt ? new Date(issue.detectedAt).toLocaleString("ru-RU") : "время не определено"}</small></div><ActionControl action={issue.recommendedAction} refresh={refresh} /></article>)}</div>}
      </section>
    </div>
  );
}
