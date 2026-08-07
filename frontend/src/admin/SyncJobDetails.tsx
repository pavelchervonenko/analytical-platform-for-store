import { useQuery } from "@tanstack/react-query";
import { Eye, X } from "lucide-react";
import { useState } from "react";
import { QueryError } from "../shared/QueryState";
import { adminKeys, getSyncJob } from "./api";

function dateTime(value: string | null): string {
  return value ? new Date(value).toLocaleString("ru-RU") : "—";
}

const statusLabels: Readonly<Record<string, string>> = {
  PENDING: "Ожидает запуска", RUNNING: "Выполняется", WAITING_RETRY: "Ожидает повтора",
  SUCCESS: "Завершена", FAILED: "Не завершена", CANCELLED: "Отменена", UNKNOWN: "Статус уточняется"
};
const phaseLabels: Readonly<Record<string, string>> = {
  STORES: "Магазины", EMPLOYEES: "Сотрудники", SALES: "Продажи", RETURNS: "Возвраты"
};

export function SyncJobDetails({ jobId }: { jobId: string }) {
  const [open, setOpen] = useState(false);
  const query = useQuery({
    queryKey: [...adminKeys.syncJobs, jobId],
    queryFn: () => getSyncJob(jobId),
    enabled: open,
    refetchInterval: open ? 5_000 : false
  });

  return <>
    <button className="admin-job-detail-button" type="button" onClick={() => setOpen(true)}><Eye /><span>Подробнее</span></button>
    {open && <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setOpen(false); }}>
      <section className="admin-modal sync-job-modal" role="dialog" aria-modal="true" aria-labelledby={`sync-job-${jobId}`}>
        <header><h2 id={`sync-job-${jobId}`}>Задача синхронизации</h2><button className="icon-button" type="button" onClick={() => setOpen(false)} aria-label="Закрыть"><X /></button></header>
        <div className="sync-job-details">
          {query.isPending ? <div className="panel-loader"><span className="spinner" />Загружаем задачу…</div> : query.isError ? <QueryError error={query.error} onRetry={() => void query.refetch()} /> : <>
            <div className="sync-job-details__summary"><span className={`sync-dot sync-dot--${query.data.status.toLowerCase()}`} /><div><strong>{statusLabels[query.data.status] ?? "Статус уточняется"}</strong><small>{phaseLabels[query.data.phase ?? ""] ?? "Подготовка"}</small></div></div>
            <dl><div><dt>Период</dt><dd>{dateTime(query.data.periodStart)} — {dateTime(query.data.periodEnd)}</dd></div><div><dt>Прогресс</dt><dd>Завершено шагов: {query.data.completedSteps}</dd></div><div><dt>Запущена</dt><dd>{dateTime(query.data.startedAt)}</dd></div><div><dt>Завершена</dt><dd>{dateTime(query.data.finishedAt)}</dd></div></dl>
            {query.data.errorSummary && <p className="form-error">Не удалось завершить загрузку. Повторите попытку позже.</p>}
          </>}
        </div>
      </section>
    </div>}
  </>;
}
