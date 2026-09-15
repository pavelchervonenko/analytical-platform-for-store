import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Filter, History, LockKeyhole, Search, Trophy, UserCheck, Users } from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Link, useLocation } from "react-router";
import { isApiClientError } from "../api/client";
import type { EmployeeRatingEntry, EmployeeRatingSetting } from "../api/contracts";
import {
  finalizeEmployeeRating,
  getEmployeeDirectory,
  getEmployeeRating,
  getEmployeeRatingSettings,
  queryKeys,
  updateEmployeeRatingSetting
} from "../api/queries";
import { currentDateInTimeZone, formatDate } from "../shared/date";
import { formatCompactMoney, formatMoney, formatNumber, formatPercent } from "../shared/format";
import { InlineQueryError, PanelSkeleton, QueryError, StaleDataNote } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { employeeRatingReason, selectEmployeeEntries, type EmployeeFilter, type EmployeeSort } from "./rating-ui";

function rankDynamics(value: number | null): ReactNode {
  if (value == null) return <span className="employee-rank-change employee-rank-change--neutral">без сравнения</span>;
  if (value === 0) return <span className="employee-rank-change employee-rank-change--neutral">без изменений</span>;
  return <span className={`employee-rank-change employee-rank-change--${value > 0 ? "positive" : "negative"}`}>{value > 0 ? "↑" : "↓"} {Math.abs(value)}</span>;
}

function ScoreProfile({ employee }: { employee: EmployeeRatingEntry }) {
  const values = [
    ["Вклад", employee.scores.contributionScore],
    ["Время", employee.scores.efficiencyScore],
    ["Структура", employee.scores.structureScore],
    ["Допродажи", employee.scores.attachScore]
  ] as const;
  return <div className="employee-score-profile">{values.map(([label, value]) => <span key={label}><small>{label}</small><strong>{formatNumber(value)}</strong></span>)}</div>;
}

function SummaryCard({ icon, label, value, note, featured = false }: { icon: ReactNode; label: string; value: string; note: string; featured?: boolean }) {
  return <article className={`employee-summary-card ${featured ? "employee-summary-card--featured" : ""}`}><span>{icon}</span><div><small>{label}</small><strong>{value}</strong><p>{note}</p></div></article>;
}

function EmployeesSkeleton() {
  return <div className="employees-skeleton" aria-busy="true" aria-label="Загрузка сотрудников"><span className="skeleton skeleton--banner" /><div className="employee-summary-grid">{Array.from({ length: 3 }, (_, index) => <span className="skeleton employee-summary-skeleton" key={index} />)}</div><span className="skeleton employee-table-skeleton" /></div>;
}

