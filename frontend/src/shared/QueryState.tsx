import { AlertTriangle, RefreshCw } from "lucide-react";
import { isApiClientError } from "../api/client";

export interface QueryErrorPresentation {
  title: string;
  message: string;
  retryLabel: string | null;
  reference: string | null;
}

const contractErrorCodes = new Set([
  "CONTRACT_MISMATCH", "INVALID_RESPONSE_TYPE", "INVALID_JSON", "ETAG_MISSING", "ETAG_INVALID"
]);

export function queryErrorPresentation(error: unknown): QueryErrorPresentation {
  if (!isApiClientError(error)) {
    return { title: "Не удалось загрузить данные", message: "Обновите страницу и повторите попытку.", retryLabel: "Повторить", reference: null };
  }
  const reference = error.correlationId ?? null;
  if (error.status === 401) return { title: "Сессия завершена", message: "Войдите снова, чтобы продолжить работу.", retryLabel: null, reference };
  if (error.status === 403) return { title: "Нет доступа", message: "Этот раздел или действие недоступны для вашей роли.", retryLabel: null, reference };
  if (error.status === 404) return { title: "Данные не найдены", message: error.message, retryLabel: null, reference };
  if ([409, 412, 428].includes(error.status)) return { title: "Данные изменились", message: error.message, retryLabel: "Обновить", reference };
  if (contractErrorCodes.has(error.code)) return { title: "Не удалось обработать ответ сервера", message: "Обновите данные. Если ошибка повторится, сообщите администратору.", retryLabel: "Обновить", reference };
  if (error.status === 0 || error.status >= 500 || error.status === 429) return { title: "Сервис временно недоступен", message: error.message, retryLabel: "Повторить", reference };
  return { title: "Не удалось загрузить данные", message: error.message, retryLabel: "Повторить", reference };
}

function ErrorReference({ reference }: { reference: string | null }) {
  if (!reference) return null;
  return <details className="query-error-reference"><summary>Подробности</summary><small>Код обращения: {reference}</small></details>;
}

export function QueryError({ error, onRetry, compact = false }: { error: unknown; onRetry: () => void; compact?: boolean }) {
  const presentation = queryErrorPresentation(error);
  return (
    <div className={`query-error ${compact ? "query-error--compact" : ""}`} role="alert">
      <AlertTriangle size={20} />
      <div><strong>{presentation.title}</strong><p>{presentation.message}</p><ErrorReference reference={presentation.reference} /></div>
      {presentation.retryLabel && <button type="button" onClick={onRetry}><RefreshCw size={16} />{presentation.retryLabel}</button>}
    </div>
  );
}

export function InlineQueryError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const presentation = queryErrorPresentation(error);
  return (
    <div className="inline-query-error" role="status">
      <AlertTriangle size={18} />
      <div><strong>{presentation.title}</strong><p>{presentation.message}</p><ErrorReference reference={presentation.reference} /></div>
      {presentation.retryLabel && <button type="button" onClick={onRetry}><RefreshCw size={15} />{presentation.retryLabel}</button>}
    </div>
  );
}

export function StaleDataNote({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const reference = isApiClientError(error) ? error.correlationId ?? null : null;
  return (
    <div className="stale-data-note" role="status">
      <span>Не удалось обновить. Показаны последние доступные данные.</span>
      <button type="button" onClick={onRetry}><RefreshCw size={14} />Повторить</button>
      <ErrorReference reference={reference} />
    </div>
  );
}

export function PanelSkeleton({ rows = 3 }: { rows?: number }) {
  return <div className="skeleton-panel" aria-busy="true" aria-label="Загрузка данных">{Array.from({ length: rows }, (_, index) => <span key={index} />)}</div>;
}

export function Delta({ value }: { value: number | null | undefined }) {
  if (value == null) return <span className="delta delta--neutral">Нет сравнения</span>;
  const direction = value > 0 ? "positive" : value < 0 ? "negative" : "neutral";
  const prefix = value > 0 ? "+" : "";
  return <span className={`delta delta--${direction}`}>{prefix}{new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 1 }).format(value)}%</span>;
}
