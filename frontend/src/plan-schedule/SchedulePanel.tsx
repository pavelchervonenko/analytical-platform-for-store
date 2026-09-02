import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CalendarCheck2, Check, Clock3, Eraser, Save, UserRoundCheck, UsersRound, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type RefObject } from "react";
import { isApiClientError, type EtaggedResource } from "../api/client";
import type { EmployeeRatingSetting, EmployeeShift, WorkScheduleDay, WorkShiftInput } from "../api/contracts";
import { getEmployeeRatingSettings, getWorkSchedule, getWorkScheduleDay, queryKeys, replaceWorkScheduleDay } from "../api/queries";
import { currentDateInTimeZone, formatDate, formatMonth } from "../shared/date";
import { formatNumber } from "../shared/format";
import { QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { buildMonthCalendar, isSelectableShiftSeller, parseWorkedHours } from "./forms";

const weekDays = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];

interface RosterEmployee {
  employeeId: string;
  displayName: string;
  eligible: boolean;
}

function dayLabel(date: string): string {
  return new Intl.DateTimeFormat("ru-RU", { weekday: "long", day: "numeric", month: "long", timeZone: "UTC" }).format(new Date(`${date}T00:00:00Z`));
}

function normalizedShifts(shifts: EmployeeShift[]): string {
  return JSON.stringify(shifts.map((shift) => [shift.employeeId, shift.workedHours]).sort(([left], [right]) => String(left).localeCompare(String(right))));
}

