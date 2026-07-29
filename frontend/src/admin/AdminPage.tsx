import { Calculator, DatabaseZap, FileArchive, PackageOpen, PackageSearch, Users } from "lucide-react";
import { useSearchParams } from "react-router";
import { CategoryImportPanel } from "./CategoryImportPanel";
import { ClassificationPanel } from "./ClassificationPanel";
import { ReportOperationsPanel } from "./ReportOperationsPanel";
import { SchemesPanel } from "./SchemesPanel";
import { SyncPanel } from "./SyncPanel";
import { UsersPanel } from "./UsersPanel";
import "./operations.css";

const views = [
  { id: "users", label: "Пользователи", description: "Роли и доступы", icon: Users },
  { id: "sync", label: "Синхронизация", description: "Backfill и задачи", icon: DatabaseZap },
  { id: "reports", label: "Архив отчетов", description: "Восстановление", icon: FileArchive },
  { id: "schemes", label: "Версии расчетов", description: "Рейтинг и зарплата", icon: Calculator },
  { id: "classification", label: "Классификация", description: "Payroll-категории", icon: PackageSearch },
  { id: "category-import", label: "Импорт категорий", description: "Справочник LiveSklad", icon: PackageOpen }
] as const;
type AdminView = typeof views[number]["id"];

export function AdminPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const requested = searchParams.get("adminView");
  const active: AdminView = views.some((view) => view.id === requested) ? requested as AdminView : "users";
  const select = (view: AdminView) => setSearchParams((current) => { const next = new URLSearchParams(current); next.set("adminView", view); return next; });

  return <div className="admin-page">
    <header className="page-heading"><div><p className="eyebrow">Системный контур</p><h1>Администрирование</h1></div></header>
    <nav className="admin-view-tabs" aria-label="Разделы администрирования">{views.map(({ id, label, description, icon: Icon }) => <button className={active === id ? "is-active" : ""} type="button" key={id} onClick={() => select(id)} aria-current={active === id ? "page" : undefined}><Icon /><span><strong>{label}</strong><small>{description}</small></span></button>)}</nav>
    <div className="admin-view" key={active}>{active === "users" ? <UsersPanel /> : active === "sync" ? <SyncPanel /> : active === "reports" ? <ReportOperationsPanel /> : active === "schemes" ? <SchemesPanel /> : active === "classification" ? <ClassificationPanel /> : <CategoryImportPanel />}</div>
  </div>;
}
