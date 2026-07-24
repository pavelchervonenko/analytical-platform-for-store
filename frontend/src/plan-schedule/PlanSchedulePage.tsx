import { CalendarDays, Target } from "lucide-react";
import { useSearchParams } from "react-router-dom";
import { formatMonth } from "../shared/date";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { PlanPanel } from "./PlanPanel";
import { SchedulePanel } from "./SchedulePanel";
import "./styles.css";

type Section = "plan" | "shifts";

export function PlanSchedulePage() {
  const { selectedStore, month } = useWorkspace();
  const [searchParams, setSearchParams] = useSearchParams();
  const section: Section = searchParams.get("section") === "shifts" ? "shifts" : "plan";
  const selectSection = (nextSection: Section) => {
    setSearchParams((current) => {
      const next = new URLSearchParams(current);
      next.set("section", nextSection);
      return next;
    });
  };

  return (
    <div className="plan-schedule-page">
      <header className="page-heading plan-schedule-heading"><div><p className="eyebrow">{selectedStore.name}</p><h1>План и смены</h1><p>Цели магазина и фактически отработанные часы за {formatMonth(month)}.</p></div></header>
      <nav className="plan-schedule-tabs" aria-label="Разделы плана и смен"><button className={section === "plan" ? "active" : ""} type="button" aria-current={section === "plan" ? "page" : undefined} onClick={() => selectSection("plan")}><Target />План магазина<span>4 направления</span></button><button className={section === "shifts" ? "active" : ""} type="button" aria-current={section === "shifts" ? "page" : undefined} onClick={() => selectSection("shifts")}><CalendarDays />Смены<span>часы по дням</span></button></nav>
      {section === "plan" ? <PlanPanel /> : <SchedulePanel />}
    </div>
  );
}
