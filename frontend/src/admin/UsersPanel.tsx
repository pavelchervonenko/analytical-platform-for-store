import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Pencil, Plus, ShieldCheck, Store, UserRoundCheck, X } from "lucide-react";
import { useState, type FormEvent, type ReactNode } from "react";
import { isApiClientError } from "../api/client";
import { queryKeys } from "../api/queries";
import { useAuth } from "../auth/AuthProvider";
import { QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { adminKeys, createAdminUser, getAdminUsers, replaceUserStoreAccess, resetAdminUserPassword, updateAdminUser, type AdminUser } from "./api";

type EditorMode = "create" | "edit" | "access" | "password";

function message(error: unknown): string {
  return isApiClientError(error) ? error.message : "Не удалось выполнить действие.";
}

function roleLabel(role: AdminUser["role"]): string {
  if (role === "ADMIN") return "Администратор";
  if (role === "MANAGER") return "Руководитель";
  return "Неизвестная роль";
}

function Modal({ title, children, onClose }: { title: string; children: ReactNode; onClose: () => void }) {
  return <div className="modal-backdrop" role="presentation"><section className="admin-modal" role="dialog" aria-modal="true" aria-labelledby="admin-modal-title"><header><h2 id="admin-modal-title">{title}</h2><button className="icon-button" type="button" onClick={onClose} aria-label="Закрыть"><X /></button></header>{children}</section></div>;
}

function StoreChecks({ selected }: { selected: string[] }) {
  const { stores } = useWorkspace();
  return <fieldset className="admin-store-checks"><legend>Доступ к магазинам</legend>{stores.map((store) => <label key={store.id}><input type="checkbox" name="storeIds" value={store.id} defaultChecked={selected.includes(store.id)} /><span><strong>{store.name}</strong><small>{store.address ?? "Адрес не указан"}</small></span></label>)}</fieldset>;
}

export function UsersPanel() {
  const { user: currentUser } = useAuth();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const usersQuery = useQuery({
    queryKey: [...adminKeys.users, page],
    queryFn: () => getAdminUsers(page)
  });
  const [editor, setEditor] = useState<{ mode: EditorMode; user?: AdminUser } | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const mutation = useMutation({
    mutationFn: async ({ mode, target, form }: { mode: EditorMode; target?: AdminUser; form: FormData }) => {
      if (mode === "create") return createAdminUser({ email: String(form.get("email")), temporaryPassword: String(form.get("temporaryPassword")), displayName: String(form.get("displayName")), role: String(form.get("role")) as "ADMIN" | "MANAGER", storeIds: form.getAll("storeIds").map(String) });
      if (!target) throw new Error("User is required");
      if (mode === "edit") return updateAdminUser(target.id, { displayName: String(form.get("displayName")), role: String(form.get("role")) as "ADMIN" | "MANAGER", active: form.get("active") === "on" });
      if (mode === "access") return replaceUserStoreAccess(target.id, form.getAll("storeIds").map(String));
      return resetAdminUserPassword(target.id, String(form.get("temporaryPassword")));
    },
    onSuccess: async (_, variables) => {
      await queryClient.invalidateQueries({ queryKey: adminKeys.users });
      await queryClient.invalidateQueries({ queryKey: queryKeys.stores });
      if (variables.target?.id === currentUser?.id) await queryClient.invalidateQueries({ queryKey: queryKeys.session });
      setEditor(null); setSubmitError(null);
    },
    onError: (error) => setSubmitError(message(error))
  });

  const open = (mode: EditorMode, user?: AdminUser) => { setSubmitError(null); setEditor({ mode, user }); };
  const submit = (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); setSubmitError(null); mutation.mutate({ mode: editor!.mode, target: editor!.user, form: new FormData(event.currentTarget) }); };

  if (usersQuery.isPending) return <div className="panel-loader"><span className="spinner" />Загружаем пользователей…</div>;
  if (usersQuery.isError) return <QueryError error={usersQuery.error} onRetry={() => void usersQuery.refetch()} />;

  return <>
    <section className="admin-section-heading"><div><p className="eyebrow">Учетные записи и полномочия</p><h2>Пользователи</h2></div><button className="button button--primary" type="button" onClick={() => open("create")}><Plus size={16} />Создать пользователя</button></section>
    <section className="panel admin-user-list">
      <div className="admin-table-head"><span>Пользователь</span><span>Роль и статус</span><span>Магазины</span><span>Последний вход</span><span /></div>
      {usersQuery.data.items.map((user) => <article key={user.id}>
        <div className="admin-user-identity"><span>{user.displayName.slice(0, 1).toUpperCase()}</span><div><strong>{user.displayName}</strong><small>{user.email}{user.id === currentUser?.id ? " · вы" : ""}</small></div></div>
        <div><span className={`status status--${user.active ? "success" : "warning"}`}>{user.active ? "Активен" : "Отключен"}</span><small>{roleLabel(user.role)}{user.passwordChangeRequired ? " · временный пароль" : ""}</small></div>
        <div><strong>{user.allStores ? "Все магазины" : `${user.storeIds.length} назначено`}</strong><small>{user.allStores ? "Полный административный доступ" : "Явные назначения"}</small></div>
        <div><strong>{user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleDateString("ru-RU") : "Не входил"}</strong><small>Версия доступа {user.version}</small></div>
        <div className="admin-row-actions"><button type="button" disabled={user.role === "UNKNOWN"} onClick={() => open("edit", user)} title={user.role === "UNKNOWN" ? "Редактирование недоступно для неизвестной роли" : "Изменить"}><Pencil /></button>{user.role === "MANAGER" && <button type="button" onClick={() => open("access", user)} title="Доступ к магазинам"><Store /></button>}<button type="button" disabled={user.id === currentUser?.id} onClick={() => open("password", user)} title="Сбросить пароль"><KeyRound /></button></div>
      </article>)}
    </section>
    {usersQuery.data.totalPages > 1 && <nav className="admin-pagination" aria-label="Страницы пользователей"><button className="button button--ghost" type="button" disabled={!usersQuery.data.hasPrevious} onClick={() => setPage((value) => Math.max(0, value - 1))}>Назад</button><span>{page + 1} из {usersQuery.data.totalPages}</span><button className="button button--ghost" type="button" disabled={!usersQuery.data.hasNext} onClick={() => setPage((value) => value + 1)}>Далее</button></nav>}

    {editor && <Modal title={editor.mode === "create" ? "Новый пользователь" : editor.mode === "edit" ? "Профиль и роль" : editor.mode === "access" ? "Доступ к магазинам" : "Новый временный пароль"} onClose={() => !mutation.isPending && setEditor(null)}>
      <form className="admin-form" onSubmit={submit}>
        {editor.mode === "create" && <><label className="field"><span>Email</span><input name="email" type="email" required maxLength={254} autoComplete="off" /></label><label className="field"><span>Имя</span><input name="displayName" required maxLength={200} autoComplete="off" /></label><label className="field"><span>Роль</span><select name="role" defaultValue="MANAGER"><option value="MANAGER">Руководитель</option><option value="ADMIN">Администратор</option></select></label><StoreChecks selected={[]} /><label className="field"><span>Временный пароль</span><input name="temporaryPassword" type="password" required minLength={12} maxLength={128} autoComplete="new-password" /><small>12–128 символов. Пароль передается только серверу и не сохраняется во frontend.</small></label></>}
        {editor.mode === "edit" && editor.user && <><label className="field"><span>Имя</span><input name="displayName" defaultValue={editor.user.displayName} required maxLength={200} /></label><label className="field"><span>Роль</span><select name="role" defaultValue={editor.user.role} disabled={editor.user.id === currentUser?.id}><option value="MANAGER">Руководитель</option><option value="ADMIN">Администратор</option></select>{editor.user.id === currentUser?.id && <input type="hidden" name="role" value={editor.user.role} />}</label><label className="admin-switch"><input type="checkbox" name="active" defaultChecked={editor.user.active} disabled={editor.user.id === currentUser?.id} /><span><strong>Активная учетная запись</strong><small>Отключение завершит действующие сессии.</small></span></label>{editor.user.id === currentUser?.id && <input type="hidden" name="active" value="on" />}</>}
        {editor.mode === "access" && editor.user && <><p className="admin-form-note"><UserRoundCheck />{editor.user.displayName}</p><StoreChecks selected={editor.user.storeIds} /></>}
        {editor.mode === "password" && editor.user && <><p className="admin-form-note admin-form-note--warning"><ShieldCheck />Все действующие сессии пользователя будут завершены.</p><label className="field"><span>Новый временный пароль</span><input name="temporaryPassword" type="password" required minLength={12} maxLength={128} autoComplete="new-password" /></label></>}
        {submitError && <p className="form-error" role="alert">{submitError}</p>}
        <footer><button className="button button--ghost" type="button" onClick={() => setEditor(null)} disabled={mutation.isPending}>Отмена</button><button className="button button--primary" type="submit" disabled={mutation.isPending}>{mutation.isPending ? "Сохраняем…" : editor.mode === "password" ? "Сбросить пароль" : "Сохранить"}</button></footer>
      </form>
    </Modal>}
  </>;
}
