import { BarChart3, CalendarRange, ChevronDown, CircleDollarSign, DatabaseZap, FileArchive, LogOut, Menu, Settings, Sparkles, Users, X } from "lucide-react";
import { useState } from "react";
import { NavLink, Outlet, useLocation } from "react-router";
import { InitialStoreSetup } from "../admin/InitialStoreSetup";
import { useAuth } from "../auth/AuthProvider";
import { PeriodSelector } from "../stores/PeriodSelector";
import { WorkspaceProvider, useWorkspace } from "../stores/WorkspaceProvider";

const navigationGroups = [
  {
    label: "Аналитика",
    items: [
      { to: "/overview", label: "Обзор", icon: BarChart3, visibility: "all" },
      { to: "/insights", label: "ИИ-разбор", icon: Sparkles, visibility: "all" },
      { to: "/employees", label: "Сотрудники", icon: Users, visibility: "all" }
    ]
  },
  {
    label: "Управление",
    items: [
      { to: "/plan", label: "План и смены", icon: CalendarRange, visibility: "all" },
      { to: "/payroll", label: "Зарплата", icon: CircleDollarSign, visibility: "all" },
      { to: "/reports", label: "Отчеты", icon: FileArchive, visibility: "all" }
    ]
  },
  {
    label: "Система",
    items: [
      { to: "/quality", label: "Качество данных", icon: DatabaseZap, visibility: "all" },
      { to: "/admin", label: "Настройки", icon: Settings, visibility: "admin" }
    ]
  }
] as const;

function roleLabel(role: "ADMIN" | "MANAGER" | "UNKNOWN" | undefined): string {
  if (role === "ADMIN") return "Администратор";
  if (role === "MANAGER") return "Руководитель";
  return "Неизвестная роль";
}

function ShellContent() {
  const { user, logout } = useAuth();
  const workspace = useWorkspace();
  const location = useLocation();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const analyticsPeriodEnabled = location.pathname === "/overview" || location.pathname.startsWith("/employees");
  const periodSelectorVisible = location.pathname !== "/insights";
  return (
    <div className="app-layout">
      <a className="skip-link" href="#main-content">К основному содержимому</a>
      <aside className={`sidebar ${mobileMenuOpen ? "sidebar--open" : ""}`} aria-label="Основная навигация">
        <div className="sidebar__brand"><span className="brand-mark">S</span><span><strong>Store</strong><small>Analytics</small></span></div>
        <button className="sidebar__close" type="button" onClick={() => setMobileMenuOpen(false)} aria-label="Закрыть меню"><X /></button>
        <nav>
          {navigationGroups.map((group) => {
            const items = group.items.filter((item) =>
              item.visibility !== "admin" || user?.role === "ADMIN");
            if (items.length === 0) return null;
            return (
              <div className="nav-group" key={group.label}>
                <span className="nav-caption">{group.label}</span>
                {items.map(({ to, label, icon: Icon }) => (
                  <NavLink key={to} to={{ pathname: to, search: location.search }} onClick={() => setMobileMenuOpen(false)}>
                    <Icon size={19} /><span>{label}</span>
                  </NavLink>
                ))}
              </div>
            );
          })}
        </nav>
        <div className="sidebar__footer">
          <NavLink className="sidebar-user" to="/profile"><span>{user?.displayName.slice(0, 1).toUpperCase()}</span><div><strong>{user?.displayName}</strong><small>{roleLabel(user?.role)}</small></div></NavLink>
          <button type="button" onClick={() => void logout()}><LogOut size={18} /><span>Выйти</span></button>
        </div>
      </aside>

      <div className="app-main">
        <header className="topbar">
          <button className="icon-button topbar__menu" type="button" onClick={() => setMobileMenuOpen(true)} aria-label="Открыть меню"><Menu /></button>
          <label className="store-selector">
            <span className="store-selector__avatar">{workspace.selectedStore.name.slice(0, 1).toUpperCase()}</span>
            <span className="store-selector__copy"><strong>{workspace.selectedStore.name}</strong></span>
            <select value={workspace.selectedStore.id} onChange={(event) => workspace.selectStore(event.target.value)} aria-label="Выбрать магазин">{workspace.stores.map((store) => <option key={store.id} value={store.id}>{store.name}</option>)}</select>
            <ChevronDown size={16} aria-hidden="true" />
          </label>

          {periodSelectorVisible && (
            <PeriodSelector analyticsEnabled={analyticsPeriodEnabled} />
          )}

          <NavLink className="topbar__profile" to="/profile" title="Профиль и безопасность"><span>{user?.displayName.slice(0, 1).toUpperCase()}</span><div><strong>{user?.displayName}</strong><small>{user?.email}</small></div></NavLink>
        </header>
        <main id="main-content" className="page-content" tabIndex={-1}><Outlet /></main>
      </div>
      {mobileMenuOpen && <button className="sidebar-backdrop" type="button" onClick={() => setMobileMenuOpen(false)} aria-label="Закрыть меню" />}
    </div>
  );
}

export function AppShell() {
  const { user } = useAuth();
  return (
    <WorkspaceProvider emptyState={user?.role === "ADMIN" ? <InitialStoreSetup /> : undefined}>
      <ShellContent />
    </WorkspaceProvider>
  );
}