function ShiftDayEditor({
  workDate,
  dayShifts,
  etag,
  settings,
  scheduleKey,
  returnFocusRef,
  onClose,
  onSaved
}: {
  workDate: string;
  dayShifts: EmployeeShift[];
  etag: string;
  settings: EmployeeRatingSetting[];
  scheduleKey: readonly unknown[];
  returnFocusRef: RefObject<HTMLButtonElement | null>;
  onClose: () => void;
  onSaved: (date: string) => void;
}) {
  const { selectedStore, month } = useWorkspace();
  const storeId = selectedStore.id;
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<Record<string, string>>(() => Object.fromEntries(dayShifts.map((shift) => [shift.employeeId, String(shift.workedHours)])));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [clearConfirmation, setClearConfirmation] = useState(false);

  const dialogRef = useRef<HTMLElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const previousFocus = returnFocusRef.current ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null);
    const frame = window.requestAnimationFrame(() => closeButtonRef.current?.focus());
    return () => {
      window.cancelAnimationFrame(frame);
      previousFocus?.focus();
    };
  }, [returnFocusRef]);
  const roster = useMemo(() => {
    const employees = new Map<string, RosterEmployee>();
    for (const setting of settings) {
      if (!isSelectableShiftSeller(setting)) continue;
      employees.set(setting.employeeId, {
        employeeId: setting.employeeId, displayName: setting.displayName, eligible: true
      });
    }
    for (const shift of dayShifts) if (!employees.has(shift.employeeId)) employees.set(shift.employeeId, { employeeId: shift.employeeId, displayName: shift.employeeName, eligible: false });
    return [...employees.values()].sort((left, right) => Number(right.eligible) - Number(left.eligible) || left.displayName.localeCompare(right.displayName, "ru-RU"));
  }, [dayShifts, settings]);

  const mutation = useMutation({
    mutationFn: (shifts: WorkShiftInput[]) => replaceWorkScheduleDay(storeId, workDate, etag, shifts),
    onSuccess: async (saved) => {
      queryClient.setQueryData<EmployeeShift[]>(scheduleKey, (current) => [...(current ?? []).filter((shift) => shift.workDate !== workDate), ...saved.value.shifts].sort((left, right) => left.workDate.localeCompare(right.workDate) || left.employeeName.localeCompare(right.employeeName, "ru-RU")));
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.employees(storeId) }),
        queryClient.invalidateQueries({ queryKey: ["stores", storeId, "period-quality"] }),
        queryClient.invalidateQueries({ queryKey: ["stores", storeId, "payroll"] })
      ]);
      onSaved(workDate);
    }
  });

  const currentNormalized = JSON.stringify(Object.entries(draft).map(([employeeId, hours]) => [employeeId, parseWorkedHours(hours)]).sort(([left], [right]) => String(left).localeCompare(String(right))));
  const dirty = currentNormalized !== normalizedShifts(dayShifts);
  const selectedCount = Object.keys(draft).length;

  const requestClose = () => {
    if (!dirty || window.confirm("Закрыть редактор и потерять несохраненные изменения?")) onClose();
  };
  const handleDialogKeyDown = (event: ReactKeyboardEvent<HTMLElement>) => {
    if (event.key === "Escape") {
      event.preventDefault();
      requestClose();
      return;
    }
    if (event.key !== "Tab") return;
    const focusable = [...(dialogRef.current?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), [href], [tabindex]:not([tabindex="-1"])') ?? [])];
    const first = focusable[0];
    const last = focusable.at(-1);
    if (!first || !last) return;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };
  const toggle = (employee: RosterEmployee) => {
    setDraft((current) => {
      const next = { ...current };
      if (employee.employeeId in next) delete next[employee.employeeId];
      else if (employee.eligible) next[employee.employeeId] = "11";
      return next;
    });
    setErrors((current) => ({ ...current, [employee.employeeId]: "" }));
    setClearConfirmation(false);
  };
  const save = () => {
    const nextErrors: Record<string, string> = {};
    const inputs: WorkShiftInput[] = [];
    for (const [employeeId, value] of Object.entries(draft)) {
      const employee = roster.find((item) => item.employeeId === employeeId);
      const hours = parseWorkedHours(value);
      if (!employee?.eligible) nextErrors[employeeId] = "Сотрудник больше недоступен — удалите его из дня.";
      else if (hours == null) nextErrors[employeeId] = "Введите от 0,01 до 11 часов, максимум два знака.";
      else inputs.push({ employeeId, workedHours: hours });
    }
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length === 0) mutation.mutate(inputs);
  };
  const clear = () => {
    if (!clearConfirmation) {
      setClearConfirmation(true);
      return;
    }
    setClearConfirmation(false);
    mutation.mutate([]);
  };

  return (
    <div className="shift-editor-overlay">
      <section className="shift-editor" role="dialog" aria-modal="true" aria-labelledby="shift-editor-title" ref={dialogRef} onKeyDown={handleDialogKeyDown}>
        <header><div><p className="eyebrow">{formatMonth(month)}</p><h2 id="shift-editor-title">{dayLabel(workDate)}</h2><p>Сохранение полностью заменит состав этого дня.</p></div><button className="icon-button" type="button" onClick={requestClose} aria-label="Закрыть редактор" ref={closeButtonRef}><X /></button></header>
        <div className="shift-editor__summary"><span><UsersRound />{selectedCount} сотрудников</span><span><Clock3 />Полная смена — 11 часов</span></div>
        {roster.length === 0 ? <div className="panel-empty"><UsersRound /><strong>Нет доступных продавцов</strong><p>Включите нужных сотрудников в рейтинг магазина.</p></div> : <div className="shift-roster">{roster.map((employee) => {
          const selected = employee.employeeId in draft;
          return <article className={`${selected ? "shift-roster-row--selected" : ""} ${!employee.eligible ? "shift-roster-row--unavailable" : ""}`} key={employee.employeeId}><button className="shift-check" type="button" aria-pressed={selected} disabled={!employee.eligible && !selected} onClick={() => toggle(employee)}><span>{selected && <Check />}</span><i>{employee.displayName.slice(0, 1).toUpperCase()}</i><strong>{employee.displayName}</strong></button><label><span>Часов</span><input type="text" inputMode="decimal" value={draft[employee.employeeId] ?? ""} disabled={!selected || !employee.eligible} onChange={(event) => { setDraft((current) => ({ ...current, [employee.employeeId]: event.target.value })); setErrors((current) => ({ ...current, [employee.employeeId]: "" })); }} aria-invalid={Boolean(errors[employee.employeeId])} /></label>{selected && employee.eligible && <button className="full-shift-button" type="button" onClick={() => setDraft((current) => ({ ...current, [employee.employeeId]: "11" }))} aria-label={`Установить полную смену для ${employee.displayName}`}>11 часов</button>}{!employee.eligible && <small>Недоступен для новых смен</small>}{errors[employee.employeeId] && <p role="alert">{errors[employee.employeeId]}</p>}</article>;
        })}</div>}
        {mutation.isError && <div className="form-alert" role="alert">{isApiClientError(mutation.error) && mutation.error.status === 412 ? "Этот день уже изменен другим пользователем. Закройте редактор и откройте день снова." : isApiClientError(mutation.error) ? mutation.error.message : "Не удалось сохранить смены. Обновите данные и повторите действие."}</div>}
        <footer><div>{dayShifts.length > 0 && <>{clearConfirmation && <span className="clear-confirmation">Очистить весь день?</span>}<button className="button button--ghost button--danger-ghost" type="button" disabled={mutation.isPending} onClick={clear}><Eraser size={15} />{clearConfirmation ? "Подтвердить" : "Очистить день"}</button>{clearConfirmation && <button className="button button--ghost" type="button" onClick={() => setClearConfirmation(false)}>Отмена</button>}</>}</div><button className="button button--primary" type="button" disabled={!dirty || mutation.isPending} onClick={save}><Save size={16} />{mutation.isPending ? "Сохраняем…" : "Сохранить день"}</button></footer>
      </section>
    </div>
  );
}

