import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  AlertCircle,
  CheckCircle2,
  Clock3,
  DatabaseZap,
  RefreshCw,
  ShieldAlert,
  TriangleAlert,
  X
} from "lucide-react";
import { Link, useLocation } from "react-router";
import { useAuth } from "../auth/AuthProvider";
import { formatDate, formatMonth } from "../shared/date";
import { formatNumber } from "../shared/format";
import { QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { describeQualityAction } from "./actions";
import {
  getQualityOverview,
  getStorePeriodQuality,
  getStoreQuality,
  qualityKeys,
  type QualityAction
} from "./api";
import {
  groupStoreQualityIssues,
  qualityIssueGuidance,
  type StoreQualityIssueGroup
} from "./issue-groups";
import {
  qualityAreaLabel,
  qualityIssueMessage,
  qualitySeverityLabel,
  qualitySourceLabel,
  qualityStatusLabel
} from "./presentation";

function actionSearch(currentSearch: string, route: string, view?: string): string {
  const params = new URLSearchParams(currentSearch);
  if (view) {
    const key = route === "/admin"
      ? "adminView"
      : route === "/plan"
        ? "section"
        : "qualityView";
    params.set(key, view);
  }
  return params.toString();
}

function eventCountLabel(count: number): string {
  const mod100 = count % 100;
  const mod10 = count % 10;
  if (mod100 >= 11 && mod100 <= 14) return "событий";
  if (mod10 === 1) return "событие";
  if (mod10 >= 2 && mod10 <= 4) return "события";
  return "событий";
}

function ActionControl({
  action,
  refresh
}: {
  action: QualityAction;
  refresh: () => void;
}) {
  const { user } = useAuth();
  const location = useLocation();
  const descriptor = describeQualityAction(action, user?.role === "ADMIN");
  if (!descriptor) return null;
  if (descriptor.refresh) {
    return (
      <button className="quality-action" type="button" onClick={refresh}>
        <RefreshCw size={14} />
        {descriptor.label}
      </button>
    );
  }
  if (descriptor.route) {
    return (
      <Link
        className="quality-action"
        to={{
          pathname: descriptor.route,
          search: actionSearch(
            location.search,
            descriptor.route,
            descriptor.view
          ),
          hash: descriptor.hash
        }}
      >
        {descriptor.label}
      </Link>
    );
  }
  return (
    <span
      className="quality-action quality-action--disabled"
      title={descriptor.unavailableReason}
    >
      {descriptor.label}
    </span>
  );
}

function QualityIssueDialog({
  group,
  isAdmin,
  onClose
}: {
  group: StoreQualityIssueGroup;
  isAdmin: boolean;
  onClose: () => void;
}) {
  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [onClose]);

  const descriptor = describeQualityAction(group.recommendedAction, isAdmin);
  return (
    <div
      className="quality-dialog-backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        className="quality-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="quality-dialog-title"
      >
        <header>
          <div>
            <span className={`quality-severity quality-severity--${group.severity.toLowerCase()}`}>
              {qualitySeverityLabel(group.severity)}
            </span>
            <h2 id="quality-dialog-title">{qualityIssueMessage(group.code)}</h2>
          </div>
          <button
            className="icon-button"
            type="button"
            onClick={onClose}
            aria-label="Закрыть"
          >
            <X />
          </button>
        </header>

        <dl className="quality-dialog__facts">
          <div>
            <dt>Источник</dt>
            <dd>{qualitySourceLabel(group.source)}</dd>
          </div>
          <div>
            <dt>Событий</dt>
            <dd>{formatNumber(group.eventCount)}</dd>
          </div>
          <div>
            <dt>Последнее обнаружение</dt>
            <dd>
              {group.latestDetectedAt
                ? new Date(group.latestDetectedAt).toLocaleString("ru-RU")
                : "Время не определено"}
            </dd>
          </div>
          <div>
            <dt>Код для диагностики</dt>
            <dd>{group.code}</dd>
          </div>
        </dl>

        <div className="quality-dialog__guidance">
          <strong>Что делать</strong>
          <p>{qualityIssueGuidance(group.code)}</p>
        </div>

        <footer>
          <button className="button button--secondary" type="button" onClick={onClose}>
            Закрыть
          </button>
          {descriptor?.route && (
            <Link
              className="button button--primary"
              to={{
                pathname: descriptor.route,
                search: actionSearch("", descriptor.route, descriptor.view),
                hash: descriptor.hash
              }}
            >
              {descriptor.label}
            </Link>
          )}
          {!descriptor?.route && descriptor?.unavailableReason && (
            <small>{descriptor.unavailableReason}</small>
          )}
        </footer>
      </section>
    </div>
  );
}

