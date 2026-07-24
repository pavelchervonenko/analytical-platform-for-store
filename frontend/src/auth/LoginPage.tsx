import { useEffect, useState, type FormEvent } from "react";
import { Eye, EyeOff, LockKeyhole } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { isApiClientError } from "../api/client";
import { useAuth } from "./AuthProvider";

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retryAfter, setRetryAfter] = useState<number | null>(null);

  useEffect(() => {
    if (retryAfter === null) return undefined;
    const timer = window.setTimeout(() => {
      setRetryAfter((seconds) => seconds !== null && seconds > 1 ? seconds - 1 : null);
    }, 1_000);
    return () => window.clearTimeout(timer);
  }, [retryAfter]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    setRetryAfter(null);
    try {
      const user = await login({ email: email.trim(), password });
      navigate(user.passwordChangeRequired ? "/change-password" : "/overview", { replace: true });
    } catch (requestError) {
      if (isApiClientError(requestError)) {
        setError(requestError.message);
        if (requestError.code === "LOGIN_THROTTLED") {
          setRetryAfter(requestError.retryAfterSeconds ?? 60);
        }
      } else {
        setError("Не удалось войти. Проверьте соединение и повторите попытку.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-intro" aria-label="Store Analytics">
        <div className="auth-brand"><span className="brand-mark">S</span><span>Store Analytics</span></div>
        <div className="auth-intro__copy">
          <p className="eyebrow">Закрытый кабинет руководителя</p>
          <h1>Показатели магазина.<br />Без информационного шума.</h1>
          <p>Продажи, сотрудники, планы и зарплата в одном защищенном рабочем пространстве.</p>
        </div>
        <div className="auth-intro__security"><LockKeyhole size={18} /><span>Защищенная серверная сессия</span></div>
      </section>

      <section className="auth-panel">
        <form className="auth-card" onSubmit={handleSubmit} noValidate>
          <div>
            <p className="eyebrow">Добро пожаловать</p>
            <h2>Вход в кабинет</h2>
            <p className="muted">Используйте учетную запись, созданную администратором.</p>
          </div>

          {error && <div className="form-alert" role="alert">{error}</div>}

          <label className="field">
            <span>Email</span>
            <input
              type="email"
              name="email"
              autoComplete="username"
              inputMode="email"
              maxLength={254}
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
              autoFocus
            />
          </label>

          <label className="field">
            <span>Пароль</span>
            <span className="password-field">
              <input
                type={showPassword ? "text" : "password"}
                name="password"
                autoComplete="current-password"
                maxLength={128}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                required
              />
              <button type="button" onClick={() => setShowPassword((visible) => !visible)} aria-label={showPassword ? "Скрыть пароль" : "Показать пароль"}>
                {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
              </button>
            </span>
          </label>

          <button className="button button--primary button--wide" type="submit" disabled={submitting || retryAfter !== null}>
            {submitting ? "Входим…" : retryAfter !== null ? `Повторите через ${retryAfter} сек.` : "Войти"}
          </button>

          <p className="auth-card__note">Нет доступа? Обратитесь к администратору системы.</p>
        </form>
      </section>
    </main>
  );
}