function ScheduleSkeleton() {
  return <div className="schedule-skeleton" aria-busy="true" aria-label="Загрузка смен"><div className="schedule-summary-grid">{Array.from({ length: 3 }, (_, index) => <span className="skeleton employee-summary-skeleton" key={index} />)}</div><span className="skeleton schedule-calendar-skeleton" /></div>;
}

export function SchedulePanel() {
  const { selectedStore, month, periodStart, periodEnd } = useWorkspace();
  const storeId = selectedStore.id;
  const scheduleKey = queryKeys.workSchedule(storeId, periodStart, periodEnd);
  const scheduleQuery = useQuery({ queryKey: scheduleKey, queryFn: () => getWorkSchedule(storeId, periodStart, periodEnd) });
  const settingsQuery = useQuery({ queryKey: queryKeys.employeeRatingSettings(storeId), queryFn: () => getEmployeeRatingSettings(storeId), staleTime: 2 * 60_000 });
  const [selectedDay, setSelectedDay] = useState<EtaggedResource<WorkScheduleDay> | null>(null);
  const [openingDate, setOpeningDate] = useState<string | null>(null);
  const [openingError, setOpeningError] = useState<string | null>(null);
  const [lastSavedDate, setLastSavedDate] = useState<string | null>(null);
  const dayButtonRef = useRef<HTMLButtonElement | null>(null);
  const calendar = useMemo(() => buildMonthCalendar(month), [month]);

  if (scheduleQuery.isPending || settingsQuery.isPending) return <ScheduleSkeleton />;
  if (scheduleQuery.isError || settingsQuery.isError) {
    const failed = scheduleQuery.isError ? scheduleQuery : settingsQuery;
    return <QueryError error={failed.error} onRetry={() => void Promise.all([scheduleQuery.refetch(), settingsQuery.refetch()])} />;
  }

  const shifts = scheduleQuery.data;
  const shiftsByDate = new Map<string, EmployeeShift[]>();
  for (const shift of shifts) shiftsByDate.set(shift.workDate, [...(shiftsByDate.get(shift.workDate) ?? []), shift]);
  const scheduledDays = shiftsByDate.size;
  const totalHours = shifts.reduce((total, shift) => total + shift.workedHours, 0);
  const availableSellers = settingsQuery.data.filter(isSelectableShiftSeller).length;
  const today = currentDateInTimeZone(selectedStore.timezone);

  const openDay = async (date: string, dayButton: HTMLButtonElement) => {
    dayButtonRef.current = dayButton;
    setOpeningDate(date);
    setOpeningError(null);
    try {
      const day = await getWorkScheduleDay(storeId, date);
      await scheduleQuery.refetch();
      setSelectedDay(day);
    } catch (error) {
      setOpeningError(isApiClientError(error) ? error.message : "Не удалось загрузить выбранный день.");
    } finally {
      setOpeningDate(null);
    }
  };

  return (
    <div className="schedule-panel-view">
      {openingError && <div className="form-alert" role="alert">{openingError}</div>}
      {lastSavedDate && <section className="schedule-saved-banner" role="status"><CalendarCheck2 /><span>Смены за {formatDate(lastSavedDate)} сохранены. Живой рейтинг и готовность зарплаты обновляются.</span><button type="button" onClick={() => setLastSavedDate(null)} aria-label="Скрыть уведомление"><X /></button></section>}
      <section className="schedule-summary-grid" aria-label="Сводка смен"><article><span><CalendarCheck2 /></span><div><small>Дней со сменами</small><strong>{scheduledDays}</strong><p>из {calendar.filter(Boolean).length} дней месяца</p></div></article><article><span><UsersRound /></span><div><small>Записей смен</small><strong>{shifts.length}</strong><p>{availableSellers} продавцов доступно</p></div></article><article><span><Clock3 /></span><div><small>Отработано часов</small><strong>{formatNumber(totalHours)}</strong><p>По фактическим часам графика</p></div></article></section>

      {availableSellers === 0 && <section className="plan-quality-warning"><AlertTriangle /><div><strong>Нет доступных продавцов</strong><p>Для создания смен включите нужных сотрудников в рейтинг магазина.</p></div></section>}

      <section className="panel schedule-calendar-panel">
        <div className="panel__heading"><div><p className="eyebrow">Фактически отработанные часы</p><h2>Календарь смен</h2></div><span>Выберите день, чтобы изменить полный состав</span></div>
        <div className="schedule-weekdays" aria-hidden="true">{weekDays.map((day) => <span key={day}>{day}</span>)}</div>
        <div className="schedule-calendar">{calendar.map((date, index) => {
          if (!date) return <span className="schedule-day schedule-day--empty" key={`empty-${index}`} />;
          const dayShifts = shiftsByDate.get(date) ?? [];
          const hours = dayShifts.reduce((total, shift) => total + shift.workedHours, 0);
          const dayDescription = dayShifts.length ? `${dayShifts.length} сотрудников, ${formatNumber(hours)} часов` : "нет смен";
          return <button className={`schedule-day ${date === today ? "schedule-day--today" : ""} ${dayShifts.length ? "schedule-day--filled" : ""}`} type="button" key={date} disabled={openingDate != null} onClick={(event) => void openDay(date, event.currentTarget)} aria-label={`${formatDate(date)}, ${dayDescription}`}><span><strong>{Number(date.slice(-2))}</strong>{date === today && <i>Сегодня</i>}</span>{dayShifts.length ? <><div className="schedule-day__avatars">{dayShifts.slice(0, 3).map((shift) => <i key={shift.id} title={shift.employeeName}>{shift.employeeName.slice(0, 1).toUpperCase()}</i>)}{dayShifts.length > 3 && <i>+{dayShifts.length - 3}</i>}</div><small className="schedule-day__meta"><span><b>{dayShifts.length}</b> <i>сотр.</i></span><span>{formatNumber(hours)} ч</span></small></> : <small className="schedule-day__empty-label">{openingDate === date ? "Обновляем…" : "Нет смен"}</small>}</button>;
        })}</div>
        <footer className="schedule-calendar-note"><UserRoundCheck /><span>В рейтинг попадает сотрудник, который включен в участие и имеет хотя бы одну смену. Часы используются для показателя выручки за час.</span></footer>
      </section>

      {selectedDay && <ShiftDayEditor key={`${selectedDay.value.workDate}:${selectedDay.etag}`} workDate={selectedDay.value.workDate} dayShifts={selectedDay.value.shifts} etag={selectedDay.etag} settings={settingsQuery.data} scheduleKey={scheduleKey} returnFocusRef={dayButtonRef} onClose={() => setSelectedDay(null)} onSaved={(date) => { setSelectedDay(null); setLastSavedDate(date); }} />}
    </div>
  );
}
