import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Clock3, KeyRound, LogOut, ShieldCheck, UserRound } from "lucide-react";
import { isApiClientError } from "../api/client";
import {
  getActiveSessions,
  queryKeys,
  revokeActiveSession,
  revokeOtherSessions
} from "../api/queries";
import { QueryError } from "../shared/QueryState";
import { useAuth } from "./AuthProvider";
import { formatSessionActivity, orderActiveSessions } from "./session-ui";
import "./profile.css";

function mutationError(error: unknown): string {
  if (!isApiClientError(error)) return "Не удалось завершить сеанс. Попробуйте еще раз.";
  if (error.code === "CURRENT_SESSION_REQUIRES_LOGOUT") {
    return "Текущий сеанс завершается только обычным выходом из кабинета.";
  }
  return error.correlationId
    ? `${error.message} Код обращения: ${error.correlationId}`
    : error.message;
}

export function ProfilePage() {
  const { user, logout } = useAuth();
  const queryClient = useQueryClient();
  const sessionsQuery = useQuery({
    queryKey: queryKeys.activeSessions,
    queryFn: getActiveSessions,
    staleTime: 15_000
  });
  const revokeMutation = useMutation({
    mutationFn: revokeActiveSession,
    onSettled: async () => queryClient.invalidateQueries({ queryKey: queryKeys.activeSessions })
  });
  const revokeOthersMutation = useMutation({
    mutationFn: revokeOtherSessions,
    onSettled: async () => queryClient.invalidateQueries({ queryKey: queryKeys.activeSessions })
  });
  const sessions = orderActiveSessions(sessionsQuery.data ?? []);
  const otherSessionCount = sessions.filter((session) => !session.current).length;
  const pending = revokeMutation.isPending || revokeOthersMutation.isPending;
  const error = revokeMutation.error ?? revokeOthersMutation.error;

  return <div className="profile-page">
    <header className="page-heading"><div><p className="eyebrow">Личный контур</p><h1>Профиль и безопасность</h1><p>Данные учетной записи и управление входами в кабинет.</p></div></header>

    <div className="profile-grid">
      <aside className="panel profile-card">
        <span className="profile-card__avatar">{user?.displayName.slice(0, 1).toUpperCase()}</span>
        <p className="eyebrow">Учетная запись</p>
        <h2>{user?.displayName}</h2>
        <p>{user?.email}</p>
        <dl>
          <div><dt>Роль</dt><dd>{user?.role === "ADMIN" ? "Администратор" : user?.role === "MANAGER" ? "Руководитель" : "Неизвестная роль"}</dd></div>
          <div><dt>Доступ</dt><dd>{user?.allStores ? "Все магазины" : `${user?.storeIds.length ?? 0} магазин(а)`}</dd></div>
        </dl>
        <div className="profile-security-note"><ShieldCheck /><span>Сессия хранится в защищенной HttpOnly cookie. Токены и пароль не сохраняются в браузере.</span></div>
      </aside>

      <section className="panel sessions-panel">
        <div className="panel__heading"><div><p className="eyebrow">Контроль доступа</p><h2>Активные сеансы</h2></div><span>Не более трех одновременно</span></div>
        <p className="sessions-panel__intro">Завершите незнакомый или больше не используемый вход. Из соображений приватности приложение не хранит IP-адрес и название устройства.</p>

        {sessionsQuery.isPending && <div className="sessions-loading" aria-live="polite"><span className="spinner" />Проверяем активные сеансы…</div>}
        {sessionsQuery.error && <QueryError error={sessionsQuery.error} onRetry={() => void sessionsQuery.refetch()} />}
        {sessionsQuery.data && <div className="session-list">
          {sessions.map((session) => <article className={`session-row ${session.current ? "session-row--current" : ""}`} key={session.sessionReference}>
            <span className="session-row__icon"><KeyRound /></span>
            <div className="session-row__copy"><div><strong>{session.current ? "Этот браузер" : "Другой активный сеанс"}</strong>{session.current && <span className="status status--success">Текущий</span>}</div><small><Clock3 />Активность: {formatSessionActivity(session.lastSeenAt)}</small></div>
            {session.current
              ? <button className="button button--ghost" type="button" disabled={pending} onClick={() => void logout()}><LogOut />Выйти</button>
              : <button className="session-revoke" type="button" disabled={pending} onClick={() => { if (window.confirm("Завершить этот сеанс? На другом устройстве потребуется войти заново.")) revokeMutation.mutate(session.sessionReference); }}><KeyRound />Завершить</button>}
          </article>)}
        </div>}

        {error && <p className="form-error" role="alert">{mutationError(error)}</p>}
        <footer className="sessions-panel__footer"><div><UserRound /><span><strong>{sessions.length} активных</strong><small>{otherSessionCount === 0 ? "Других входов нет" : `Других входов: ${otherSessionCount}`}</small></span></div><button className="button button--ghost" type="button" disabled={pending || otherSessionCount === 0} onClick={() => { if (window.confirm("Завершить все остальные сеансы? На других устройствах потребуется войти заново.")) revokeOthersMutation.mutate(); }}>Завершить остальные</button></footer>
      </section>
    </div>
  </div>;
}
