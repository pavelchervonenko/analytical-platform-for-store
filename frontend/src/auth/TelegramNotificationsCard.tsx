import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import {
  BellRing,
  Check,
  ExternalLink,
  Link2,
  MessageCircleWarning,
  RefreshCw,
  Save,
  Send,
  Settings2,
  Unlink
} from "lucide-react";
import type { TelegramChannelAction, TelegramChannelResource } from "../api/contracts";
import {
  confirmTelegramChannel,
  createTelegramLink,
  getTelegramChannel,
  queryKeys,
  revokeTelegramChannel,
  updateTelegramDeliverySettings
} from "../api/queries";
import { QueryError } from "../shared/QueryState";
import { useAuth } from "./AuthProvider";
import {
  formatQuietHours,
  formatTelegramMoment,
  telegramApiTime,
  telegramMutationError,
  telegramPollInterval,
  telegramTimeInput
} from "./telegram-ui";

const TELEGRAM_TIMEZONES = [
  ["Europe/Kaliningrad", "Калининград — UTC+2"],
  ["Europe/Moscow", "Москва — UTC+3"],
  ["Europe/Samara", "Самара — UTC+4"],
  ["Asia/Yekaterinburg", "Екатеринбург — UTC+5"],
  ["Asia/Omsk", "Омск — UTC+6"],
  ["Asia/Novosibirsk", "Новосибирск — UTC+7"],
  ["Asia/Irkutsk", "Иркутск — UTC+8"],
  ["Asia/Yakutsk", "Якутск — UTC+9"],
  ["Asia/Vladivostok", "Владивосток — UTC+10"],
  ["Asia/Magadan", "Магадан — UTC+11"],
  ["Asia/Kamchatka", "Камчатка — UTC+12"]
] as const;

interface TelegramSettingsDraft {
  timezone: string;
  quietHoursEnabled: boolean;
  quietHoursStart: string;
  quietHoursEnd: string;
}

function allows(
  resource: TelegramChannelResource,
  action: TelegramChannelAction
): boolean {
  return resource.value.allowedActions.includes(action);
}

function TelegramFacts({ resource }: { resource: TelegramChannelResource }) {
  const channel = resource.value;
  if (!channel.destination || !channel.deliverySettings) return null;
  return <dl className="telegram-facts">
    <div><dt>Получатель</dt><dd>{channel.destination}</dd></div>
    <div><dt>Часовой пояс</dt><dd>{channel.deliverySettings.timezone}</dd></div>
    <div><dt>Тихие часы</dt><dd>{channel.deliverySettings.quietHoursEnabled
      ? formatQuietHours(channel.deliverySettings.quietHoursStart, channel.deliverySettings.quietHoursEnd)
      : "Выключены"}</dd></div>
  </dl>;
}

