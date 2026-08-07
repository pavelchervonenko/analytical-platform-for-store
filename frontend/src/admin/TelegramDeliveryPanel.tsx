import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { BellRing, Bot, Clock3, RefreshCw, ShieldAlert, ShieldCheck, TriangleAlert, UserRoundX } from "lucide-react";
import { QueryError } from "../shared/QueryState";
import { adminKeys, getTelegramDeliveryOperations, type TelegramDeliveryOperations } from "./api";
import { TelegramResendDialog } from "./TelegramResendDialog";

type Incident = TelegramDeliveryOperations["incidents"][number];

const attentionLabel: Record<TelegramDeliveryOperations["summary"]["attentionLevel"], string> = {
  NORMAL: "Доставка работает штатно",
  WARNING: "Есть события для проверки",
  CRITICAL: "Требуется внимание",
  UNKNOWN: "Неизвестное состояние"
};

function statusLabel(status: Incident["status"]): string {
  switch (status) {
    case "UNKNOWN_OUTCOME": return "Нужно проверить";
    case "PERMANENT_FAILED": return "Не доставлено";
    case "WAITING_RETRY": return "Ожидает повтора";
    case "RUNNING": return "Отправляется дольше обычного";
    default: return "Служебное событие";
  }
}

function eventLabel(incident: Incident): string {
  if (incident.deliveryKind === "LINK_CONFIRMATION") return "Подтверждение подключения";
  if (incident.eventType === "WEEKLY_REPORT_READY") return "Еженедельный отчет";
  if (incident.eventType === "WEEKLY_REPORT_REVISED") return "Обновленный еженедельный отчет";
  return "Уведомление";
}

function formatDate(value: string | null): string {
  return value ? new Date(value).toLocaleString("ru-RU") : "—";
}

function canResend(incident: Incident): boolean {
  return incident.deliveryKind === "NOTIFICATION"
    && (incident.status === "PERMANENT_FAILED" || incident.status === "UNKNOWN_OUTCOME")
    && Date.parse(incident.expiresAt) > Date.now();
}

export function TelegramDeliveryPanel() {
  const [selectedIncident, setSelectedIncident] = useState<Incident | null>(null);
  const query = useQuery({
    queryKey: adminKeys.telegramDeliveries,
    queryFn: getTelegramDeliveryOperations,
    refetchInterval: (state) => state.state.data?.summary.attentionLevel === "NORMAL" ? 60_000 : 15_000
  });

  if (query.isPending) {
    return <div className="panel-loader"><span className="spinner" />Проверяем доставку Telegram…</div>;
  }
  if (query.isError) {
    return <QueryError error={query.error} onRetry={() => void query.refetch()} />;
  }

  const { summary, incidents } = query.data;
  const actionable = summary.readyPending + summary.readyRetries;

  return <><div className="telegram-ops-layout">
    <section className={`panel telegram-ops-summary is-${summary.attentionLevel.toLowerCase()}`}>
      <div className="panel__heading">
        <div><p className="eyebrow">Telegram-уведомления</p><h2>{attentionLabel[summary.attentionLevel]}</h2></div>
        {summary.attentionLevel === "NORMAL" ? <ShieldCheck /> : <ShieldAlert />}
      </div>
      <div className="telegram-ops-metrics">
        <article><BellRing /><strong>{actionable}</strong><span>готово к отправке</span></article>
        <article><Clock3 /><strong>{summary.running}</strong><span>в обработке</span></article>
        <article><TriangleAlert /><strong>{summary.permanentFailed}</strong><span>не доставлено</span></article>
        <article><ShieldAlert /><strong>{summary.unknownOutcome}</strong><span>нужно проверить</span></article>
        <article><Bot /><strong>{summary.activeSubscriptions}</strong><span>активных подписок</span></article>
        <article><UserRoundX /><strong>{summary.blockedSubscriptions}</strong><span>заблокировали бота</span></article>
      </div>
      <p className="telegram-ops-boundary">
        Сообщения с неопределенным статусом не отправляются повторно автоматически, чтобы избежать дублей.
      </p>
    </section>

    <section className="panel telegram-ops-incidents" aria-live="polite">
      <div className="panel__heading">
        <h2>Проблемы доставки</h2>
        <button className="icon-button" type="button" onClick={() => void query.refetch()} aria-label="Обновить">
          <RefreshCw className={query.isFetching ? "is-spinning" : ""} />
        </button>
      </div>
      {incidents.length === 0 ? <div className="panel-empty">
        <ShieldCheck /><strong>Инцидентов нет</strong><p>Очередь и Telegram-подписки не требуют вмешательства.</p>
      </div> : <div className="telegram-incident-list">{incidents.map((incident) => <article key={incident.deliveryId}>
        <div className="telegram-incident-title">
          <span className={`telegram-incident-status is-${incident.status.toLowerCase()}`}>{statusLabel(incident.status)}</span>
          <strong>{incident.recipientName}</strong>
          <small>{incident.storeName ?? "Системное уведомление"}</small>
        </div>
        <div><strong>{eventLabel(incident)}</strong><small>Попыток: {incident.attemptCount} из {incident.maxAttempts}</small></div>
        <div><strong>Сообщение не подтверждено</strong><small>{canResend(incident) ? "Можно отправить повторно" : "Дополнительных действий не требуется"}</small></div>
        <div className="telegram-incident-actions">
          <time dateTime={incident.updatedAt}>{formatDate(incident.updatedAt)}</time>
          {canResend(incident)
            ? <button type="button" onClick={() => setSelectedIncident(incident)}>Повторить вручную</button>
            : incident.deliveryKind === "NOTIFICATION"
              && (incident.status === "PERMANENT_FAILED" || incident.status === "UNKNOWN_OUTCOME")
              && <small>Срок доставки истек</small>}
        </div>
      </article>)}</div>}
    </section>
  </div>
  {selectedIncident && <TelegramResendDialog incident={selectedIncident} onClose={() => setSelectedIncident(null)} />}
  </>;
}
