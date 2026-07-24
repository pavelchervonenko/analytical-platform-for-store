import { Calculator, DatabaseZap, PackageSearch, Users } from "lucide-react";
import { useSearchParams } from "react-router-dom";
import { ClassificationPanel } from "./ClassificationPanel";
import { SchemesPanel } from "./SchemesPanel";
import { SyncPanel } from "./SyncPanel";
import { UsersPanel } from "./UsersPanel";

const views = [
  { id: "users", label: "Пользователи", description: "Роли и доступы", icon: Users },
  { id: "sync", label: "Синхронизация", description: "Backfill и задачи", icon: DatabaseZap },
  { id: "schemes", label: "Версии расчётов", description: "Рейтинг и зарплата", icon: Calculator },
  { id: "classification", label: "Классификация", description: "Payroll-категории", icon: PackageSearch }
] as const;
type AdminView = typeof views[number]["id"];

export function AdminPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const requested = searchParams.get("adminView");
  const active: AdminView = views.some((view) => view.id === requested) ? requested as AdminView : "users";
  const select = (view: AdminView) => setSearchParams((current) => { const next = new URLSearchParams(current); next.set("adminView", view); return next; });

  return <div className="admin-page">
    <header className="page-heading"><div><p className="eyebrow">Системный контур</p><h1>Администрирование</h1><p>Управление доступами, интеграциями и неизменяемыми версиями бизнес-правил.</p></div></header>
    <nav className="admin-view-tabs" aria-label="Разделы администрирования">{views.map(({ id, label, description, icon: Icon }) => <button className={active === id ? "is-active" : ""} type="button" key={id} onClick={() => select(id)} aria-current={active === id ? "page" : undefined}><Icon /><span><strong>{label}</strong><small>{description}</small></span></button>)}</nav>
    <div className="admin-view" key={active}>{active === "users" ? <UsersPanel /> : active === "sync" ? <SyncPanel /> : active === "schemes" ? <SchemesPanel /> : <ClassificationPanel />}</div>
  </div>;
}
