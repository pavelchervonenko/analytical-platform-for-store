import { useEffect } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { RefreshCw, ShieldCheck, Warehouse } from "lucide-react";
import { useNavigate } from "react-router";
import { isApiClientError } from "../api/client";
import { queryKeys } from "../api/queries";
import { currentDateInTimeZone, shiftDate } from "../shared/date";
import {
  adminKeys,
  createBackfill,
  getSyncJobs,
  type SyncJob
} from "./api";

const terminal = new Set<SyncJob["status"]>(["SUCCESS", "FAILED", "CANCELLED", "UNKNOWN"]);
const phaseLabel: Record<string, string> = {
  STORES: "магазины",
  EMPLOYEES: "сотрудники",
  SALES: "продажи",
  RETURNS: "возвраты"
};

export function InitialStoreSetup() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const today = currentDateInTimeZone("Europe/Kaliningrad");
  const periodStart = shiftDate(today, -6);
  const jobsQuery = useQuery({
    queryKey: adminKeys.syncJobs,
    queryFn: getSyncJobs,
    refetchInterval: (query) =>
      query.state.data?.some((job) => !terminal.has(job.status)) ? 3_000 : false
  });
  const activeJob = jobsQuery.data?.find((job) => !terminal.has(job.status));
  const mutation = useMutation({
    mutationFn: () => createBackfill(periodStart, today),
    onSuccess: async () => {
      navigate("/admin?adminView=sync", { replace: true });
      await queryClient.invalidateQueries({ queryKey: adminKeys.syncJobs });
    }
  });
  const trackedJob = mutation.data
    ? jobsQuery.data?.find((job) => job.id === mutation.data.id) ?? mutation.data
    : activeJob;

  useEffect(() => {
    if (trackedJob && !terminal.has(trackedJob.status)) {
      void queryClient.invalidateQueries({ queryKey: queryKeys.stores });
    }
  }, [queryClient, trackedJob]);

  const run = () => {
    if (window.confirm(
      "Запустить единую синхронизацию магазинов, сотрудников, продаж и возвратов за последние 7 дней?"
    )) {
      mutation.mutate();
    }
  };
  const error = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "Не удалось создать задачу синхронизации."
    : jobsQuery.isError
      ? "Не удалось получить состояние задач синхронизации."
      : trackedJob?.status === "FAILED"
        ? trackedJob.errorSummary ?? "Задача синхронизации завершилась с ошибкой."
        : null;
  const busy = mutation.isPending || Boolean(activeJob);

  return (
    <main className="workspace-state workspace-bootstrap">
      <section className="workspace-bootstrap__card">
        <span className="context-icon" aria-hidden="true"><Warehouse /></span>
        <p className="eyebrow">Первичная настройка</p>
        <h1>Подключите первый магазин</h1>
        <p>
          Запустите единую фоновую синхронизацию. Backend последовательно обновит
          магазины, сотрудников, продажи и возвраты и продолжит работу независимо
          от открытой вкладки.
        </p>

        {error && <p className="form-error" role="alert">{error}</p>}
        {trackedJob && (
          <p className="workspace-bootstrap__result" role="status">
            Статус: {trackedJob.status.toLowerCase()} · этап:{" "}
            {phaseLabel[trackedJob.phase ?? ""] ?? "ожидание"}
          </p>
        )}

        <button
          className="button button--primary"
          type="button"
          disabled={busy}
          onClick={run}
        >
          <RefreshCw className={busy ? "is-spinning" : ""} size={17} />
          {mutation.isPending
            ? "Создаем задачу…"
            : activeJob
              ? "Синхронизация выполняется"
              : "Синхронизировать данные"}
        </button>

        <div className="workspace-bootstrap__note">
          <ShieldCheck aria-hidden="true" />
          <span>
            Запускается durable job за последние 7 дней; полный исторический
            период можно загрузить позже в разделе администрирования.
          </span>
        </div>
      </section>
    </main>
  );
}