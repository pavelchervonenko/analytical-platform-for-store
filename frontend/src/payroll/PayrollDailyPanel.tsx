import { AlertTriangle, CalendarDays, CheckCircle2, UsersRound } from "lucide-react";
import type { PayrollPreview, PayrollRunDetail } from "../api/contracts";
import { formatDate } from "../shared/date";
import { formatNumber, formatPercent } from "../shared/format";
import { formatPayrollMoney } from "./payroll-ui";

interface DailyView {
  key: string;
  workDate: string;
  fundAmount: number | null;
  shiftEmployeeCount: number;
  calculationComplete: boolean;
  accessoryReward: number;
  serviceReward: number;
  playstationReward: number | null;
  paidRepairReward: number | null;
  tier1Reward: number;
  tier2Reward: number;
  accessoryTurnover: number;
  serviceTurnover: number;
  tier1Quantity: number;
  tier2Quantity: number;
  accessoryRate?: number;
  serviceRate?: number;
  allocations: { employeeId: string; employeeName: string; workedHours: number; amount: number }[];
}

function addNullable(left: number | null, right: number | null): number | null {
  return left == null || right == null ? null : left + right;
}

function dailyViews(detail: PayrollRunDetail | null, preview: PayrollPreview | null): DailyView[] {
  if (detail) return detail.dailyPools.map((day) => ({ ...day, key: day.id, accessoryRate: day.accessoryPercentageRate, serviceRate: day.servicePercentageRate, allocations: detail.dailyAllocations.filter((allocation) => allocation.workDate === day.workDate) }));
  return (preview?.actualScenario.days ?? []).map((day) => ({ ...day, key: day.workDate, accessoryRate: preview?.actualScenario.appliedRates.accessoryPercentage, serviceRate: preview?.actualScenario.appliedRates.servicePercentage }));
}

export function PayrollDailyPanel({ detail, preview }: { detail: PayrollRunDetail | null; preview: PayrollPreview | null }) {
  const days = dailyViews(detail, preview);
  const completeDays = days.filter((day) => day.calculationComplete).length;
  const daysWithFund = days.filter((day) => day.fundAmount != null && day.fundAmount !== 0).length;
  return <div className="payroll-daily-view"><section className="payroll-daily-summary"><article><CalendarDays /><span><small>Дней в расчете</small><strong>{days.length}</strong></span></article><article><CheckCircle2 /><span><small>Полностью рассчитано</small><strong>{completeDays}</strong></span></article><article><UsersRound /><span><small>Дней с фондом</small><strong>{daysWithFund}</strong></span></article></section><section className="panel payroll-days-panel"><div className="panel__heading"><h2>Расчет по дням</h2><span>Часы показываются для контроля, доли участников равны</span></div>{days.length === 0 ? <div className="panel-empty"><CalendarDays /><strong>Нет дневных данных</strong><p>В выбранном месяце отсутствуют продажи, формирующие зарплатный фонд.</p></div> : <div className="payroll-day-list">{days.map((day) => <details key={day.key} className={!day.calculationComplete ? "payroll-day--warning" : ""}><summary><span className="payroll-day-status">{day.calculationComplete ? <CheckCircle2 /> : <AlertTriangle />}</span><span><strong>{formatDate(day.workDate)}</strong><small>{day.shiftEmployeeCount} сотрудников в смене</small></span><b>{formatPayrollMoney(day.fundAmount)}</b><i>Подробнее</i></summary><div className="payroll-day-detail"><div className="payroll-reward-grid"><article><small>Аксессуары, {formatPercent(day.accessoryRate)}</small><strong>{formatPayrollMoney(day.accessoryReward)}</strong><span>оборот {formatPayrollMoney(day.accessoryTurnover)}</span></article><article><small>Услуги, {formatPercent(day.serviceRate)}</small><strong>{formatPayrollMoney(day.serviceReward)}</strong><span>оборот {formatPayrollMoney(day.serviceTurnover)}</span></article><article><small>PlayStation / ремонт</small><strong>{formatPayrollMoney(addNullable(day.playstationReward, day.paidRepairReward))}</strong><span>валовая прибыль по данным системы</span></article><article><small>Категории 1 и 2</small><strong>{formatPayrollMoney(day.tier1Reward + day.tier2Reward)}</strong><span>{formatNumber(day.tier1Quantity)} / {formatNumber(day.tier2Quantity)} шт.</span></article></div><section className="payroll-day-allocations"><h3>Распределение фонда</h3>{day.allocations.length === 0 ? <p>В этот день нет участников смены.</p> : day.allocations.map((allocation) => <article key={allocation.employeeId}><span><strong>{allocation.employeeName}</strong><small>{formatNumber(allocation.workedHours)} ч</small></span><b>{formatPayrollMoney(allocation.amount)}</b></article>)}</section>{!day.calculationComplete && <aside><AlertTriangle /><span>День рассчитан не полностью. Проверьте классификацию, себестоимость и состав смены.</span></aside>}</div></details>)}</div>}</section></div>;
}
