import { useState, type FormEvent } from "react";
import { isApiClientError } from "../api/client";
import { useAuth } from "./AuthProvider";

export function validateNewPassword(currentPassword: string, newPassword: string, confirmation: string): string | null {
  if (newPassword !== confirmation) return "Новый пароль и подтверждение не совпадают.";
  if (newPassword === currentPassword) return "Новый пароль должен отличаться от временного.";
  const normalized = newPassword.normalize("NFC");
  const length = Array.from(normalized).length;
  if (length < 12 || length > 128) return "Пароль должен содержать от 12 до 128 символов.";
  if (new TextEncoder().encode(normalized).length > 72) return "Пароль слишком длинный: используйте не более 72 байт UTF-8.";
  if (/\p{Cc}/u.test(normalized)) return "Пароль не должен содержать управляющие символы.";
  return null;
}

export function ChangePasswordPage() {
  const { changePassword, logout } = useAuth();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const validationError = validateNewPassword(currentPassword, newPassword, confirmation);
    if (validationError) {
      setError(validationError);
      return;
    }
    setSubmitting(true);
    try {
      await changePassword({ currentPassword, newPassword });
    } catch (requestError) {
      setError(isApiClientError(requestError) ? requestError.message : "Не удалось изменить пароль.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="auth-page auth-page--compact">
      <section className="auth-panel">
        <form className="auth-card auth-card--password" onSubmit={handleSubmit}>
          <div className="auth-brand"><span className="brand-mark">S</span><span>Store Analytics</span></div>
          <div>
            <p className="eyebrow">Безопасность учетной записи</p>
            <h1>Задайте постоянный пароль</h1>
            <p className="muted">После смены пароля текущая сессия завершится. Затем войдите снова.</p>
          </div>


          {error && <div className="form-alert" role="alert">{error}</div>}

          <label className="field"><span>Временный пароль</span><input type="password" autoComplete="current-password" maxLength={128} value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} required /></label>
          <label className="field"><span>Новый пароль</span><input type="password" autoComplete="new-password" minLength={12} maxLength={128} value={newPassword} onChange={(event) => setNewPassword(event.target.value)} required /><small>12–128 символов, не более 72 байт; новый и нераспространенный</small></label>
          <label className="field"><span>Повторите новый пароль</span><input type="password" autoComplete="new-password" minLength={12} maxLength={128} value={confirmation} onChange={(event) => setConfirmation(event.target.value)} required /></label>

          <button className="button button--primary button--wide" type="submit" disabled={submitting}>{submitting ? "Сохраняем…" : "Сменить пароль"}</button>
          <button className="button button--ghost button--wide" type="button" onClick={() => void logout()}>Выйти</button>
        </form>
      </section>
    </main>
  );
}
