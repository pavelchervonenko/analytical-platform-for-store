import { CalendarRange, ChevronDown } from "lucide-react";
import { useRef, useState } from "react";
import { inclusiveDayCount, monthRange, weekRange } from "../shared/date";
import { useWorkspace, type AnalyticsPeriodMode } from "./WorkspaceProvider";

export function PeriodSelector({ analyticsEnabled }: { analyticsEnabled: boolean }) {
  const workspace = useWorkspace();
  const detailsRef = useRef<HTMLDetailsElement>(null);
  const [mode, setMode] = useState<AnalyticsPeriodMode>(analyticsEnabled ? workspace.periodMode : "MONTH");
  const [month, setMonth] = useState(workspace.month);
  const [weekDate, setWeekDate] = useState(workspace.periodStart);
  const [customStart, setCustomStart] = useState(workspace.periodStart);
  const [customEnd, setCustomEnd] = useState(workspace.periodEnd);

  const resetDraft = () => {
    setMode(analyticsEnabled ? workspace.periodMode : "MONTH");
    setMonth(workspace.month);
    setWeekDate(workspace.periodStart);
    setCustomStart(workspace.periodStart);
    setCustomEnd(workspace.periodEnd);
  };

  const customDays = inclusiveDayCount(customStart, customEnd);
  const customValid = customDays > 0 && customDays <= 366 && customEnd <= workspace.today;

  const apply = () => {
    if (mode === "MONTH") {
      workspace.selectAnalyticsPeriod({ mode, ...monthRange(month) });
    } else if (mode === "WEEK") {
      workspace.selectAnalyticsPeriod({ mode, ...weekRange(weekDate) });
    } else if (customValid) {
      workspace.selectAnalyticsPeriod({ mode, start: customStart, end: customEnd });
    }
    detailsRef.current?.removeAttribute("open");
  };

  return (
    <details className="period-selector" ref={detailsRef} onToggle={(event) => { if (event.currentTarget.open) resetDraft(); }}>
      <summary aria-label="Выбрать период">
        <CalendarRange size={18} />
        <span><small>{analyticsEnabled ? "Период аналитики" : "Расчетный месяц"}</small><strong>{analyticsEnabled ? workspace.periodLabel : workspace.month}</strong></span>
        <ChevronDown size={16} />
      </summary>
      <div className="period-popover">
        <div className="period-popover__heading"><strong>Выберите период</strong><small>{analyticsEnabled ? "Сравнение строится с предыдущим периодом той же длины" : "Этот раздел работает только с полным календарным месяцем"}</small></div>
        {analyticsEnabled && <div className="period-tabs" role="radiogroup" aria-label="Тип периода">
          {(["MONTH", "WEEK", "CUSTOM"] as const).map((value) => <button className={mode === value ? "is-active" : ""} key={value} type="button" role="radio" aria-checked={mode === value} onClick={() => setMode(value)}>{value === "MONTH" ? "Месяц" : value === "WEEK" ? "Неделя" : "Произвольно"}</button>)}
        </div>}
        {mode === "MONTH" && <label className="field"><span>Месяц</span><input type="month" value={month} max={workspace.currentMonth} onChange={(event) => setMonth(event.target.value)} /></label>}
        {mode === "WEEK" && <label className="field"><span>День недели</span><input type="date" value={weekDate} max={workspace.today} onChange={(event) => setWeekDate(event.target.value)} /><small>Неделя считается с понедельника по воскресенье; текущая неделя ограничивается сегодняшней датой.</small></label>}
        {mode === "CUSTOM" && <div className="period-custom-fields"><label className="field"><span>Начало</span><input type="date" value={customStart} max={workspace.today} onChange={(event) => setCustomStart(event.target.value)} /></label><label className="field"><span>Окончание</span><input type="date" value={customEnd} min={customStart} max={workspace.today} onChange={(event) => setCustomEnd(event.target.value)} /></label><small className={customValid ? "" : "field-error"}>{customValid ? `${customDays} дн., максимум 366 дней` : "Проверьте границы периода; максимум — 366 дней."}</small></div>}
        <div className="period-popover__actions"><button className="button button--ghost" type="button" onClick={() => detailsRef.current?.removeAttribute("open")}>Отмена</button><button className="button button--primary" type="button" disabled={mode === "CUSTOM" && !customValid} onClick={apply}>Применить</button></div>
      </div>
    </details>
  );
}
