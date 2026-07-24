import { BarChart3, CalendarRange, ChevronDown, CircleDollarSign, DatabaseZap, FileArchive, LogOut, Menu, Settings, Users, X } from "lucide-react";
import { useState } from "react";
import { NavLink, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { PeriodSelector } from "../stores/PeriodSelector";
import { WorkspaceProvider, useWorkspace } from "../stores/WorkspaceProvider";

const navigation = [
  { to: "/overview", label: "Обзор", icon: BarChart3 },
  { to: "/employees", label: "Сотрудники", icon: Users },
  { to: "/plan", label: "План и смены", icon: CalendarRange },
  { to: "/payroll", label: "Зарплата и аудит", icon: CircleDollarSign },
  { to: "/reports", label: "Отчёты", icon: FileArchive },
  { to: "/quality", label: "Качество данных", icon: DatabaseZap }
];

function ShellContent() {
  const { user, logout } = useAuth();
  const workspace = useWorkspace();
  const location = useLocation();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const analyticsPeriodEnabled = location.pathname === "/overview" || location.pathname.startsWith("/employees");

  return (
    <div className="app-layout">
      <a className="skip-link" href="#main-content">К основному содержимому</a>
      <aside className={`sidebar ${mobileMenuOpen ? "sidebar--open" : ""}`} aria-label="Основная навигация">
        <div className="sidebar__brand"><span className="brand-mark">S</span><span><strong>Store</strong><small>Analytics</small></span></div>
        <button className="sidebar__close" type="button" onClick={() => setMobileMenuOpen(false)} aria-label="Закрыть меню"><X /></button>
        <nav>
          <span className="nav-caption">Рабочее пространство</span>
          {navigation.map(({ to, label, icon: Icon }) => <NavLink key={to} to={{ pathname: to, search: location.search }} onClick={() => setMobileMenuOpen(false)}><Icon size={19} /><span>{label}</span></NavLink>)}
          {user?.role === "ADMIN" && <NavLink to={{ pathname: "/admin", search: location.search }} onClick={() => setMobileMenuOpen(false)}><Settings size={19} /><span>Администрирование</span></NavLink>}
        </nav>
        <div className="sidebar__footer">
          <div className="sidebar-user"><span>{user?.displayName.slice(0, 1).toUpperCase()}</span><div><strong>{user?.displayName}</strong><small>{user?.role === "ADMIN" ? "Администратор" : "Руководитель"}</small></div></div>
          <button type="button" onClick={() => void logout()}><LogOut size={18} /><span>Выйти</span></button>
        </div>
      </aside>

      <div className="app-main">
        <header className="topbar">
          <button className="icon-button topbar__menu" type="button" onClick={() => setMobileMenuOpen(true)} aria-label="Открыть меню"><Menu /></button>
          <label className="store-selector">
            <span className="store-selector__avatar">{workspace.selectedStore.name.slice(0, 1).toUpperCase()}</span>
            <span className="store-selector__copy"><small>Магазин</small><strong>{workspace.selectedStore.name}</strong></span>
            <select value={workspace.selectedStore.id} onChange={(event) => workspace.selectStore(event.target.value)} aria-label="Выбрать магазин">{workspace.stores.map((store) => <option key={store.id} value={store.id}>{store.name}</option>)}</select>
            <ChevronDown size={16} aria-hidden="true" />
          </label>

          <PeriodSelector analyticsEnabled={analyticsPeriodEnabled} />

          <div className="topbar__profile" title={user?.email}><span>{user?.displayName.slice(0, 1).toUpperCase()}</span><div><strong>{user?.displayName}</strong><small>{user?.email}</small></div></div>
        </header>
        <main id="main-content" className="page-content" tabIndex={-1}><Outlet /></main>
      </div>
      {mobileMenuOpen && <button className="sidebar-backdrop" type="button" onClick={() => setMobileMenuOpen(false)} aria-label="Закрыть меню" />}
    </div>
  );
}

export function AppShell() {
  return <WorkspaceProvider><ShellContent /></WorkspaceProvider>;
}
