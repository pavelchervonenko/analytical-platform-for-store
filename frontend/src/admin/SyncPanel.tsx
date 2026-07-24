import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, Clock3, DatabaseZap, Play, RefreshCw, RotateCcw, TriangleAlert } from "lucide-react";
import { useState, type FormEvent } from "react";
import { isApiClientError } from "../api/client";
import { inclusiveDayCount, monthRange } from "../shared/date";
import { QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { adminKeys, cancelSyncJob, createBackfill, getSyncJobs, type SyncJob } from "./api";

const terminal = new Set(["SUCCESS", "FAILED", "CANCELLED"]);
const statusLabel: Record<SyncJob["status"], string> = { PENDING: "В очереди", RUNNING: "Выполняется", WAITING_RETRY: "Ожидает повтора", SUCCESS: "Завершена", FAILED: "Ошибка", CANCELLED: "Отменена" };

function apiMessage(error: unknown): string { return isApiClientError(error) ? error.message : "Не удалось выполнить операцию."; }

export function SyncPanel() {
  const { month, today } = useWorkspace();
  const queryClient = useQueryClient();
  const defaultRange = monthRange(month);
  const [start, setStart] = useState(defaultRange.start);
  const [end, setEnd] = useState(defaultRange.end > today ? today : defaultRange.end);
  const [error, setError] = useState<string | null>(null);
  const jobsQuery = useQuery({ queryKey: adminKeys.syncJobs, queryFn: getSyncJobs, refetchInterval: (query) => query.state.data?.some((job) => !terminal.has(job.status)) ? 5_000 : false });
  const createMutation = useMutation({ mutationFn: () => createBackfill(start, end), onSuccess: async () => { setError(null); await queryClient.invalidateQueries({ queryKey: adminKeys.syncJobs }); }, onError: (value) => setError(apiMessage(value)) });
  const cancelMutation = useMutation({ mutationFn: cancelSyncJob, onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: adminKeys.syncJobs }); }, onError: (value) => setError(apiMessage(value)) });
  const days = inclusiveDayCount(start, end);
  const valid = days > 0 && days <= 730 && end <= today;
  const submit = (event: FormEvent) => { event.preventDefault(); if (valid) createMutation.mutate(); };

  if (jobsQuery.isPending) return <div className="panel-loader"><span className="spinner" />Загружаем задачи…</div>;
  if (jobsQuery.isError) return <QueryError error={jobsQuery.error} onRetry={() => void jobsQuery.refetch()} />;

  return <div className="admin-sync-layout">
    <section className="panel admin-backfill-card"><span className="context-icon"><DatabaseZap /></span><p className="eyebrow">Историческая загрузка</p><h2>Запустить backfill</h2><p>Задача сохранится в PostgreSQL и продолжит работу после перезапуска приложения.</p><form onSubmit={submit}><div className="admin-date-range"><label className="field"><span>Начало</span><input type="date" value={start} max={today} onChange={(event) => setStart(event.target.value)} /></label><label className="field"><span>Окончание</span><input type="date" value={end} min={start} max={today} onChange={(event) => setEnd(event.target.value)} /></label></div><small className={valid ? "" : "field-error"}>{valid ? `${days} календарных дней` : "Период должен быть от 1 до 730 дней и не заходить в будущее."}</small>{error && <p className="form-error" role="alert">{error}</p>}<button className="button button--primary" type="submit" disabled={!valid || createMutation.isPending}><Play size={16} />{createMutation.isPending ? "Создаём задачу…" : "Запустить"}</button></form><div className="admin-safety-note"><TriangleAlert /><p><strong>Одна активная задача на подключение.</strong><span>Backend сам ограничивает окна, повторы и бюджет запросов LiveSklad.</span></p></div></section>
    <section className="panel admin-jobs"><div className="panel__heading"><div><p className="eyebrow">Фоновая обработка</p><h2>Задачи синхронизации</h2></div><button className="icon-button" type="button" onClick={() => void jobsQuery.refetch()} aria-label="Обновить"><RefreshCw className={jobsQuery.isFetching ? "is-spinning" : ""} /></button></div>
      {jobsQuery.data.length === 0 ? <div className="panel-empty"><Clock3 /><strong>Задач пока нет</strong><p>Создайте первый короткий backfill для проверки интеграции.</p></div> : <div className="admin-job-list">{jobsQuery.data.map((job) => <article key={job.id}><div className="admin-job-status"><span className={`sync-dot sync-dot--${job.status.toLowerCase()}`} /> <div><strong>{statusLabel[job.status]}</strong><small>{job.jobType} · {job.phase ?? "ожидание"}</small></div></div><div><strong>{new Date(job.periodStart).toLocaleDateString("ru-RU")} — {new Date(job.periodEnd).toLocaleDateString("ru-RU")}</strong><small>Шагов: {job.completedSteps} · повторов: {job.totalRetries}</small></div><div><strong>{job.startedAt ? new Date(job.startedAt).toLocaleString("ru-RU") : "Не начата"}</strong><small>{job.nextAttemptAt ? `Повтор ${new Date(job.nextAttemptAt).toLocaleString("ru-RU")}` : `Обновлено ${new Date(job.updatedAt).toLocaleTimeString("ru-RU")}`}</small></div>{job.errorSummary && <p className="admin-job-error">{job.errorSummary}</p>}<button className="admin-job-cancel" type="button" disabled={terminal.has(job.status) || cancelMutation.isPending} onClick={() => { if (window.confirm("Отменить задачу синхронизации? Текущая фаза может завершиться перед остановкой.")) cancelMutation.mutate(job.id); }}>{job.status === "WAITING_RETRY" ? <RotateCcw /> : <Ban />}<span>Отменить</span></button></article>)}</div>}
    </section>
  </div>;
}
