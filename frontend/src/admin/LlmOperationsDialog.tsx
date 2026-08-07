import { useState, type FormEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { RefreshCcw, Square, TriangleAlert, X } from "lucide-react";
import { isApiClientError } from "../api/client";
import {
  cancelLlmJob,
  llmOperationsKey,
  regenerateLlmInterpretation,
  type LlmJobIncident
} from "./llm-api";

interface LlmOperationsDialogProps {
  action: "regenerate" | "cancel";
  incident: LlmJobIncident;
  onClose: () => void;
}

export function LlmOperationsDialog({ action, incident, onClose }: LlmOperationsDialogProps) {
  const queryClient = useQueryClient();
  const [reason, setReason] = useState("");
  const normalizedReason = reason.trim();
  const valid = normalizedReason.length >= 10 && normalizedReason.length <= 500;
  const mutation = useMutation({
    mutationFn: () => action === "regenerate"
      ? regenerateLlmInterpretation(incident.snapshotId, normalizedReason)
      : cancelLlmJob(incident.jobId, normalizedReason),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: llmOperationsKey });
      onClose();
    }
  });
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (valid && !mutation.isPending) mutation.mutate();
  };
  const regenerate = action === "regenerate";
  const error = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "Операцию не удалось выполнить. Обновите состояние и попробуйте снова."
    : null;

  return <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
    if (event.target === event.currentTarget && !mutation.isPending) onClose();
  }}>
    <section className="admin-modal llm-action-modal" role="dialog" aria-modal="true" aria-labelledby="llm-action-title">
      <header>
        <h2 id="llm-action-title">{regenerate ? "Повторить ИИ-разбор" : "Отменить задачу"}</h2>
        <button className="icon-button" type="button" onClick={onClose} disabled={mutation.isPending} aria-label="Закрыть"><X /></button>
      </header>
      <form className="admin-form" onSubmit={submit}>
        <p className="admin-form-note admin-form-note--warning llm-action-warning">
          <TriangleAlert />
          <span>{regenerate
            ? "Будет подготовлена новая версия разбора. Текущая версия останется доступной, пока новая не будет готова."
            : "Отмена может занять некоторое время. После отмены результат не будет опубликован."}</span>
        </p>
        <dl className="telegram-resend-facts">
          <div><dt>Магазин</dt><dd>{incident.storeName}</dd></div>
          <div><dt>Период</dt><dd>{incident.periodStart} — {incident.periodEnd}</dd></div>
        </dl>
        <label className="field">
          <span>Причина операции</span>
          <textarea value={reason} onChange={(event) => setReason(event.target.value)} minLength={10} maxLength={500} required />
          <small>{normalizedReason.length}/500, минимум 10 символов</small>
        </label>
        {error && <p className="form-error" role="alert">{error}</p>}
        <footer>
          <button className="button button--ghost" type="button" onClick={onClose} disabled={mutation.isPending}>Назад</button>
          <button className="button button--primary" type="submit" disabled={!valid || mutation.isPending}>
            {regenerate ? <RefreshCcw /> : <Square />}
            {mutation.isPending ? "Выполняем…" : regenerate ? "Создать новую версию" : "Отменить задачу"}
          </button>
        </footer>
      </form>
    </section>
  </div>;
}
