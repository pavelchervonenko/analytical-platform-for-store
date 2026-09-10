import type { ReactNode } from "react";
import { ChartNoAxesCombined, SlidersHorizontal } from "lucide-react";
import { Navigate, NavLink, useLocation } from "react-router";
import { PlanPanel } from "./PlanPanel";
import { PlanSettingsPanel } from "./PlanSettingsPanel";
import { SchedulePanel } from "./SchedulePanel";
import "./styles.css";

function PlanningPage({
  title,
  children
}: {
  title: string;
  children: ReactNode;
}) {
  return (
    <div className="plan-schedule-page">
      <header className="page-heading plan-schedule-heading"><h1>{title}</h1></header>
      {children}
    </div>
  );
}

function PlanNavigation() {
  const location = useLocation();
  const searchParams = new URLSearchParams(location.search);
  searchParams.delete("section");
  const search = searchParams.toString();
  const linkClassName = ({ isActive }: { isActive: boolean }) => isActive ? "is-active" : "";

  return (
    <nav className="plan-navigation" aria-label="Разделы плана">
      <NavLink end to={{ pathname: "/plan", search }} className={linkClassName}>
        <ChartNoAxesCombined />
        <span>Обзор плана</span>
      </NavLink>
      <NavLink to={{ pathname: "/plan/settings", search }} className={linkClassName}>
        <SlidersHorizontal />
        <span>Настройка плана</span>
      </NavLink>
    </nav>
  );
}

export function PlanPage() {
  const location = useLocation();
  const searchParams = new URLSearchParams(location.search);

  if (searchParams.get("section") === "shifts") {
    searchParams.delete("section");
    return <Navigate to={{ pathname: "/shifts", search: searchParams.toString() }} replace />;
  }

  return <PlanningPage title="План"><PlanNavigation /><PlanPanel /></PlanningPage>;
}

export function PlanSettingsPage() {
  return <PlanningPage title="План"><PlanNavigation /><PlanSettingsPanel /></PlanningPage>;
}

export function ShiftsPage() {
  return <PlanningPage title="Смены"><SchedulePanel /></PlanningPage>;
}
