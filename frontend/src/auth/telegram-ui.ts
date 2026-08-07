import { isApiClientError } from "../api/client";
import type { TelegramChannelState } from "../api/contracts";

export function telegramPollInterval(
  state: TelegramChannelState | undefined
): number | false {
  if (state === "LINK_ISSUED") return 2_000;
  if (state === "PENDING_CONFIRMATION") return 3_000;
  if (state === "BOT_BLOCKED") return 10_000;
  return false;
}

export function telegramMutationError(error: unknown): string {
  if (!isApiClientError(error)) {
    return "Не удалось изменить подключение Telegram. Попробуйте еще раз.";
  }
  if (error.code === "TELEGRAM_LINK_THROTTLED") {
    return "Ссылка уже создавалась недавно. Подождите немного и повторите попытку.";
  }
  if (error.status === 412 || error.status === 428) {
    return "Состояние подключения уже изменилось. Мы обновляем его — повторите действие.";
  }
  if (error.code === "TELEGRAM_LINK_STATE_CONFLICT") {
    return "Подключение уже изменилось или Telegram временно недоступен для привязки.";
  }
  return error.message;
}

export function formatTelegramMoment(value: string | null): string | null {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(date);
}

export function formatQuietHours(start: string, end: string): string {
  return `${start.slice(0, 5)}–${end.slice(0, 5)}`;
}

export function telegramTimeInput(value: string): string {
  return /^\d{2}:\d{2}(?::\d{2})?$/u.test(value) ? value.slice(0, 5) : "";
}

export function telegramApiTime(value: string): string | null {
  if (!/^\d{2}:\d{2}$/u.test(value)) return null;
  const hours = Number(value.slice(0, 2));
  const minutes = Number(value.slice(3, 5));
  if (hours > 23 || minutes > 59) return null;
  return `${value}:00`;
}
