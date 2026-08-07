import { BellRing, BrainCircuit, Calculator, DatabaseZap, FileArchive, PackageOpen, PackageSearch, Users } from "lucide-react";
import { useSearchParams } from "react-router";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { CategoryImportPanel } from "./CategoryImportPanel";
import { ClassificationPanel } from "./ClassificationPanel";
import { LlmOperationsPanel } from "./LlmOperationsPanel";
import { ReportOperationsPanel } from "./ReportOperationsPanel";
import { SchemesPanel } from "./SchemesPanel";
import { SyncPanel } from "./SyncPanel";
import { TelegramDeliveryPanel } from "./TelegramDeliveryPanel";
import { UsersPanel } from "./UsersPanel";
import "./operations.css";

const views = [
  { id: "users", label: "Пользователи", icon: Users },
  { id: "sync", label: "Синхронизация", icon: DatabaseZap },
  { id: "reports", label: "Архив отчетов", icon: FileArchive },
  { id: "schemes", label: "Правила расчетов", icon: Calculator },
  { id: "classification", label: "Категории товаров", icon: PackageSearch },
  { id: "category-import", label: "Импорт категорий", icon: PackageOpen },
  { id: "llm", label: "ИИ-разбор", icon: BrainCircuit },
  { id: "telegram", label: "Telegram", icon: BellRing }
] as const;
type AdminView = typeof views[number]["id"];

export function AdminPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { selectedStore, month } = useWorkspace();
  const requested = searchParams.get("adminView");
  const active: AdminView = views.some((view) => view.id === requested) ? requested as AdminView : "users";
  const contextualView = active === "sync" || active === "reports" || active === "classification" || active === "category-import";
  const viewKey = contextualView ? `${active}:${selectedStore.id}:${month}` : active;
  const select = (view: AdminView) => setSearchParams((current) => { const next = new URLSearchParams(current); next.set("adminView", view); return next; });

  return <div className="admin-page">
    <header className="page-heading"><h1>Настройки</h1></header>
    <nav className="admin-view-tabs" aria-label="Разделы администрирования">{views.map(({ id, label, icon: Icon }) => <button className={active === id ? "is-active" : ""} type="button" key={id} onClick={() => select(id)} aria-current={active === id ? "page" : undefined}><Icon /><strong>{label}</strong></button>)}</nav>
    <div className="admin-view" key={viewKey}>{active === "users" ? <UsersPanel /> : active === "sync" ? <SyncPanel /> : active === "reports" ? <ReportOperationsPanel /> : active === "schemes" ? <SchemesPanel /> : active === "classification" ? <ClassificationPanel /> : active === "category-import" ? <CategoryImportPanel /> : active === "llm" ? <LlmOperationsPanel /> : <TelegramDeliveryPanel />}</div>
  </div>;
}
