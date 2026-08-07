import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  BadgeCheck,
  Clock3,
  RefreshCcw,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  Square,
  TriangleAlert,
  Workflow
} from "lucide-react";
import { QueryError } from "../shared/QueryState";
import { LlmOperationsDialog } from "./LlmOperationsDialog";
import {
  getLlmOperations,
  llmOperationsKey,
  type LlmJobIncident,
  type LlmOperations
} from "./llm-api";
import "./llm-operations.css";

type Action = { type: "regenerate" | "cancel"; incident: LlmJobIncident };

const attentionLabel: Record<LlmOperations["summary"]["attentionLevel"], string> = {
  NORMAL: "ИИ-разбор работает штатно",
  WARNING: "Есть задачи, ожидающие повтора",
  CRITICAL: "Требуется внимание",
  UNKNOWN: "Неизвестное состояние"
};

function formatDate(value: string | null): string {
  return value ? new Date(value).toLocaleString("ru-RU") : "—";
}

function statusLabel(status: LlmJobIncident["status"]): string {
  switch (status) {
    case "PENDING": return "В очереди";
    case "RUNNING": return "Выполняется";
    case "WAITING_RETRY": return "Ожидает повтора";
    case "SUCCESS": return "Опубликовано";
    case "VALIDATION_FAILED": return "Не прошла проверка";
    case "FAILED": return "Ошибка";
    case "CANCELLED": return "Отменено";
    case "SKIPPED": return "Пропущено";
    default: return "Неизвестно";
  }
}

function canCancel(job: LlmJobIncident): boolean {
  return ["PENDING", "RUNNING", "WAITING_RETRY"].includes(job.status)
    && !job.cancelRequested;
}

function canRegenerate(job: LlmJobIncident, enabled: boolean): boolean {
  return enabled && ["SUCCESS", "FAILED", "VALIDATION_FAILED", "CANCELLED"].includes(job.status);
}

export function LlmOperationsPanel() {
  const [action, setAction] = useState<Action | null>(null);
  const query = useQuery({
    queryKey: llmOperationsKey,
    queryFn: getLlmOperations,
    refetchInterval: (state) => state.state.data?.summary.attentionLevel === "NORMAL" ? 60_000 : 15_000
  });

  if (query.isPending) {
    return <div className="panel-loader"><span className="spinner" />Проверяем ИИ-разбор…</div>;
  }
  if (query.isError) {
    return <QueryError error={query.error} onRetry={() => void query.refetch()} />;
  }

  const { configuration, summary, incidents } = query.data;
  const queueSize = summary.pending + summary.waitingRetry + summary.running;
  const configured = configuration.snapshotsEnabled
    && configuration.generationEnabled
    && configuration.publicationEnabled
    && configuration.providerConfigured;

  return <>
    <div className="llm-ops-layout">
      <section className={`panel llm-ops-summary is-${summary.attentionLevel.toLowerCase()}`}>
        <div className="panel__heading">
          <div><p className="eyebrow">ИИ-разбор</p><h2>{attentionLabel[summary.attentionLevel]}</h2></div>
          {summary.attentionLevel === "NORMAL" ? <ShieldCheck /> : <ShieldAlert />}
        </div>
        {!configured && <p className="llm-ops-disabled"><TriangleAlert />ИИ-разбор пока недоступен. Проверьте настройки подключения.</p>}
        <div className="llm-ops-metrics">
          <article><Workflow /><strong>{queueSize}</strong><span>задач в контуре</span></article>
          <article><Activity /><strong>{summary.running}</strong><span>выполняется</span></article>
          <article><TriangleAlert /><strong>{summary.failed + summary.validationFailed}</strong><span>ошибок и отклонений</span></article>
          <article><BadgeCheck /><strong>{summary.succeededLast30Days}</strong><span>успешно за 30 дней</span></article>
                  </div>
      </section>

      <section className="panel llm-ops-jobs" aria-live="polite">
        <div className="panel__heading">
          <h2>Задачи ИИ-разбора</h2>
          <button className="icon-button" type="button" onClick={() => void query.refetch()} aria-label="Обновить"><RefreshCw className={query.isFetching ? "is-spinning" : ""} /></button>
        </div>
        {incidents.length === 0 ? <div className="panel-empty"><ShieldCheck /><strong>Активных задач и инцидентов нет</strong><p>Новые записи появятся после формирования недельного снимка.</p></div>
          : <div className="llm-job-list">{incidents.map((job) => <article key={job.jobId}>
            <div className="llm-job-title">
              <span className={`llm-job-status is-${job.status.toLowerCase()}`}>{statusLabel(job.status)}</span>
              <strong>{job.storeName}</strong>
              <small>{job.periodStart} — {job.periodEnd}</small>
            </div>
            <div><strong>{job.status === "SUCCESS" ? "Разбор готов" : job.status === "FAILED" || job.status === "VALIDATION_FAILED" ? "Не удалось подготовить разбор" : "Обработка продолжается"}</strong><small>{job.attemptCount > 1 ? `Попыток: ${job.attemptCount}` : "Дополнительных действий не требуется"}</small></div>
            <div className="llm-job-actions">
              <time dateTime={job.updatedAt}><Clock3 />{formatDate(job.updatedAt)}</time>
              {canCancel(job) && <button type="button" onClick={() => setAction({ type: "cancel", incident: job })}><Square />Отменить</button>}
              {canRegenerate(job, configuration.generationEnabled) && <button type="button" onClick={() => setAction({ type: "regenerate", incident: job })}><RefreshCcw />Повторить</button>}
            </div>
          </article>)}</div>}
      </section>
    </div>
    {action && <LlmOperationsDialog action={action.type} incident={action.incident} onClose={() => setAction(null)} />}
  </>;
}
