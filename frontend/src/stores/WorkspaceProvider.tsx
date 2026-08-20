import { useQuery } from "@tanstack/react-query";
import { createContext, useContext, useEffect, type ReactNode } from "react";
import { useSearchParams } from "react-router";
import type { StoreSummary } from "../api/contracts";
import { getStores, getStoreStatus, queryKeys } from "../api/queries";
import {
  completedDataThroughDate,
  currentDateInTimeZone,
  effectiveMonthRange,
  formatDateShort,
  formatMonth,
  inclusiveDayCount,
  isIsoDate,
  monthFromDate,
  monthRange,
  weekRange
} from "../shared/date";

export type AnalyticsPeriodMode = "MONTH" | "WEEK" | "CUSTOM";

interface AnalyticsPeriodInput {
  mode: AnalyticsPeriodMode;
  start: string;
  end: string;
}

interface WorkspaceContextValue {
  stores: StoreSummary[];
  selectedStore: StoreSummary;
  month: string;
  periodMode: AnalyticsPeriodMode;
  periodStart: string;
  periodEnd: string;
  periodLabel: string;
  asOfDate: string;
  dataThroughDate: string | null;
  completedThroughDate: string;
  currentMonth: string;
  today: string;
  selectStore: (storeId: string) => void;
  selectMonth: (month: string) => void;
  selectAnalyticsPeriod: (period: AnalyticsPeriodInput) => void;
}

const WorkspaceContext = createContext<WorkspaceContextValue | null>(null);

function updateSearchParams(current: URLSearchParams, values: Record<string, string | null>): URLSearchParams {
  const next = new URLSearchParams(current);
  for (const [key, value] of Object.entries(values)) {
    if (value == null) next.delete(key);
    else next.set(key, value);
  }
  return next;
}

function validCustomRange(start: string | null, end: string | null, today: string): start is string {
  return isIsoDate(start) && isIsoDate(end) && start <= end && end <= today && inclusiveDayCount(start, end) <= 366;
}

function periodLabel(mode: AnalyticsPeriodMode, start: string, end: string, month: string): string {
  if (mode === "MONTH") {
    const label = formatMonth(month);
    return end < monthRange(month).end ? `${label} · по ${formatDateShort(end)}` : label;
  }
  if (mode === "WEEK") return `${formatDateShort(start)} — ${formatDateShort(end)}`;
  return `${formatDateShort(start)} — ${formatDateShort(end)}`;
}

export function WorkspaceProvider({
  children,
  emptyState
}: {
  children: ReactNode;
  emptyState?: ReactNode;
}) {
  const [searchParams, setSearchParams] = useSearchParams();
  const storesQuery = useQuery({ queryKey: queryKeys.stores, queryFn: getStores, staleTime: 5 * 60_000 });

  const stores = storesQuery.data ?? [];
  const requestedStoreId = searchParams.get("store");
  const selectedStore = stores.find((store) => store.id === requestedStoreId) ?? stores[0];
  const statusQuery = useQuery({
    queryKey: selectedStore
      ? queryKeys.storeStatus(selectedStore.id)
      : ["stores", "unselected", "status"],
    queryFn: () => getStoreStatus(selectedStore!.id),
    enabled: Boolean(selectedStore),
    staleTime: 60_000
  });
  const today = selectedStore ? currentDateInTimeZone(selectedStore.timezone) : "";
  const currentMonth = today ? monthFromDate(today) : "";
  const requestedMonth = searchParams.get("month");
  const month = requestedMonth && /^\d{4}-\d{2}$/u.test(requestedMonth) && requestedMonth <= currentMonth
    ? requestedMonth
    : currentMonth;

  const requestedMode = searchParams.get("range");
  const requestedStart = searchParams.get("periodStart");
  const requestedEnd = searchParams.get("periodEnd");
  let periodMode: AnalyticsPeriodMode = "MONTH";
  const dataThroughDate = statusQuery.data?.dataThroughDate ?? null;
  const completedThroughDate = today
    ? completedDataThroughDate(today, dataThroughDate)
    : "";
  let { start: periodStart, end: periodEnd } = month && today
    ? effectiveMonthRange(month, today, dataThroughDate)
    : { start: "", end: "" };

  if (today && requestedMode === "WEEK" && isIsoDate(requestedStart)) {
    const range = weekRange(requestedStart);
    periodMode = "WEEK";
    periodStart = range.start;
    periodEnd = range.end > today ? today : range.end;
  } else if (today && requestedMode === "CUSTOM" && validCustomRange(requestedStart, requestedEnd, today)) {
    periodMode = "CUSTOM";
    periodStart = requestedStart;
    periodEnd = requestedEnd as string;
  }

  useEffect(() => {
    if (!selectedStore || !month) return;
    if (requestedStoreId !== selectedStore.id || requestedMonth !== month) {
      setSearchParams((current) => updateSearchParams(current, { store: selectedStore.id, month }), { replace: true });
    }
  }, [month, requestedMonth, requestedStoreId, selectedStore, setSearchParams]);

  if (storesQuery.isPending) return <main className="workspace-state" aria-live="polite"><span className="spinner" /><p>Загружаем доступные магазины…</p></main>;
  if (storesQuery.isError) return <main className="workspace-state"><h1>Не удалось загрузить магазины</h1><p>Проверьте соединение или доступ учетной записи.</p><button className="button button--primary" type="button" onClick={() => void storesQuery.refetch()}>Повторить</button></main>;
  if (!selectedStore || !month) return emptyState ?? (
    <main className="workspace-state"><h1>Нет доступных магазинов</h1>
      <p>Администратор еще не назначил вам активный магазин.</p></main>
  );

  const value: WorkspaceContextValue = {
    stores,
    selectedStore,
    month,
    periodMode,
    periodStart,
    periodEnd,
    periodLabel: periodLabel(periodMode, periodStart, periodEnd, month),
    asOfDate: effectiveMonthRange(month, today, dataThroughDate).end,
    dataThroughDate,
    completedThroughDate,
    currentMonth,
    today,
    selectStore: (storeId) => setSearchParams((current) => updateSearchParams(current, { store: storeId })),
    selectMonth: (nextMonth) => {
      if (/^\d{4}-\d{2}$/u.test(nextMonth) && nextMonth <= currentMonth) {
        setSearchParams((current) => updateSearchParams(current, { month: nextMonth, range: null, periodStart: null, periodEnd: null }));
      }
    },
    selectAnalyticsPeriod: ({ mode, start, end }) => {
      if (mode === "MONTH") {
        const nextMonth = monthFromDate(start);
        if (nextMonth <= currentMonth) setSearchParams((current) => updateSearchParams(current, { month: nextMonth, range: null, periodStart: null, periodEnd: null }));
        return;
      }
      if (mode === "WEEK" && isIsoDate(start) && start <= today) {
        setSearchParams((current) => updateSearchParams(current, { range: "WEEK", periodStart: weekRange(start).start, periodEnd: null }));
        return;
      }
      if (mode === "CUSTOM" && validCustomRange(start, end, today)) {
        setSearchParams((current) => updateSearchParams(current, { range: "CUSTOM", periodStart: start, periodEnd: end }));
      }
    }
  };

  return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>;
}

export function useWorkspace(): WorkspaceContextValue {
  const context = useContext(WorkspaceContext);
  if (!context) throw new Error("useWorkspace must be used within WorkspaceProvider");
  return context;
}