export function TelegramNotificationsCard() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [settingsDraft, setSettingsDraft] = useState<TelegramSettingsDraft | null>(null);
  const channelQuery = useQuery({
    queryKey: queryKeys.telegramChannel,
    queryFn: getTelegramChannel,
    staleTime: 0,
    refetchOnWindowFocus: true,
    refetchInterval: (query) => telegramPollInterval(query.state.data?.value.state)
  });
  const linkMutation = useMutation({
    mutationFn: createTelegramLink,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.telegramChannel });
    }
  });
  const confirmMutation = useMutation({
    mutationFn: confirmTelegramChannel,
    onSuccess: (resource) => queryClient.setQueryData(queryKeys.telegramChannel, resource),
    onError: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.telegramChannel });
    }
  });
  const revokeMutation = useMutation({
    mutationFn: revokeTelegramChannel,
    onSuccess: (resource) => queryClient.setQueryData(queryKeys.telegramChannel, resource),
    onError: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.telegramChannel });
    }
  });
  const settingsMutation = useMutation({
    mutationFn: updateTelegramDeliverySettings,
    onSuccess: (updated) => {
      queryClient.setQueryData(queryKeys.telegramChannel, updated);
      setSettingsDraft(null);
    },
    onError: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.telegramChannel });
    }
  });

  const resource = channelQuery.data;
  const channel = resource?.value;
  const mutation = linkMutation.isPending || confirmMutation.isPending
    || revokeMutation.isPending || settingsMutation.isPending;
  const error = linkMutation.error ?? confirmMutation.error
    ?? revokeMutation.error ?? settingsMutation.error;
  const issuedLink = channel?.state === "LINK_ISSUED" ? linkMutation.data : undefined;
  const startEditingSettings = () => {
    const settings = channel?.deliverySettings;
    if (!settings) return;
    setSettingsDraft({
      timezone: settings.timezone,
      quietHoursEnabled: settings.quietHoursEnabled,
      quietHoursStart: telegramTimeInput(settings.quietHoursStart),
      quietHoursEnd: telegramTimeInput(settings.quietHoursEnd)
    });
  };
  const submitSettings = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!settingsDraft || !resource?.etag) return;
    const quietHoursStart = telegramApiTime(settingsDraft.quietHoursStart);
    const quietHoursEnd = telegramApiTime(settingsDraft.quietHoursEnd);
    if (!quietHoursStart || !quietHoursEnd) return;
    settingsMutation.mutate({
      etag: resource.etag,
      input: {
        timezone: settingsDraft.timezone,
        quietHoursEnabled: settingsDraft.quietHoursEnabled,
        quietHoursStart,
        quietHoursEnd
      }
    });
  };

  const revoke = () => {
    if (!resource?.etag || !window.confirm(
      "Отключить Telegram? Все ожидающие уведомления для этой привязки будут отменены."
    )) return;
    revokeMutation.mutate(resource.etag);
  };

  return <section className="panel telegram-panel" aria-labelledby="telegram-heading">
    <div className="panel__heading telegram-panel__heading">
      <div><p className="eyebrow">Уведомления</p><h2 id="telegram-heading">Telegram</h2></div>
      {channel && <span className={`telegram-state telegram-state--${channel.state.toLowerCase()}`}>
        {channel.state === "ACTIVE" ? "Подключен"
          : channel.state === "BOT_BLOCKED" ? "Бот заблокирован"
            : channel.state === "PENDING_CONFIRMATION" ? "Нужно подтверждение"
              : channel.state === "LINK_ISSUED" ? "Ожидаем Telegram"
                : channel.state === "NOT_LINKED" ? "Не подключен"
                  : "Неизвестный статус"}
      </span>}
    </div>

    {channelQuery.isPending && <div className="telegram-loading" aria-live="polite">
      <span className="spinner" />Проверяем подключение…
    </div>}
    {channelQuery.error && <QueryError
      error={channelQuery.error}
      onRetry={() => void channelQuery.refetch()}
    />}

    {resource && channel?.state === "NOT_LINKED" && <div className="telegram-state-view">
      <span className="telegram-state-view__icon"><BellRing /></span>
      <div><h3>{user?.role === "MANAGER"
        ? "Недельные итоги и утренняя сводка — без входа в кабинет"
        : "Безопасное подключение Telegram"}</h3>
        <p>{allows(resource, "LINK")
          ? user?.role === "MANAGER"
            ? "После запуска рассылки сюда будут приходить недельные отчеты и утренние сводки по доступным магазинам. Привязка выполняется через одноразовую защищенную ссылку."
            : "ADMIN получает только сервисные сообщения подключения. Бизнес-уведомления отправляются руководителям с доступом к магазину."
          : "Подключение появится после настройки и безопасного включения Telegram-интеграции."}</p></div>
      <button className="button button--primary" type="button"
        disabled={mutation || !allows(resource, "LINK")}
        onClick={() => linkMutation.mutate()}><Link2 />Создать ссылку</button>
    </div>}

    {resource && channel?.state === "LINK_ISSUED" && <div className="telegram-state-view telegram-state-view--attention">
      <span className="telegram-state-view__icon"><Send /></span>
      <div><h3>Откройте бота и нажмите Start</h3>
        <p>Кабинет автоматически увидит Telegram. Затем вернитесь сюда и подтвердите найденного получателя.</p>
        {formatTelegramMoment(channel.linkExpiresAt) && <small>Ссылка действует до {formatTelegramMoment(channel.linkExpiresAt)}.</small>}
      </div>
      {issuedLink
        ? <a className="button button--primary" href={issuedLink.deepLink}
          target="_blank" rel="noreferrer">Открыть Telegram<ExternalLink /></a>
        : <button className="button button--ghost" type="button"
          onClick={() => void channelQuery.refetch()}><RefreshCw />Проверить</button>}
    </div>}

    {resource && channel?.state === "PENDING_CONFIRMATION" && <div className="telegram-state-view telegram-state-view--attention">
      <span className="telegram-state-view__icon"><Check /></span>
      <div><h3>Telegram найден</h3>
        <p>Проверьте получателя и подтвердите привязку. До подтверждения уведомления не отправляются.</p>
        <TelegramFacts resource={resource} /></div>
      <div className="telegram-actions">
        <button className="button button--primary" type="button"
          disabled={mutation || !resource.etag || !allows(resource, "CONFIRM")}
          onClick={() => resource.etag && confirmMutation.mutate(resource.etag)}>
          <Check />Подтвердить
        </button>
        <button className="button button--ghost" type="button"
          disabled={mutation || !resource.etag || !allows(resource, "REVOKE")}
          onClick={revoke}><Unlink />Отменить</button>
      </div>
    </div>}

    {resource && channel?.state === "ACTIVE" && <div className="telegram-state-view telegram-state-view--success">
      <span className="telegram-state-view__icon"><Send /></span>
      <div><h3>Уведомления подключены</h3>
        <p>{formatTelegramMoment(channel.confirmedAt)
          ? `Подтверждено ${formatTelegramMoment(channel.confirmedAt)}.`
          : "Привязка подтверждена."} Доступ к магазину повторно проверяется перед каждой отправкой.</p>
        <TelegramFacts resource={resource} /></div>
      <div className="telegram-actions">
        <button className="button button--ghost" type="button"
          disabled={mutation || !resource.etag || !allows(resource, "UPDATE_SETTINGS")}
          onClick={startEditingSettings}><Settings2 />Настроить</button>
        <button className="button button--ghost" type="button"
          disabled={mutation || !resource.etag || !allows(resource, "REVOKE")}
          onClick={revoke}><Unlink />Отключить</button>
      </div>
      {settingsDraft && <form className="telegram-settings-form" onSubmit={submitSettings}>
        <div className="telegram-settings-form__heading">
          <strong>Когда можно отправлять уведомления</strong>
          <span>Время применяется в выбранном часовом поясе.</span>
        </div>
        <label className="field telegram-settings-form__timezone">
          <span>Часовой пояс</span>
          <select value={settingsDraft.timezone}
            onChange={(event) => setSettingsDraft({
              ...settingsDraft, timezone: event.target.value
            })}>
            {!TELEGRAM_TIMEZONES.some(([value]) => value === settingsDraft.timezone)
              && <option value={settingsDraft.timezone}>{settingsDraft.timezone}</option>}
            {TELEGRAM_TIMEZONES.map(([value, label]) =>
              <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <label className="telegram-settings-toggle">
          <input type="checkbox" checked={settingsDraft.quietHoursEnabled}
            onChange={(event) => setSettingsDraft({
              ...settingsDraft, quietHoursEnabled: event.target.checked
            })} />
          <span><strong>Тихие часы</strong>
            <small>Не отправлять обычные уведомления в указанный период.</small></span>
        </label>
        <label className="field"><span>Начало</span>
          <input type="time" step="60" required value={settingsDraft.quietHoursStart}
            disabled={!settingsDraft.quietHoursEnabled}
            onChange={(event) => setSettingsDraft({
              ...settingsDraft, quietHoursStart: event.target.value
            })} />
        </label>
        <label className="field"><span>Окончание</span>
          <input type="time" step="60" required value={settingsDraft.quietHoursEnd}
            disabled={!settingsDraft.quietHoursEnabled}
            onChange={(event) => setSettingsDraft({
              ...settingsDraft, quietHoursEnd: event.target.value
            })} />
        </label>
        {settingsDraft.quietHoursEnabled
          && settingsDraft.quietHoursStart === settingsDraft.quietHoursEnd
          && <p className="form-error">Начало и окончание должны отличаться.</p>}
        <div className="telegram-settings-form__actions">
          <button className="button button--ghost" type="button"
            disabled={settingsMutation.isPending}
            onClick={() => setSettingsDraft(null)}>Отмена</button>
          <button className="button button--primary" type="submit"
            disabled={mutation || !resource.etag
              || (settingsDraft.quietHoursEnabled
                && settingsDraft.quietHoursStart === settingsDraft.quietHoursEnd)}>
            <Save />Сохранить
          </button>
        </div>
      </form>}
    </div>}

    {resource && channel?.state === "BOT_BLOCKED" && <div className="telegram-state-view telegram-state-view--danger">
      <span className="telegram-state-view__icon"><MessageCircleWarning /></span>
      <div><h3>Бот заблокирован в Telegram</h3>
        <p>Откройте бота, разблокируйте его и нажмите Start. Кабинет автоматически восстановит ранее подтвержденную привязку.</p>
        <TelegramFacts resource={resource} /></div>
      <div className="telegram-actions">
        {channel.publicBotUrl && <a className="button button--primary"
          href={channel.publicBotUrl} target="_blank" rel="noreferrer">
          Открыть бота<ExternalLink />
        </a>}
        <button className="button button--ghost" type="button"
          disabled={mutation || !resource.etag || !allows(resource, "REVOKE")}
          onClick={revoke}><Unlink />Отключить</button>
      </div>
    </div>}

    {resource && channel?.state === "UNKNOWN" && <div className="telegram-state-view">
      <span className="telegram-state-view__icon"><RefreshCw /></span>
      <div><h3>Состояние подключения обновилось</h3>
        <p>Эта версия кабинета пока не распознает новый статус. Обновите страницу; опасные действия скрыты.</p></div>
      <button className="button button--ghost" type="button"
        onClick={() => void channelQuery.refetch()}><RefreshCw />Обновить</button>
    </div>}

    {error && <p className="form-error telegram-panel__error" role="alert">
      {telegramMutationError(error)}
    </p>}
  </section>;
}
