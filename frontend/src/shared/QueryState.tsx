import { AlertTriangle, RefreshCw } from "lucide-react";
import { isApiClientError } from "../api/client";

export function QueryError({ error, onRetry, compact = false }: { error: unknown; onRetry: () => void; compact?: boolean }) {
  const message = isApiClientError(error) ? error.message : "Не удалось загрузить данные.";
  const correlationId = isApiClientError(error) ? error.correlationId : undefined;
  return (
    <div className={`query-error ${compact ? "query-error--compact" : ""}`} role="alert">
      <AlertTriangle size={20} />
      <div><strong>Данные временно недоступны</strong><p>{message}</p>{correlationId && <small>Код обращения: {correlationId}</small>}</div>
      <button type="button" onClick={onRetry}><RefreshCw size={16} />Повторить</button>
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
