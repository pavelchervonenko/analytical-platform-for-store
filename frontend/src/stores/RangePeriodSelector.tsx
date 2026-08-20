import { CalendarRange, ChevronDown, ChevronLeft, ChevronRight, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from "react";
import { createPortal } from "react-dom";
import {
  formatDateShort,
  inclusiveDayCount,
  monthFromDate,
  monthRange,
  shiftDate,
  shiftMonth
} from "../shared/date";
import { useWorkspace, type AnalyticsPeriodMode } from "./WorkspaceProvider";
import {
  buildCalendarMonth,
  orderedRange,
  quickPeriods,
  type CalendarRange as DateRange,
  type QuickPeriod
} from "./periodCalendar";
import "./range-period.css";

const WEEKDAYS = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"];

export function RangePeriodSelector({ analyticsEnabled }: { analyticsEnabled: boolean }) {
  const workspace = useWorkspace();
  const rootRef = useRef<HTMLDivElement>(null);
  const popoverRef = useRef<HTMLElement>(null);
  const mobileViewport = useMobileViewport();
  const [open, setOpen] = useState(false);
  const [draftMode, setDraftMode] = useState<AnalyticsPeriodMode>("CUSTOM");
  const [draftStart, setDraftStart] = useState(workspace.periodStart);
  const [draftEnd, setDraftEnd] = useState(workspace.periodEnd);
  const [selectingEnd, setSelectingEnd] = useState(false);
  const [hoverDate, setHoverDate] = useState<string | null>(null);
  const [visibleMonth, setVisibleMonth] = useState(monthFromDate(workspace.periodStart));

  const dataThroughDate = workspace.dataThroughDate;
  const presets = useMemo(
    () => quickPeriods(workspace.today, workspace.completedThroughDate),
    [workspace.completedThroughDate, workspace.today]
  );

  const resetDraft = () => {
    const end = analyticsEnabled && workspace.periodEnd > workspace.today
      ? workspace.today
      : workspace.periodEnd;
    setDraftMode(analyticsEnabled ? workspace.periodMode : "MONTH");
    setDraftStart(workspace.periodStart);
    setDraftEnd(end);
    setSelectingEnd(false);
    setHoverDate(null);
    setVisibleMonth(monthFromDate(workspace.periodStart));
  };

  const openSelector = () => {
    resetDraft();
    setOpen(true);
  };

  useEffect(() => {
    if (!open) return;
    const closeOutside = (event: MouseEvent | TouchEvent) => {
      if (
        event.target instanceof Node
        && !rootRef.current?.contains(event.target)
        && !popoverRef.current?.contains(event.target)
      ) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", closeOutside);
    document.addEventListener("touchstart", closeOutside);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("mousedown", closeOutside);
      document.removeEventListener("touchstart", closeOutside);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [open]);

  const draftDays = inclusiveDayCount(draftStart, draftEnd);
  const draftValid = draftDays > 0 && draftDays <= 366 && (!analyticsEnabled || draftEnd <= workspace.today);
  const previewRange = selectingEnd && hoverDate
    ? orderedRange(draftStart, hoverDate)
    : { start: draftStart, end: draftEnd || draftStart };
  const exceedsCoverage = Boolean(
    analyticsEnabled && dataThroughDate && draftEnd && draftEnd > dataThroughDate
  );

  const selectDate = (date: string) => {
    if (date > workspace.today) return;
    setHoverDate(null);
    if (!analyticsEnabled) {
      const range = monthRange(monthFromDate(date));
      setDraftMode("MONTH");
      setDraftStart(range.start);
      setDraftEnd(range.end);
      setSelectingEnd(false);
      return;
    }
    setDraftMode("CUSTOM");
    if (!selectingEnd) {
      setDraftStart(date);
      setDraftEnd("");
      setSelectingEnd(true);
      return;
    }
    const range = orderedRange(draftStart, date);
    setDraftStart(range.start);
    setDraftEnd(range.end);
    setSelectingEnd(false);
  };

  const selectPreset = (preset: QuickPeriod) => {
    if (preset.disabled) return;
    setDraftMode(quickMode(preset, workspace.today));
    setDraftStart(preset.range.start);
    setDraftEnd(preset.range.end);
    setSelectingEnd(false);
    setHoverDate(null);
    setVisibleMonth(monthFromDate(preset.range.start));
  };

  const apply = () => {
    if (!draftValid) return;
    workspace.selectAnalyticsPeriod({
      mode: analyticsEnabled ? draftMode : "MONTH",
      start: draftStart,
      end: draftEnd
    });
    setOpen(false);
  };

  return (
    <div className={"range-period" + (open ? " is-open" : "")} ref={rootRef}>
      <button
        className="range-period__trigger"
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label="Выбрать период"
        onClick={() => open ? setOpen(false) : openSelector()}
      >
        <CalendarRange size={18} />
        <span>
          <small>{analyticsEnabled ? "Период аналитики" : "Расчетный месяц"}</small>
          <strong>{analyticsEnabled ? workspace.periodLabel : workspace.month}</strong>
        </span>
        <ChevronDown size={16} />
      </button>

      {open && (
        <MobilePortal enabled={mobileViewport}>
          <>
          <button className="range-period__backdrop" type="button" aria-label="Закрыть календарь" onClick={() => setOpen(false)} />
          <section
            ref={popoverRef}
            className={"range-period__popover" + (!analyticsEnabled ? " is-month-only" : "")}
            role="dialog"
            aria-modal="false"
            aria-label="Выбор периода"
          >
            <header className="range-period__mobile-heading">
              <div><small>Выбранный период</small><strong>{rangeLabel(draftStart, draftEnd)}</strong></div>
              <button type="button" onClick={() => setOpen(false)} aria-label="Закрыть календарь"><X /></button>
            </header>

            {analyticsEnabled && (
              <nav className="range-period__presets" aria-label="Быстрые периоды">
                {presets.map((preset) => (
                  <button
                    className={sameRange(preset.range, draftStart, draftEnd) ? "is-active" : ""}
                    type="button"
                    key={preset.code}
                    disabled={preset.disabled}
                    onClick={() => selectPreset(preset)}
                  >
                    {preset.label}
                  </button>
                ))}
              </nav>
            )}

            <div className="range-period__calendar-area">
              <div className="range-period__months">
                <CalendarMonth
                  month={visibleMonth}
                  today={workspace.today}
                  dataThroughDate={dataThroughDate}
                  range={previewRange}
                  selectingEnd={selectingEnd}
                  onSelect={selectDate}
                  onHover={setHoverDate}
                  onPrevious={() => setVisibleMonth((current) => shiftMonth(current, -1))}
                  onNext={() => setVisibleMonth((current) => shiftMonth(current, 1))}
                  nextDisabled={visibleMonth >= workspace.currentMonth}
                />
                <CalendarMonth
                  month={shiftMonth(visibleMonth, 1)}
                  today={workspace.today}
                  dataThroughDate={dataThroughDate}
                  range={previewRange}
                  selectingEnd={selectingEnd}
                  onSelect={selectDate}
                  onHover={setHoverDate}
                  onPrevious={() => setVisibleMonth((current) => shiftMonth(current, -1))}
                  onNext={() => setVisibleMonth((current) => shiftMonth(current, 1))}
                  nextDisabled={visibleMonth >= workspace.currentMonth}
                  second
                />
              </div>

              <footer className="range-period__footer">
                <div className="range-period__summary">
                  <strong>{rangeLabel(draftStart, draftEnd)}</strong>
                  {selectingEnd ? (
                    <small>Выберите дату окончания</small>
                  ) : !draftValid ? (
                    <small className="field-error">Период не должен превышать 366 дней</small>
                  ) : exceedsCoverage ? (
                    <small className="range-period__coverage-warning">Данные синхронизированы по {formatDateShort(dataThroughDate!)}</small>
                  ) : dataThroughDate ? (
                    <small>Данные синхронизированы по {formatDateShort(dataThroughDate)}</small>
                  ) : (
                    <small>{draftDays} {dayWord(draftDays)} в выбранном периоде</small>
                  )}
                </div>
                <div className="range-period__actions">
                  <button className="button button--ghost" type="button" onClick={() => setOpen(false)}>Отмена</button>
                  <button className="button button--primary" type="button" disabled={!draftValid || selectingEnd} onClick={apply}>Применить</button>
                </div>
              </footer>
            </div>
          </section>
        </>
        </MobilePortal>
      )}
    </div>
  );
}

function MobilePortal({ enabled, children }: { enabled: boolean; children: ReactNode }) {
  if (enabled && typeof document !== "undefined") return createPortal(children, document.body);
  return <>{children}</>;
}

function useMobileViewport(): boolean {
  const query = "(max-width: 720px)";
  const [matches, setMatches] = useState(
    () => typeof window !== "undefined" && typeof window.matchMedia === "function"
      ? window.matchMedia(query).matches
      : false
  );

  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const media = window.matchMedia(query);
    const update = () => setMatches(media.matches);
    update();
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);

  return matches;
}

function CalendarMonth({
  month,
  today,
  dataThroughDate,
  range,
  selectingEnd,
  onSelect,
  onHover,
  onPrevious,
  onNext,
  nextDisabled,
  second = false
}: {
  month: string;
  today: string;
  dataThroughDate?: string | null;
  range: DateRange;
  selectingEnd: boolean;
  onSelect: (date: string) => void;
  onHover: (date: string | null) => void;
  onPrevious: () => void;
  onNext: () => void;
  nextDisabled: boolean;
  second?: boolean;
}) {
  return (
    <section className={"range-calendar" + (second ? " range-calendar--second" : "")}>
      <header>
        <button className="range-calendar__previous" type="button" onClick={onPrevious} aria-label="Предыдущий месяц"><ChevronLeft /></button>
        <strong>{monthLabel(month)}</strong>
        <button className="range-calendar__next" type="button" onClick={onNext} disabled={nextDisabled} aria-label="Следующий месяц"><ChevronRight /></button>
      </header>
      <div className="range-calendar__weekdays" aria-hidden="true">
        {WEEKDAYS.map((weekday) => <span key={weekday}>{weekday}</span>)}
      </div>
      <div className="range-calendar__grid">
        {buildCalendarMonth(month).map((day) => {
          const disabled = day.date > today;
          const rangeStart = day.date === range.start;
          const rangeEnd = day.date === range.end;
          const inRange = day.date >= range.start && day.date <= range.end;
          const afterCoverage = Boolean(dataThroughDate && day.date > dataThroughDate && day.date <= today);
          const className = [
            !day.inCurrentMonth && "is-outside",
            inRange && "is-in-range",
            rangeStart && "is-range-start",
            rangeEnd && "is-range-end",
            day.date === today && "is-today",
            afterCoverage && "is-after-coverage",
            selectingEnd && day.date === range.end && "is-preview-end"
          ].filter(Boolean).join(" ");
          return (
            <button
              className={className}
              data-date={day.date}
              type="button"
              key={day.date}
              disabled={disabled}
              aria-label={dateLabel(day.date)}
              aria-pressed={rangeStart || rangeEnd}
              onClick={() => onSelect(day.date)}
              onMouseEnter={() => !disabled && onHover(day.date)}
              onMouseLeave={() => onHover(null)}
              onKeyDown={(event) => navigateDateGrid(event, day.date)}
            >
              <span>{Number(day.date.slice(-2))}</span>
            </button>
          );
        })}
      </div>
    </section>
  );
}

function quickMode(preset: QuickPeriod, today: string): AnalyticsPeriodMode {
  if (preset.code === "PREVIOUS_WEEK") return "WEEK";
  if (preset.code === "THIS_WEEK" && preset.range.end === today) return "WEEK";
  if (preset.code === "PREVIOUS_MONTH") return "MONTH";
  return "CUSTOM";
}

function sameRange(range: DateRange, start: string, end: string): boolean {
  return range.start === start && range.end === end;
}

function rangeLabel(start: string, end: string): string {
  if (!start) return "Период не выбран";
  if (!end) return `${formatDateShort(start)} — …`;
  if (start === end) return formatDateShort(start);
  return `${formatDateShort(start)} — ${formatDateShort(end)}`;
}

function monthLabel(month: string): string {
  const { start } = monthRange(month);
  return new Intl.DateTimeFormat("ru-RU", { month: "long", year: "numeric", timeZone: "UTC" })
    .format(new Date(`${start}T00:00:00Z`));
}

function dateLabel(date: string): string {
  return new Intl.DateTimeFormat("ru-RU", {
    weekday: "long",
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "UTC"
  }).format(new Date(`${date}T00:00:00Z`));
}

function dayWord(value: number): string {
  const remainder100 = value % 100;
  const remainder10 = value % 10;
  if (remainder100 >= 11 && remainder100 <= 14) return "дней";
  if (remainder10 === 1) return "день";
  if (remainder10 >= 2 && remainder10 <= 4) return "дня";
  return "дней";
}

function navigateDateGrid(event: ReactKeyboardEvent<HTMLButtonElement>, date: string) {
  const offsets: Partial<Record<string, number>> = {
    ArrowLeft: -1,
    ArrowRight: 1,
    ArrowUp: -7,
    ArrowDown: 7
  };
  const offset = offsets[event.key];
  if (offset == null) return;
  event.preventDefault();
  document.querySelector<HTMLButtonElement>(`[data-date="${shiftDate(date, offset)}"]:not(:disabled)`)?.focus();
}
