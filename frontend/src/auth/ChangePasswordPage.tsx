import { useState, type FormEvent } from "react";
import { CheckCircle2 } from "lucide-react";
import { isApiClientError } from "../api/client";
import { useAuth } from "./AuthProvider";

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
    if (newPassword !== confirmation) {
      setError("Новый пароль и подтверждение не совпадают.");
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

          <ul className="password-rules" aria-label="Требования к паролю">
            <li><CheckCircle2 size={16} />От 12 до 128 символов</li>
            <li><CheckCircle2 size={16} />Не используйте распространенный пароль</li>
          </ul>

          {error && <div className="form-alert" role="alert">{error}</div>}

          <label className="field"><span>Временный пароль</span><input type="password" autoComplete="current-password" maxLength={128} value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} required /></label>
          <label className="field"><span>Новый пароль</span><input type="password" autoComplete="new-password" minLength={12} maxLength={128} value={newPassword} onChange={(event) => setNewPassword(event.target.value)} required /></label>
          <label className="field"><span>Повторите новый пароль</span><input type="password" autoComplete="new-password" minLength={12} maxLength={128} value={confirmation} onChange={(event) => setConfirmation(event.target.value)} required /></label>

          <button className="button button--primary button--wide" type="submit" disabled={submitting}>{submitting ? "Сохраняем…" : "Сменить пароль"}</button>
          <button className="button button--ghost button--wide" type="button" onClick={() => void logout()}>Выйти</button>
        </form>
      </section>
    </main>
  );
}