function QualitySkeleton() {
  return (
    <div className="quality-page" aria-busy="true">
      <span className="skeleton skeleton--banner" />
      <div className="quality-area-grid">
        {Array.from({ length: 4 }, (_, index) => (
          <span className="skeleton skeleton--panel" key={index} />
        ))}
      </div>
      <span className="skeleton skeleton--panel" />
    </div>
  );
}

export function QualityPage() {
  const { selectedStore, month, asOfDate } = useWorkspace();
  const { user } = useAuth();
  const location = useLocation();
  const [selectedIssue, setSelectedIssue] =
    useState<StoreQualityIssueGroup | null>(null);
  const overviewQuery = useQuery({
    queryKey: qualityKeys.overview,
    queryFn: getQualityOverview,
    staleTime: 60_000
  });
  const storeQuery = useQuery({
    queryKey: qualityKeys.store(selectedStore.id),
    queryFn: () => getStoreQuality(selectedStore.id),
    refetchInterval: (query) =>
      query.state.data?.dataStatus.synchronization.active ? 5_000 : false
  });
  const periodQuery = useQuery({
    queryKey: qualityKeys.period(selectedStore.id, month, asOfDate),
    queryFn: () => getStorePeriodQuality(selectedStore.id, month, asOfDate)
  });
  const refresh = () => {
    void overviewQuery.refetch();
    void storeQuery.refetch();
    void periodQuery.refetch();
  };

  useEffect(() => {
    if (location.hash !== "#quality-source-issues" || !storeQuery.data) return;
    const frame = window.requestAnimationFrame(() => {
      document.getElementById("quality-source-issues")?.scrollIntoView({
        behavior: "smooth",
        block: "start"
      });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [location.hash, storeQuery.data]);

  if (overviewQuery.isPending || storeQuery.isPending || periodQuery.isPending) {
    return <QualitySkeleton />;
  }
  const error = overviewQuery.error ?? storeQuery.error ?? periodQuery.error;
  if (error) return <QueryError error={error} onRetry={refresh} />;
  if (!overviewQuery.data || !storeQuery.data || !periodQuery.data) {
    return <QualitySkeleton />;
  }

  const overview = overviewQuery.data;
  const store = storeQuery.data;
  const period = periodQuery.data;
  const storeIssueGroups = groupStoreQualityIssues(store.issues);

  return (
    <div className="quality-page">
      <header className="page-heading quality-heading">
        <div>
          <h1>Качество данных</h1>
          <p>Здесь видно, что нужно исправить перед расчетом зарплаты и рейтинга.</p>
        </div>
        <button
          className="button button--secondary"
          type="button"
          onClick={refresh}
          disabled={
            overviewQuery.isFetching
            || storeQuery.isFetching
            || periodQuery.isFetching
          }
        >
          <RefreshCw
            size={16}
            className={storeQuery.isFetching ? "is-spinning" : ""}
          />
          Обновить
        </button>
      </header>

      <section className={`quality-verdict quality-verdict--${period.status.toLowerCase()}`}>
        <span>{period.readyForDecisions ? <CheckCircle2 /> : <ShieldAlert />}</span>
        <div>
          <p className="eyebrow">{formatMonth(month)}</p>
          <h2>
            {period.readyForDecisions
              ? "Данные готовы для работы"
              : "Сначала исправьте замечания"}
          </h2>
          <p>
            {period.issues.length === 0
              ? "Проблем за выбранный месяц нет."
              : `Найдено замечаний: ${period.issues.length}. Важные показаны первыми.`}
          </p>
        </div>
        <dl>
          <div>
            <dt>Данные по</dt>
            <dd>{formatDate(period.sourceData.dataThroughDate)}</dd>
          </div>
          <div>
            <dt>Проверено</dt>
            <dd>{new Date(period.checkedAt).toLocaleString("ru-RU")}</dd>
          </div>
        </dl>
      </section>

      <section className="quality-area-grid" aria-label="Готовность по направлениям">
        {period.areas.map((area) => (
          <article
            className={`quality-area quality-area--${area.status.toLowerCase()}`}
            key={area.code}
          >
            <div>
              <span>
                {area.ready
                  ? <CheckCircle2 />
                  : area.status === "ERROR"
                    ? <AlertCircle />
                    : <TriangleAlert />}
              </span>
              <i className={`status status--${area.status === "OK" ? "success" : "warning"}`}>
                {qualityStatusLabel(area.status)}
              </i>
            </div>
            <h2>{qualityAreaLabel(area.code)}</h2>
            <p>
              {area.issueCount === 0
                ? "Замечаний нет"
                : `Замечаний: ${area.issueCount}, важных: ${area.errorCount}`}
            </p>
          </article>
        ))}
      </section>

      <div className="quality-layout">
        <section className="panel quality-issues" id="quality-issues">
          <div className="panel__heading">
            <div>
              <p className="eyebrow">Выбранный месяц</p>
              <h2>Что требует действия</h2>
            </div>
            <span>{period.issues.length}</span>
          </div>
          {period.issues.length === 0 ? (
            <div className="panel-empty">
              <CheckCircle2 size={28} />
              <strong>Месяц готов</strong>
              <p>Все проверки пройдены.</p>
            </div>
          ) : (
            <div className="quality-issue-list">
              {period.issues.map((issue) => (
                <article key={issue.key}>
                  <span className={`quality-severity quality-severity--${issue.severity.toLowerCase()}`}>
                    {qualitySeverityLabel(issue.severity)}
                  </span>
                  <div>
                    <strong>{qualityIssueMessage(issue.code)}</strong>
                    <small>
                      {qualityAreaLabel(issue.area)}
                      {issue.affectedCount != null
                        ? `, затронуто: ${formatNumber(issue.affectedCount)}`
                        : ""}
                    </small>
                  </div>
                  <ActionControl action={issue.recommendedAction} refresh={refresh} />
                </article>
              ))}
            </div>
          )}
        </section>

        <aside className="quality-aside">
          <section className="panel quality-source-card">
            <div className="panel__heading">
              <h2>Актуальность данных</h2>
              <DatabaseZap />
            </div>
            <strong className={`quality-source-status quality-source-status--${store.summary.status.toLowerCase()}`}>
              {qualityStatusLabel(store.summary.status)}
            </strong>
            <dl>
              <div>
                <dt>Продажи загружены по</dt>
                <dd>{formatDate(store.dataStatus.salesDataThroughDate)}</dd>
              </div>
              <div>
                <dt>Возвраты загружены по</dt>
                <dd>{formatDate(store.dataStatus.returnsDataThroughDate)}</dd>
              </div>
              <div>
                <dt>Отставание</dt>
                <dd>
                  {store.summary.lagDays == null
                    ? "—"
                    : `${store.summary.lagDays} дн.`}
                </dd>
              </div>
            </dl>
            {store.dataStatus.synchronization.active && (
              <p className="quality-syncing">
                <Clock3 size={15} />
                Данные обновляются автоматически.
              </p>
            )}
          </section>
          <section className="panel quality-portfolio">
            <div className="panel__heading">
              <h2>Все магазины</h2>
              <span>{overview.storeCount}</span>
            </div>
            <div className="quality-portfolio__stats">
              <span>
                <strong>{overview.okStoreCount}</strong>
                <small>готовы</small>
              </span>
              <span>
                <strong>{overview.warningStoreCount}</strong>
                <small>есть замечания</small>
              </span>
              <span>
                <strong>{overview.errorStoreCount}</strong>
                <small>есть проблемы</small>
              </span>
            </div>
            <p>Всего открытых замечаний: {overview.openIssueCount}.</p>
          </section>
        </aside>
      </div>

      <section
        className="panel quality-store-issues"
        id="quality-source-issues"
      >
        <div className="panel__heading">
          <div>
            <p className="eyebrow">Сгруппировано по типу</p>
            <h2>Проблемы в исходных данных</h2>
          </div>
          <span>
            Типов: {storeIssueGroups.length} · событий: {store.issues.length}
          </span>
        </div>
        {storeIssueGroups.length === 0 ? (
          <div className="panel-empty">
            <CheckCircle2 size={24} />
            <strong>Открытых проблем нет</strong>
          </div>
        ) : (
          <div className="quality-issue-list quality-source-issue-list">
            {storeIssueGroups.map((group) => (
              <article key={group.key}>
                <span className={`quality-severity quality-severity--${group.severity.toLowerCase()}`}>
                  {qualitySeverityLabel(group.severity)}
                </span>
                <div>
                  <strong>{qualityIssueMessage(group.code)}</strong>
                  <small>
                    {qualitySourceLabel(group.source)}
                    {" · "}
                    {formatNumber(group.eventCount)}{" "}
                    {eventCountLabel(group.eventCount)}
                    {" · последнее: "}
                    {group.latestDetectedAt
                      ? new Date(group.latestDetectedAt).toLocaleString("ru-RU")
                      : "время не определено"}
                  </small>
                </div>
                <button
                  className="quality-action"
                  type="button"
                  onClick={() => setSelectedIssue(group)}
                >
                  Подробнее
                </button>
              </article>
            ))}
          </div>
        )}
      </section>

      {selectedIssue && (
        <QualityIssueDialog
          group={selectedIssue}
          isAdmin={user?.role === "ADMIN"}
          onClose={() => setSelectedIssue(null)}
        />
      )}
    </div>
  );
}