export function EmployeesPage() {
  const { selectedStore, periodStart, periodEnd } = useWorkspace();
  const location = useLocation();
  const queryClient = useQueryClient();
  const storeId = selectedStore.id;
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<EmployeeFilter>("all");
  const [sort, setSort] = useState<EmployeeSort>("rank");
  const [finalizeDialogOpen, setFinalizeDialogOpen] = useState(false);

  const directoryQuery = useQuery({
    queryKey: queryKeys.employeeDirectory(storeId, periodStart, periodEnd),
    queryFn: () => getEmployeeDirectory(storeId, periodStart, periodEnd)
  });
  const ratingQuery = useQuery({
    queryKey: queryKeys.employeeRating(storeId, periodStart, periodEnd),
    queryFn: () => getEmployeeRating(storeId, periodStart, periodEnd)
  });
  const settingsQuery = useQuery({
    queryKey: queryKeys.employeeRatingSettings(storeId),
    queryFn: () => getEmployeeRatingSettings(storeId)
  });

  const participationMutation = useMutation({
    mutationFn: (setting: EmployeeRatingSetting) => updateEmployeeRatingSetting(
      storeId,
      setting.employeeId,
      !setting.participatesInRanking,
      setting.version
    ),
    onSuccess: async (updated) => {
      queryClient.setQueryData<EmployeeRatingSetting[]>(
        queryKeys.employeeRatingSettings(storeId),
        (current) => current?.map((setting) => setting.employeeId === updated.employeeId ? updated : setting) ?? [updated]
      );
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.employeeDirectory(storeId, periodStart, periodEnd) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.employeeRating(storeId, periodStart, periodEnd) }),
        queryClient.invalidateQueries({ queryKey: ["stores", storeId, "work-schedule"] }),
        queryClient.invalidateQueries({ queryKey: ["stores", storeId, "payroll"] })
      ]);
    },
    onError: (error) => {
      if (isApiClientError(error) && error.status === 409) void settingsQuery.refetch();
    }
  });

  useEffect(() => {
    if (location.hash !== "#rating-participants" || !settingsQuery.data) return;
    const frame = window.requestAnimationFrame(() => document.getElementById("rating-participants")?.scrollIntoView({ block: "start" }));
    return () => window.cancelAnimationFrame(frame);
  }, [location.hash, settingsQuery.data]);

  const finalizeMutation = useMutation({
    mutationFn: () => finalizeEmployeeRating(storeId, periodStart, periodEnd),
    onSuccess: async (result) => {
      queryClient.setQueryData(queryKeys.employeeRating(storeId, periodStart, periodEnd), result);
      setFinalizeDialogOpen(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.employees(storeId) }),
        queryClient.invalidateQueries({ queryKey: ["stores", storeId, "period-quality"] })
      ]);
    }
  });

  const entries = useMemo(() => selectEmployeeEntries(directoryQuery.data?.employees ?? [], search, filter, sort), [directoryQuery.data, filter, search, sort]);

  const directoryAvailable = directoryQuery.data !== undefined;
  const ratingAvailable = ratingQuery.data !== undefined;
  if ((!directoryAvailable && directoryQuery.isPending) || (!ratingAvailable && ratingQuery.isPending)) return <EmployeesSkeleton />;
  if (!directoryAvailable && directoryQuery.isError) {
    return <QueryError error={directoryQuery.error} onRetry={() => void directoryQuery.refetch()} />;
  }
  if (!ratingAvailable && ratingQuery.isError) {
    return <QueryError error={ratingQuery.error} onRetry={() => void ratingQuery.refetch()} />;
  }
  const staleQuery = [directoryQuery, ratingQuery, settingsQuery].find((query) => query.isError && query.data !== undefined);

  const rating = ratingQuery.data!;
  const participants = directoryQuery.data!.employees.filter(({ current }) => current.participatesInRanking);
  const ranked = participants.filter(({ current }) => current.ranked).length;
  const withoutRank = participants.filter(({ current }) => !current.ranked).length;
  const isFinalized = rating.history.status === "FINALIZED";
  const isLive = rating.history.status === "LIVE";
  const canFinalize = isLive && periodEnd < currentDateInTimeZone(selectedStore.timezone);

  return (
    <div className="employees-page">
      <header className="page-heading employees-heading">
        <h1>Сотрудники и рейтинг</h1>
        <div className="employees-heading__actions">
          {!isLive && <span className={`rating-history-badge rating-history-badge--${isFinalized ? "finalized" : "unknown"}`}>{isFinalized ? <LockKeyhole size={15} /> : <History size={15} />}{isFinalized ? "Зафиксирован" : "Статус неизвестен"}</span>}
          {canFinalize && <button className="button button--primary" type="button" onClick={() => setFinalizeDialogOpen(true)}><LockKeyhole size={17} />Зафиксировать период</button>}
        </div>
      </header>

      {staleQuery && <StaleDataNote error={staleQuery.error} onRetry={() => void Promise.all([directoryQuery.refetch(), ratingQuery.refetch(), settingsQuery.refetch()])} />}

      {finalizeMutation.isError && (
        <div className="form-alert" role="alert">{isApiClientError(finalizeMutation.error) ? finalizeMutation.error.message : "Не удалось зафиксировать рейтинг. Обновите данные и повторите действие."}</div>
      )}

      <section className="employee-summary-grid" aria-label="Сводка рейтинга">
        <SummaryCard icon={<Users size={21} />} label="Сотрудники" value={String(participants.length)} note="Показываются участники рейтинга" featured />
        <SummaryCard icon={<Trophy size={21} />} label="Получили место" value={String(ranked)} note={withoutRank ? `${withoutRank} без места — причины указаны в таблице` : "Место присвоено всем участникам"} />
        <SummaryCard icon={<UserCheck size={21} />} label="Покрытие плана" value={formatPercent(rating.plan.coveragePercent)} note={rating.plan.complete ? `Выполнение выручки: ${formatPercent(rating.plan.revenueAchievementPercent)}` : "План задан не на весь период"} />
      </section>

      <section className="panel rating-participation-panel" id="rating-participants" aria-labelledby="rating-participants-title">
        <div className="panel__heading"><div><p className="eyebrow">Состав команды</p><h2 id="rating-participants-title">Участники рейтинга и смен</h2><p>Включайте сюда продавцов, которых нужно добавлять в календарь смен и общий рейтинг.</p></div><span>{settingsQuery.data ? `${settingsQuery.data.filter((setting) => setting.participatesInRanking).length} включено` : "—"}</span></div>
        {settingsQuery.data === undefined && settingsQuery.isPending && <PanelSkeleton rows={3} />}
        {settingsQuery.data === undefined && settingsQuery.isError && <InlineQueryError error={settingsQuery.error} onRetry={() => void settingsQuery.refetch()} />}
        {settingsQuery.data && <div className="rating-participation-list">
          {settingsQuery.data.map((setting) => {
            const available = setting.employeeActive && setting.assignmentActive;
            const pending = participationMutation.isPending && participationMutation.variables?.employeeId === setting.employeeId;
            const failed = participationMutation.isError && participationMutation.variables?.employeeId === setting.employeeId;
            const failureMessage = isApiClientError(participationMutation.error) && participationMutation.error.status === 409
              ? "Настройка уже изменилась. Проверьте актуальное значение и повторите."
              : isApiClientError(participationMutation.error) ? participationMutation.error.message : "Не удалось изменить участие.";
            return <article key={setting.employeeId}><div><strong>{setting.displayName}</strong><small>{available ? setting.participatesInRanking ? "Участвует в рейтинге и доступен для смен" : "Не участвует и недоступен для новых смен" : !setting.employeeActive ? "Профиль неактивен" : "Нет активного назначения в магазин"}</small>{failed && <p className="rating-participation-error" role="alert">{failureMessage}</p>}</div><button className={`participation-toggle ${setting.participatesInRanking ? "participation-toggle--active" : ""}`} type="button" aria-pressed={setting.participatesInRanking} disabled={!available || pending} onClick={() => participationMutation.mutate(setting)}><span aria-hidden="true"><i /></span>{pending ? "Сохраняем…" : setting.participatesInRanking ? "Включен" : "Выключен"}</button></article>;
          })}
        </div>}
      </section>

      <section className="employees-panel panel">
        <div className="employees-toolbar">
          <div><p className="eyebrow">Команда</p><h2>Результаты сотрудников</h2></div>
          <label className="employee-search"><Search size={17} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Найти сотрудника" aria-label="Найти сотрудника" /></label>
          <label className="employee-select"><Filter size={15} /><select value={filter} onChange={(event) => setFilter(event.target.value as EmployeeFilter)} aria-label="Фильтр сотрудников"><option value="all">Все участники</option><option value="ranked">С местом</option><option value="unranked">Без места</option></select></label>
          <label className="employee-select"><select value={sort} onChange={(event) => setSort(event.target.value as EmployeeSort)} aria-label="Сортировка сотрудников"><option value="rank">По месту</option><option value="score">По общему баллу</option><option value="revenue">По выручке</option><option value="improvement">По росту места</option></select></label>
        </div>

        {entries.length === 0 ? <div className="panel-empty"><Search size={24} /><strong>Сотрудники не найдены</strong><p>Измените строку поиска или выбранный фильтр.</p></div> : (
          <>
            <div className="employee-table-wrap">
              <table className="employee-rating-table">
                <thead><tr><th>Сотрудник</th><th>Место</th><th>Общий балл</th><th>4 направления</th><th>Выручка</th><th>Смены и время</th><th aria-label="Открыть карточку" /></tr></thead>
                <tbody>{entries.map(({ current, dynamics }) => (
                  <tr key={current.employeeId}>
                    <td><div className="employee-person"><span>{current.displayName.slice(0, 1).toUpperCase()}</span><div><Link to={{ pathname: `/employees/${current.employeeId}`, search: location.search }}>{current.displayName}</Link><small>{employeeRatingReason(current, rating.formula.minimumCoveragePercent)}</small></div></div></td>
                    <td><div className="employee-rank-cell"><strong>{current.rank ?? "—"}</strong>{rankDynamics(dynamics.rankImprovement)}</div></td>
                    <td><div className="employee-score-cell"><strong>{formatNumber(current.scores.overallScore)}</strong><small>покрытие {formatPercent(current.scores.coveragePercent)}</small></div></td>
                    <td><ScoreProfile employee={current} /></td>
                    <td><div className="employee-money-cell"><strong>{formatMoney(current.netRevenue)}</strong><small>{formatPercent(current.storeRevenueSharePercent)} выручки магазина</small></div></td>
                    <td><div className="employee-time-cell"><strong>{current.shiftCount} смен, {formatNumber(current.workedHours)} ч</strong><small>{formatCompactMoney(current.revenuePerHour)} / час</small></div></td>
                    <td><Link className="employee-open" to={{ pathname: `/employees/${current.employeeId}`, search: location.search }} aria-label={`Открыть карточку: ${current.displayName}`}><ArrowRight size={17} /></Link></td>
                  </tr>
                ))}</tbody>
              </table>
            </div>

            <div className="employee-mobile-list">{entries.map(({ current, dynamics }) => (
              <article className="employee-mobile-card" key={current.employeeId}>
                <div className="employee-mobile-card__head"><div className="employee-person"><span>{current.displayName.slice(0, 1).toUpperCase()}</span><div><Link to={{ pathname: `/employees/${current.employeeId}`, search: location.search }}>{current.displayName}</Link><small>{employeeRatingReason(current, rating.formula.minimumCoveragePercent)}</small></div></div><div className="employee-rank-cell"><strong>{current.rank ?? "—"}</strong>{rankDynamics(dynamics.rankImprovement)}</div></div>
                <div className="employee-mobile-card__metrics"><span><small>Общий балл</small><strong>{formatNumber(current.scores.overallScore)}</strong></span><span><small>Выручка</small><strong>{formatCompactMoney(current.netRevenue)}</strong></span><span><small>Смены</small><strong>{current.shiftCount}, {formatNumber(current.workedHours)} ч</strong></span></div>
                <ScoreProfile employee={current} />
                <div className="employee-mobile-card__actions"><Link to={{ pathname: `/employees/${current.employeeId}`, search: location.search }}>Подробнее <ArrowRight size={15} /></Link></div>
              </article>
            ))}</div>
          </>
        )}
      </section>

      {finalizeDialogOpen && <div className="confirm-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !finalizeMutation.isPending) setFinalizeDialogOpen(false); }}><section className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="finalize-title"><span className="confirm-dialog__icon"><LockKeyhole /></span><h2 id="finalize-title">Зафиксировать рейтинг?</h2><p>Результат за {formatDate(periodStart)} — {formatDate(periodEnd)} станет неизменяемым историческим снимком. Отменить это действие после подтверждения нельзя.</p><div><button className="button button--ghost" type="button" autoFocus disabled={finalizeMutation.isPending} onClick={() => setFinalizeDialogOpen(false)}>Отмена</button><button className="button button--primary" type="button" disabled={finalizeMutation.isPending} onClick={() => finalizeMutation.mutate()}>{finalizeMutation.isPending ? "Фиксируем…" : "Да, зафиксировать"}</button></div></section></div>}
    </div>
  );
}
