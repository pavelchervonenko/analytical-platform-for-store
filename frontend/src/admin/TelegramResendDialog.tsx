import { useState, type FormEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Send, TriangleAlert, X } from "lucide-react";
import { isApiClientError } from "../api/client";
import {
  adminKeys,
  resendTelegramDelivery,
  type TelegramDeliveryOperations
} from "./api";

type Incident = TelegramDeliveryOperations["incidents"][number];

interface TelegramResendDialogProps {
  incident: Incident;
  onClose: () => void;
}

function formatDate(value: string): string {
  return new Date(value).toLocaleString("ru-RU");
}

export function TelegramResendDialog({ incident, onClose }: TelegramResendDialogProps) {
  const queryClient = useQueryClient();
  const [reason, setReason] = useState("");
  const [riskAccepted, setRiskAccepted] = useState(false);
  const normalizedReason = reason.trim();
  const valid = normalizedReason.length >= 10 && normalizedReason.length <= 500 && riskAccepted;
  const mutation = useMutation({
    mutationFn: () => resendTelegramDelivery(incident.deliveryId, normalizedReason),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: adminKeys.telegramDeliveries });
      onClose();
    }
  });

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (valid && !mutation.isPending) mutation.mutate();
  };

  const error = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "Не удалось создать повторную доставку. Обновите данные и попробуйте снова."
    : null;

  return <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
    if (event.target === event.currentTarget && !mutation.isPending) onClose();
  }}>
    <section className="admin-modal telegram-resend-modal" role="dialog" aria-modal="true" aria-labelledby="telegram-resend-title">
      <header>
        <h2 id="telegram-resend-title">Повторить Telegram-уведомление</h2>
        <button className="icon-button" type="button" onClick={onClose} disabled={mutation.isPending} aria-label="Закрыть"><X /></button>
      </header>
      <form className="admin-form" onSubmit={submit}>
        <p className="admin-form-note admin-form-note--warning telegram-resend-warning">
          <TriangleAlert />
          <span><strong>Возможен дубль.</strong> Сообщение могло быть доставлено без подтверждения. При повторе получатель может увидеть его дважды.</span>
        </p>
        <dl className="telegram-resend-facts">
          <div><dt>Получатель</dt><dd>{incident.recipientName}</dd></div>
          <div><dt>Магазин</dt><dd>{incident.storeName ?? "Не указан"}</dd></div>
          <div><dt>Отправить до</dt><dd>{formatDate(incident.expiresAt)}</dd></div>
        </dl>
        <label className="field">
          <span>Причина ручного повтора</span>
          <textarea
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            minLength={10}
            maxLength={500}
            required
            placeholder="Например: подтверждено, что руководитель не получил недельный отчет"
          />
          <small>{normalizedReason.length}/500, минимум 10 символов</small>
        </label>
        <label className="telegram-resend-consent">
          <input type="checkbox" checked={riskAccepted} onChange={(event) => setRiskAccepted(event.target.checked)} />
          <span>Я понимаю риск повторной доставки и подтверждаю отправку.</span>
        </label>
        {error && <p className="form-error" role="alert">{error}</p>}
        <footer>
          <button className="button button--ghost" type="button" onClick={onClose} disabled={mutation.isPending}>Отмена</button>
          <button className="button button--primary" type="submit" disabled={!valid || mutation.isPending}>
            <Send />{mutation.isPending ? "Создаем доставку…" : "Повторить вручную"}
          </button>
        </footer>
      </form>
    </section>
  </div>;
}
