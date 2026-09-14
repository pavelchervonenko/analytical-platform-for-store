import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Pencil, Plus, ShieldCheck, X } from "lucide-react";
import { useState, type FormEvent, type ReactNode } from "react";
import { isApiClientError } from "../api/client";
import { userFeatureValues, type UserFeature } from "../api/contracts";
import { queryKeys } from "../api/queries";
import { useAuth } from "../auth/AuthProvider";
import { QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import {
  adminKeys,
  createAdminUser,
  getAdminUsers,
  resetAdminUserPassword,
  updateAdminUser,
  type AdminUser
} from "./api";

type EditorMode = "create" | "edit" | "password";
type EditableRole = "ADMIN" | "MANAGER";

const featureOptions: ReadonlyArray<{
  value: UserFeature;
  label: string;
  description: string;
}> = [
  { value: "PLAN", label: "План", description: "Просмотр и изменение плана магазина" },
  { value: "SHIFTS", label: "Смены", description: "Просмотр и изменение графика смен" },
  { value: "PAYROLL", label: "Зарплата", description: "Расчет, проверка и утверждение зарплаты" }
];

function message(error: unknown): string {
  return isApiClientError(error) ? error.message : "Не удалось выполнить действие.";
}

function roleLabel(role: AdminUser["role"]): string {
  if (role === "ADMIN") return "Администратор";
  if (role === "MANAGER") return "Руководитель";
  return "Неизвестная роль";
}

function featureSummary(user: AdminUser): string {
  if (user.role === "ADMIN") return "Все разделы";
  if (user.features.includes("UNKNOWN")) return "Доступ новой версии";
  const labels = featureOptions
    .filter((option) => user.features.includes(option.value))
    .map((option) => option.label);
  return labels.length > 0 ? labels.join(", ") : "Только аналитика";
}

function hasUnsupportedAccess(user: AdminUser): boolean {
  return user.role === "UNKNOWN" || user.features.includes("UNKNOWN");
}

function storeSummary(user: AdminUser): string {
  if (user.allStores) return "Все магазины";
  return user.storeIds.length === 0 ? "Нет магазинов" : `${user.storeIds.length} назначено`;
}

function isUserFeature(value: string): value is UserFeature {
  return userFeatureValues.includes(value as UserFeature);
}

function Modal({
  title,
  children,
  onClose
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
}) {
  return (
    <div className="modal-backdrop" role="presentation">
      <section
        className="admin-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="admin-modal-title"
      >
        <header>
          <h2 id="admin-modal-title">{title}</h2>
          <button className="icon-button" type="button" onClick={onClose} aria-label="Закрыть">
            <X />
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}

function StoreChecks({ selected }: { selected: string[] }) {
  const { stores } = useWorkspace();
  return (
    <fieldset className="admin-access-checks">
      <legend>Магазины</legend>
      <p>Руководитель увидит данные только выбранных магазинов.</p>
      {stores.length === 0 && <small>Сначала добавьте магазин.</small>}
      {stores.map((store) => (
        <label key={store.id}>
          <input
            type="checkbox"
            name="storeIds"
            value={store.id}
            defaultChecked={selected.includes(store.id)}
          />
          <span>
            <strong>{store.name}</strong>
            <small>{store.address ?? "Адрес не указан"}</small>
          </span>
        </label>
      ))}
    </fieldset>
  );
}

function FeatureChecks({ selected }: { selected: AdminUser["features"] }) {
  return (
    <fieldset className="admin-access-checks">
      <legend>Доступные действия</legend>
      <p>Невыбранные разделы исчезнут из меню и не откроются по прямой ссылке.</p>
      {featureOptions.map((feature) => (
        <label key={feature.value}>
          <input
            type="checkbox"
            name="features"
            value={feature.value}
            defaultChecked={selected.includes(feature.value)}
          />
          <span>
            <strong>{feature.label}</strong>
            <small>{feature.description}</small>
          </span>
        </label>
      ))}
      <small>Если ничего не выбрать, останутся аналитика, сотрудники и отчеты.</small>
    </fieldset>
  );
}

export function UsersPanel() {
  const { user: currentUser } = useAuth();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const usersQuery = useQuery({
    queryKey: [...adminKeys.users, page],
    queryFn: () => getAdminUsers(page)
  });
  const [editor, setEditor] = useState<{
    mode: EditorMode;
    user?: AdminUser;
  } | null>(null);
  const [editorRole, setEditorRole] = useState<EditableRole>("MANAGER");
  const [submitError, setSubmitError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: async ({
      mode,
      target,
      form
    }: {
      mode: EditorMode;
      target?: AdminUser;
      form: FormData;
    }) => {
      if (mode === "password") {
        if (!target) throw new Error("User is required");
        return resetAdminUserPassword(target.id, String(form.get("temporaryPassword")));
      }
      const role = String(form.get("role")) as EditableRole;
      const storeIds = role === "ADMIN" ? [] : form.getAll("storeIds").map(String);
      const features = role === "ADMIN"
        ? []
        : form.getAll("features").map(String).filter(isUserFeature);
      if (mode === "create") {
        return createAdminUser({
          email: String(form.get("email")),
          temporaryPassword: String(form.get("temporaryPassword")),
          displayName: String(form.get("displayName")),
          role,
          storeIds,
          features
        });
      }
      if (!target) throw new Error("User is required");
      return updateAdminUser(target.id, {
        displayName: String(form.get("displayName")),
        role,
        active: form.get("active") === "on",
        storeIds,
        features,
        version: target.version
      });
    },
    onSuccess: async (_, variables) => {
      await queryClient.invalidateQueries({ queryKey: adminKeys.users });
      await queryClient.invalidateQueries({ queryKey: queryKeys.stores });
      if (variables.target?.id === currentUser?.id) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.session });
      }
      setEditor(null);
      setSubmitError(null);
    },
    onError: (error, variables) => {
      setSubmitError(message(error));
      if (!isApiClientError(error) || error.code !== "CONCURRENT_MODIFICATION") return;
      void usersQuery.refetch().then(({ data }) => {
        const refreshed = data?.items.find((user) => user.id === variables.target?.id);
        if (!refreshed) return;
        setEditor({ mode: variables.mode, user: refreshed });
        setEditorRole(refreshed.role === "ADMIN" ? "ADMIN" : "MANAGER");
        setSubmitError(
          "Данные пользователя изменились. Форма обновлена — проверьте ее и повторите действие."
        );
      });
    }
  });

  const open = (mode: EditorMode, user?: AdminUser) => {
    setSubmitError(null);
    setEditorRole(user?.role === "ADMIN" ? "ADMIN" : "MANAGER");
    setEditor({ mode, user });
  };
  const close = () => {
    if (!mutation.isPending) setEditor(null);
  };
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSubmitError(null);
    mutation.mutate({
      mode: editor!.mode,
      target: editor!.user,
      form: new FormData(event.currentTarget)
    });
  };

  if (usersQuery.isPending) {
    return <div className="panel-loader"><span className="spinner" />Загружаем пользователей…</div>;
  }
  if (usersQuery.isError) {
    return <QueryError error={usersQuery.error} onRetry={() => void usersQuery.refetch()} />;
  }

  return (
    <>
      <section className="admin-section-heading">
        <div>
          <p className="eyebrow">Учетные записи и полномочия</p>
          <h2>Пользователи</h2>
        </div>
        <button className="button button--primary" type="button" onClick={() => open("create")}>
          <Plus size={16} />Создать пользователя
        </button>
      </section>
      <section className="panel admin-user-list">
        <div className="admin-table-head">
          <span>Пользователь</span><span>Роль и статус</span><span>Доступ</span>
          <span>Последний вход</span><span />
        </div>
        {usersQuery.data.items.map((user) => (
          <article key={user.id}>
            <div className="admin-user-identity">
              <span>{user.displayName.slice(0, 1).toUpperCase()}</span>
              <div>
                <strong>{user.displayName}</strong>
                <small>{user.email}{user.id === currentUser?.id ? ", вы" : ""}</small>
              </div>
            </div>
            <div>
              <span className={`status status--${user.active ? "success" : "warning"}`}>
                {user.active ? "Активен" : "Отключен"}
              </span>
              <small>{roleLabel(user.role)}{user.passwordChangeRequired ? ", временный пароль" : ""}</small>
            </div>
            <div>
              <strong>{featureSummary(user)}</strong>
              <small>{storeSummary(user)}</small>
            </div>
            <div>
              <strong>
                {user.lastLoginAt
                  ? new Date(user.lastLoginAt).toLocaleDateString("ru-RU")
                  : "Не входил"}
              </strong>
            </div>
            <div className="admin-row-actions">
              <button
                type="button"
                disabled={hasUnsupportedAccess(user)}
                onClick={() => open("edit", user)}
                title={hasUnsupportedAccess(user)
                  ? "Редактирование недоступно: сервер вернул доступ новой версии"
                  : "Изменить пользователя и доступ"}
              >
                <Pencil />
              </button>
              <button
                type="button"
                disabled={user.id === currentUser?.id}
                onClick={() => open("password", user)}
                title="Сбросить пароль"
              >
                <KeyRound />
              </button>
            </div>
          </article>
        ))}
      </section>
      {usersQuery.data.totalPages > 1 && (
        <nav className="admin-pagination" aria-label="Страницы пользователей">
          <button
            className="button button--ghost"
            type="button"
            disabled={!usersQuery.data.hasPrevious}
            onClick={() => setPage((value) => Math.max(0, value - 1))}
          >
            Назад
          </button>
          <span>{page + 1} из {usersQuery.data.totalPages}</span>
          <button
            className="button button--ghost"
            type="button"
            disabled={!usersQuery.data.hasNext}
            onClick={() => setPage((value) => value + 1)}
          >
            Далее
          </button>
        </nav>
      )}

      {editor && (
        <Modal
          title={editor.mode === "create"
            ? "Новый пользователь"
            : editor.mode === "edit"
              ? "Пользователь и доступ"
              : "Новый временный пароль"}
          onClose={close}
        >
          <form
            className="admin-form"
            key={`${editor.mode}-${editor.user?.id ?? "new"}-${editor.user?.version ?? 0}`}
            onSubmit={submit}
          >
            {editor.mode === "create" && (
              <>
                <label className="field">
                  <span>Email</span>
                  <input name="email" type="email" required maxLength={254} autoComplete="off" />
                </label>
                <label className="field">
                  <span>Имя</span>
                  <input name="displayName" required maxLength={200} autoComplete="off" />
                </label>
              </>
            )}
            {editor.mode === "edit" && editor.user && (
              <label className="field">
                <span>Имя</span>
                <input
                  name="displayName"
                  defaultValue={editor.user.displayName}
                  required
                  maxLength={200}
                />
              </label>
            )}
            {editor.mode !== "password" && (
              <>
                <label className="field">
                  <span>Роль</span>
                  <select
                    name="role"
                    value={editorRole}
                    disabled={editor.user?.id === currentUser?.id}
                    onChange={(event) => setEditorRole(event.target.value as EditableRole)}
                  >
                    <option value="MANAGER">Руководитель</option>
                    <option value="ADMIN">Администратор</option>
                  </select>
                  {editor.user?.id === currentUser?.id && (
                    <input type="hidden" name="role" value={editorRole} />
                  )}
                </label>
                {editorRole === "MANAGER" ? (
                  <>
                    <StoreChecks selected={editor.user?.storeIds ?? []} />
                    <FeatureChecks selected={editor.user?.features ?? []} />
                  </>
                ) : (
                  <p className="admin-form-note">
                    <ShieldCheck />Администратор автоматически получает все магазины и разделы.
                  </p>
                )}
              </>
            )}
            {editor.mode === "edit" && editor.user && (
              <>
                <label className="admin-switch">
                  <input
                    type="checkbox"
                    name="active"
                    defaultChecked={editor.user.active}
                    disabled={editor.user.id === currentUser?.id}
                  />
                  <span>
                    <strong>Активная учетная запись</strong>
                    <small>Отключение завершит действующие сессии.</small>
                  </span>
                </label>
                {editor.user.id === currentUser?.id && (
                  <input type="hidden" name="active" value="on" />
                )}
              </>
            )}
            {editor.mode === "create" && (
              <label className="field">
                <span>Временный пароль</span>
                <input
                  name="temporaryPassword"
                  type="password"
                  required
                  minLength={12}
                  maxLength={128}
                  autoComplete="new-password"
                />
                <small>от 12 символов</small>
              </label>
            )}
            {editor.mode === "password" && editor.user && (
              <>
                <p className="admin-form-note admin-form-note--warning">
                  <ShieldCheck />Все действующие сессии пользователя будут завершены.
                </p>
                <label className="field">
                  <span>Новый временный пароль</span>
                  <input
                    name="temporaryPassword"
                    type="password"
                    required
                    minLength={12}
                    maxLength={128}
                    autoComplete="new-password"
                  />
                </label>
              </>
            )}
            {submitError && <p className="form-error" role="alert">{submitError}</p>}
            <footer>
              <button
                className="button button--ghost"
                type="button"
                onClick={close}
                disabled={mutation.isPending}
              >
                Отмена
              </button>
              <button className="button button--primary" type="submit" disabled={mutation.isPending}>
                {mutation.isPending
                  ? "Сохраняем…"
                  : editor.mode === "password" ? "Сбросить пароль" : "Сохранить"}
              </button>
            </footer>
          </form>
        </Modal>
      )}
    </>
  );
}
