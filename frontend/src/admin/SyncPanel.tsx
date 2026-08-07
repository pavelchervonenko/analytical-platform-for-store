import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, Clock3, DatabaseZap, Play, RefreshCw, RotateCcw, TriangleAlert } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link } from "react-router";
import { isApiClientError } from "../api/client";
import { inclusiveDayCount, monthRange } from "../shared/date";
import { QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { adminKeys, cancelSyncJob, createBackfill, getSyncClassificationReadiness, getSyncJobs, type SyncJob } from "./api";
import { SyncJobDetails } from "./SyncJobDetails";

const terminal = new Set<SyncJob["status"]>(["SUCCESS", "FAILED", "CANCELLED", "UNKNOWN"]);
const statusLabel: Record<SyncJob["status"], string> = {
  PENDING: "В очереди",
  RUNNING: "Выполняется",
  WAITING_RETRY: "Ожидает повтора",
  SUCCESS: "Завершена",
  FAILED: "Ошибка",
  CANCELLED: "Отменена",
  UNKNOWN: "Неизвестный статус"
};
const syncPhases = [
  ["STORES", "Магазины"],
  ["EMPLOYEES", "Сотрудники"],
  ["SALES", "Продажи"],
  ["RETURNS", "Возвраты"]
] as const;

function phaseLabel(phase: SyncJob["phase"]): string {
  const known = syncPhases.find(([value]) => value === phase);
  return known?.[1] ?? (phase ? "Неизвестный этап" : "Ожидание запуска");
}

function apiMessage(error: unknown): string {
  return isApiClientError(error) ? error.message : "Не удалось выполнить операцию.";
}

export function SyncPanel() {
  const { month, today } = useWorkspace();
  const queryClient = useQueryClient();
  const defaultRange = monthRange(month);
  const [start, setStart] = useState(defaultRange.start);
  const [end, setEnd] = useState(defaultRange.end > today ? today : defaultRange.end);
  const [error, setError] = useState<string | null>(null);
  const jobsQuery = useQuery({
    queryKey: adminKeys.syncJobs,
    queryFn: getSyncJobs,
    refetchInterval: (query) =>
      query.state.data?.some((job) => !terminal.has(job.status)) ? 5_000 : false
  });
  const readinessQuery = useQuery({
    queryKey: [...adminKeys.syncReadiness, start],
    queryFn: () => getSyncClassificationReadiness(start)
  });
  const observedActiveJobs = useRef(new Set<string>());
  useEffect(() => {
    for (const job of jobsQuery.data ?? []) {
      if (!terminal.has(job.status)) {
        observedActiveJobs.current.add(job.id);
      } else if (job.status === "SUCCESS" && observedActiveJobs.current.delete(job.id)) {
        void queryClient.invalidateQueries({ queryKey: ["stores"] });
      }
    }
  }, [jobsQuery.data, queryClient]);
  const backgroundJobActive =
    jobsQuery.data?.some((job) => !terminal.has(job.status)) ?? false;
  const createMutation = useMutation({
    mutationFn: () => createBackfill(start, end),
    onSuccess: async () => {
      setError(null);
      await queryClient.invalidateQueries({ queryKey: adminKeys.syncJobs });
    },
    onError: (value) => setError(apiMessage(value))
  });
  const cancelMutation = useMutation({
    mutationFn: cancelSyncJob,
    onSuccess: async (cancelledJob) => {
      setError(null);
      queryClient.setQueryData<SyncJob[]>(adminKeys.syncJobs, (jobs) =>
        jobs?.map((job) => job.id === cancelledJob.id ? cancelledJob : job)
      );
      await queryClient.invalidateQueries({ queryKey: adminKeys.syncJobs });
    },
    onError: (value) => setError(apiMessage(value))
  });
  const days = inclusiveDayCount(start, end);
  const valid = days > 0 && days <= 730 && end <= today;
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (valid && readinessQuery.data?.ready && !backgroundJobActive) createMutation.mutate();
  };

  if (jobsQuery.isPending || readinessQuery.isPending) {
    return <div className="panel-loader"><span className="spinner" />Проверяем готовность синхронизации…</div>;
  }
  if (jobsQuery.isError || readinessQuery.isError) {
    const failedQuery = jobsQuery.isError ? jobsQuery : readinessQuery;
    return <QueryError error={failedQuery.error} onRetry={() => void failedQuery.refetch()} />;
  }
  const readiness = readinessQuery.data;

  return <div className="admin-sync-layout">
    <section className="panel admin-sync-card">
      <span className="context-icon"><DatabaseZap /></span>
      <p className="eyebrow">Единая синхронизация</p>
      <h2>Синхронизировать данные</h2>
      <p>Система обновит магазины, сотрудников, продажи и возвраты за выбранный период.</p>
      {!readiness.ready && <div className="admin-safety-note admin-safety-note--blocking">
        <TriangleAlert />
        <p><strong>Сначала импортируйте классификацию товаров.</strong><span>Для {start} нет ни одного действующего утвержденного назначения. Без него продажи попадут в «Неразмеченные».</span></p>
        <Link className="button button--secondary" to="/admin?adminView=category-import">Импортировать</Link>
      </div>}
      {readiness.ready && readiness.unmappedSalesItemCount > 0 && <div className="admin-safety-note">
        <TriangleAlert />
        <p><strong>Есть ранее загруженные неразмеченные позиции: {readiness.unmappedSalesItemCount}.</strong><span>После импорта запустите повторную синхронизацию исходного периода, чтобы обновить их снимки классификации.</span></p>
      </div>}
      <ol className="sync-pipeline" aria-label="Этапы синхронизации">
        {syncPhases.map(([, label], index) =>
          <li key={label}><span>{index + 1}</span>{label}</li>
        )}
      </ol>
      <form onSubmit={submit}>
        <div className="admin-date-range">
          <label className="field">
            <span>Начало</span>
            <input type="date" value={start} max={today}
              onChange={(event) => setStart(event.target.value)} />
          </label>
          <label className="field">
            <span>Окончание</span>
            <input type="date" value={end} min={start} max={today}
              onChange={(event) => setEnd(event.target.value)} />
          </label>
        </div>
        <small className={valid ? "" : "field-error"}>
          {valid ? `${days} календарных дней` :
            "Период должен быть от 1 до 730 дней и не заходить в будущее."}
        </small>
        {error && <p className="form-error" role="alert">{error}</p>}
        <button className="button button--primary" type="submit"
          disabled={!valid || !readiness.ready || backgroundJobActive || createMutation.isPending}>
          <Play size={16} />
          {createMutation.isPending ? "Создаем задачу…" :
            backgroundJobActive ? "Задача уже выполняется" : "Синхронизировать"}
        </button>
      </form>
      <div className="admin-safety-note">
        <TriangleAlert />
        <p>
          <strong>Одновременно выполняется одна загрузка.</strong>
          <span>Загрузка продолжится в фоне. Если возникнет временная ошибка, система повторит попытку.</span>
        </p>
      </div>
    </section>

    <section className="panel admin-jobs">
      <div className="panel__heading">
        <div><p className="eyebrow">История обновлений</p><h2>Выполненные и текущие загрузки</h2></div>
        <button className="icon-button" type="button"
          onClick={() => void jobsQuery.refetch()} aria-label="Обновить">
          <RefreshCw className={jobsQuery.isFetching ? "is-spinning" : ""} />
        </button>
      </div>
      {jobsQuery.data.length === 0
        ? <div className="panel-empty">
            <Clock3 />
            <strong>Задач пока нет</strong>
            <p>Запустите первую синхронизацию за короткий проверочный период.</p>
          </div>
        : <div className="admin-job-list">
            {jobsQuery.data.map((job) =>
              <article key={job.id}>
                <div className="admin-job-status">
                  <span className={`sync-dot sync-dot--${job.status.toLowerCase()}`} />
                  <div><strong>{statusLabel[job.status]}</strong><small>{phaseLabel(job.phase)}</small></div>
                </div>
                <div>
                  <strong>
                    {new Date(job.periodStart).toLocaleDateString("ru-RU")} —{" "}
                    {new Date(job.periodEnd).toLocaleDateString("ru-RU")}
                  </strong>
                  <small>Шагов: {job.completedSteps}, повторов: {job.totalRetries}</small>
                </div>
                <div>
                  <strong>
                    {job.startedAt ? new Date(job.startedAt).toLocaleString("ru-RU") : "Не начата"}
                  </strong>
                  <small>
                    {job.nextAttemptAt
                      ? `Повтор ${new Date(job.nextAttemptAt).toLocaleString("ru-RU")}`
                      : `Обновлено ${new Date(job.updatedAt).toLocaleTimeString("ru-RU")}`}
                  </small>
                </div>
                {job.errorSummary && <p className="admin-job-error">{job.errorSummary}</p>}
                <div className="admin-job-actions">
                  <SyncJobDetails jobId={job.id} />
                  <button className="admin-job-cancel" type="button"
                    disabled={terminal.has(job.status) || cancelMutation.isPending}
                    onClick={() => {
                      if (window.confirm(
                        "Отменить задачу синхронизации? Текущий этап может завершиться перед остановкой."
                      )) cancelMutation.mutate(job.id);
                    }}>
                    {job.status === "WAITING_RETRY" ? <RotateCcw /> : <Ban />}
                    <span>Отменить</span>
                  </button>
                </div>
              </article>
            )}
          </div>}
    </section>
  </div>;
}