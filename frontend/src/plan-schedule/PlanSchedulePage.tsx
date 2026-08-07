import { CalendarDays, Target } from "lucide-react";
import { useSearchParams } from "react-router";
import { PlanPanel } from "./PlanPanel";
import { SchedulePanel } from "./SchedulePanel";
import "./styles.css";

type Section = "plan" | "shifts";

export function PlanSchedulePage() {
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
      <header className="page-heading plan-schedule-heading"><h1>План и смены</h1></header>
      <nav className="plan-schedule-tabs" aria-label="Разделы плана и смен"><button className={section === "plan" ? "active" : ""} type="button" aria-current={section === "plan" ? "page" : undefined} onClick={() => selectSection("plan")}><Target />План магазина</button><button className={section === "shifts" ? "active" : ""} type="button" aria-current={section === "shifts" ? "page" : undefined} onClick={() => selectSection("shifts")}><CalendarDays />Смены</button></nav>
      {section === "plan" ? <PlanPanel /> : <SchedulePanel />}
    </div>
  );
}
