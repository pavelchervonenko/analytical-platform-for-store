import type { ReactNode } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "./AuthProvider";

function BootScreen() {
  return (
    <main className="boot-screen" aria-live="polite" aria-busy="true">
      <div className="brand-mark" aria-hidden="true">S</div>
      <span className="spinner" aria-hidden="true" />
      <p>Открываем рабочее пространство…</p>
    </main>
  );
}

export function SessionGate() {
  const { status, user, bootstrapError, retryBootstrap } = useAuth();
  const location = useLocation();

  if (status === "booting") return <BootScreen />;
  if (status === "error") {
    return (
      <main className="fatal-state">
        <div className="fatal-state__card">
          <div className="brand-mark" aria-hidden="true">S</div>
          <h1>Сервер недоступен</h1>
          <p>{bootstrapError}</p>
          <button className="button button--primary" type="button" onClick={retryBootstrap}>Повторить</button>
        </div>
      </main>
    );
  }
  if (!user) return <Navigate to="/login" state={{ from: location }} replace />;
  if (user.passwordChangeRequired) return <Navigate to="/change-password" replace />;
  return <Outlet />;
}

export function AnonymousGate() {
  const { status, user } = useAuth();
  if (status === "booting") return <BootScreen />;
  if (user?.passwordChangeRequired) return <Navigate to="/change-password" replace />;
  if (user) return <Navigate to="/overview" replace />;
  return <Outlet />;
}

export function PasswordChangeGate() {
  const { status, user } = useAuth();
  if (status === "booting") return <BootScreen />;
  if (!user) return <Navigate to="/login" replace />;
  if (!user.passwordChangeRequired) return <Navigate to="/overview" replace />;
  return <Outlet />;
}

export function AdminGate({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  if (user?.role !== "ADMIN") return <Navigate to="/overview" replace />;
  return children;
}
