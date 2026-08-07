import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, CheckCircle2, Clock3, FileArchive, Play, RotateCcw, ShieldCheck, TriangleAlert } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router";
import { isApiClientError } from "../api/client";
import { queryKeys } from "../api/queries";
import { useWorkspace } from "../stores/WorkspaceProvider";
import {
  adminKeys,
  backfillReports,
  cancelReportBackfill,
  getReportBackfillJobs,
  type ReportBackfillJob
} from "./api";

const terminal = new Set<ReportBackfillJob["status"]>(["SUCCESS", "FAILED", "CANCELLED", "UNKNOWN"]);
const statusLabel: Record<ReportBackfillJob["status"], string> = {
  PENDING: "Ожидает",
  RUNNING: "Выполняется",
  WAITING_RETRY: "Ожидает повтора",
  SUCCESS: "Завершена",
  FAILED: "Ошибка",
  CANCELLED: "Отменена",
  UNKNOWN: "Неизвестный статус"
};

function errorMessage(error: unknown): string {
  if (!isApiClientError(error)) return "Не удалось создать задачу восстановления отчетов.";
  return error.message;
}

function ReportOperationsContent({ storeId, storeName, currentYear }: { storeId: string; storeName: string; currentYear: number }) {
  const queryClient = useQueryClient();
  const [year, setYear] = useState(currentYear);
  const [selectedJobId, setSelectedJobId] = useState<string | null>(null);
  const jobsQuery = useQuery({ queryKey: adminKeys.reportBackfillJobs, queryFn: getReportBackfillJobs, refetchInterval: 5_000 });
  const mutation = useMutation({
    mutationFn: (requestedYear: number) => backfillReports(storeId, requestedYear),
    onSuccess: async (job) => {
      setSelectedJobId(job.id);
      await queryClient.invalidateQueries({ queryKey: adminKeys.reportBackfillJobs });
    }
  });
  const cancelMutation = useMutation({
    mutationFn: cancelReportBackfill,
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: adminKeys.reportBackfillJobs })
  });
  const storeJobs = (jobsQuery.data ?? []).filter((job) => job.storeId === storeId);
  const selectedJob = storeJobs.find((job) => job.id === selectedJobId) ?? mutation.data ?? storeJobs[0];
  const valid = Number.isInteger(year) && year >= 2000 && year <= 2100;

  useEffect(() => {
    if (selectedJob?.status === "SUCCESS") {
      void queryClient.invalidateQueries({ queryKey: queryKeys.reportArchive(storeId) });
    }
  }, [queryClient, selectedJob?.status, storeId]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!valid || !window.confirm(`Поставить восстановление отчетов магазина «${storeName}» за ${year} год в очередь?`)) return;
    mutation.mutate(year);
  };

  return <div className="admin-report-layout">
    <section className="panel admin-report-action">
      <span className="context-icon"><FileArchive /></span>

      <h2>Восстановить архив</h2>
      <p>Система восстановит отчеты за выбранный год. Процесс продолжится, даже если закрыть эту страницу.</p>
      <form onSubmit={submit}>
        <label className="field"><span>Календарный год</span><input type="number" min="2000" max="2100" step="1" value={year} disabled={mutation.isPending} onChange={(event) => { setYear(Number(event.target.value)); mutation.reset(); }} /></label>
        <small className={valid ? "" : "field-error"}>Допустимый диапазон: 2000–2100.</small>
        {mutation.isError && <p className="form-error" role="alert">{errorMessage(mutation.error)}</p>}
        <button className="button button--primary" type="submit" disabled={!valid || mutation.isPending}><Play size={16} />{mutation.isPending ? "Создаем задачу…" : "Запустить восстановление"}</button>
      </form>
      <div className="admin-safety-note"><TriangleAlert /><p><strong>Одна активная задача на магазин.</strong><span>Повторный запуск не создаст дубликаты отчетов.</span></p></div>
    </section>

    <section className="panel admin-report-result" aria-live="polite">
      <div className="panel__heading"><div><p className="eyebrow">Состояние задачи</p><h2>{selectedJob ? `${selectedJob.year}: ${statusLabel[selectedJob.status]}` : "Задач пока нет"}</h2></div>{selectedJob?.status === "SUCCESS" ? <CheckCircle2 /> : selectedJob ? <Clock3 /> : <ShieldCheck />}</div>
      {selectedJob ? <>
        <dl>
          <div><dt>Текущий шаг</dt><dd>{selectedJob.phase === "MONTHLY" ? `Месяц ${selectedJob.cursorMonth}` : selectedJob.phase === "ANNUAL" ? "Годовой отчет" : "Неизвестный этап"}</dd></div>
          <div><dt>Шагов завершено</dt><dd>{selectedJob.completedSteps} из 13</dd></div>
          <div><dt>Месячных отчетов создано</dt><dd>{selectedJob.monthlyCreatedCount}</dd></div>
          <div><dt>Уже существовало</dt><dd>{selectedJob.monthlyExistingCount}</dd></div>
        </dl>
        {selectedJob.errorSummary && <p className="form-error" role="alert">{selectedJob.errorSummary}</p>}
        {!terminal.has(selectedJob.status) && <button className="admin-job-cancel" type="button" disabled={cancelMutation.isPending} onClick={() => { if (window.confirm("Отменить восстановление отчетов? Обработка текущего месяца может завершиться перед остановкой.")) cancelMutation.mutate(selectedJob.id); }}><Ban /><span>Отменить</span></button>}
        {selectedJob.status === "WAITING_RETRY" && <p><RotateCcw /> Задача продолжит работу после задержки.</p>}
        {selectedJob.status === "SUCCESS" && <Link className="button button--ghost" to={{ pathname: "/reports", search: `?store=${encodeURIComponent(storeId)}` }}>Открыть архив</Link>}
      </> : <div className="admin-report-rules">
        <p><strong>Месячный снимок</strong><span>Создается по последней выплаченной версии зарплаты.</span></p>
        <p><strong>Годовой снимок</strong><span>Только для закрытого года с полным набором месяцев.</span></p>
        <p><strong>Восстановление</strong><span>Прогресс сохраняется, и восстановление можно продолжить позже.</span></p>
      </div>}
    </section>
  </div>;
}

export function ReportOperationsPanel() {
  const { selectedStore, today } = useWorkspace();
  return <ReportOperationsContent key={selectedStore.id} storeId={selectedStore.id} storeName={selectedStore.name} currentYear={Number(today.slice(0, 4))} />;
}
