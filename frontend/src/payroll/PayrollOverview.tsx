import { Check, ChevronRight, CircleDollarSign, MinusCircle, ReceiptText, UsersRound, WalletCards, X } from "lucide-react";
import { useState } from "react";
import type { PayrollPlanResult, PayrollPreview, PayrollRunDetail, PayrollStatement } from "../api/contracts";
import { formatNumber, formatPercent } from "../shared/format";
import { formatPayrollMoney, summarizeStatements } from "./payroll-ui";

interface AllocationRow { employeeId: string; employeeName: string; workDate: string; workedHours: number; amount: number; }

function totalRunFund(detail: PayrollRunDetail): number | null {
  return detail.dailyPools.some((day) => day.fundAmount == null)
    ? null
    : detail.dailyPools.reduce((total, day) => total + (day.fundAmount ?? 0), 0);
}

function PlanResultCard({ title, achieved, actual, target, rate, detail }: { title: string; achieved: boolean; actual: string; target: string; rate: string; detail: string }) {
  return <article className={`payroll-plan-card ${achieved ? "payroll-plan-card--success" : "payroll-plan-card--warning"}`}><header><span>{achieved ? <Check /> : <MinusCircle />}</span><i className={`status ${achieved ? "status--success" : "status--warning"}`}>{achieved ? "Выполнен" : "Не выполнен"}</i></header><small>{title}</small><strong>{actual}</strong><p>Цель: {target}</p><footer><span>Примененная ставка</span><b>{rate}</b></footer><em>{detail}</em></article>;
}

function EmployeeBreakdown({ statement, allocations, onClose }: { statement: PayrollStatement; allocations: AllocationRow[]; onClose: () => void }) {
  const deductions = statement.penaltyAmount + statement.inventoryAmount + statement.taxAmount;
  return <div className="payroll-sheet-overlay"><aside className="payroll-sheet" role="dialog" aria-modal="true" aria-labelledby="employee-breakdown-title"><header><div><p className="eyebrow">Расшифровка выплаты</p><h2 id="employee-breakdown-title">{statement.employeeName}</h2><span>{statement.shiftCount} смен · {formatNumber(statement.workedHours)} ч</span></div><button className="icon-button" type="button" onClick={onClose} aria-label="Закрыть"><X /></button></header><div className="payroll-sheet-total"><small>К выплате</small><strong className={statement.payableAmount < 0 ? "negative-value" : ""}>{formatPayrollMoney(statement.payableAmount)}</strong></div><dl className="payroll-breakdown"><div><dt>Начислено</dt><dd>{formatPayrollMoney(statement.earnedAmount)}</dd></div><div><dt>Аванс</dt><dd>−{formatPayrollMoney(statement.advanceAmount)}</dd></div><div><dt>Штрафы</dt><dd>−{formatPayrollMoney(statement.penaltyAmount)}</dd></div><div><dt>Инвентаризация</dt><dd>−{formatPayrollMoney(statement.inventoryAmount)}</dd></div><div><dt>Налог</dt><dd>−{formatPayrollMoney(statement.taxAmount)}</dd></div><div className="payroll-breakdown__result"><dt>Всего удержано</dt><dd>−{formatPayrollMoney(statement.advanceAmount + deductions)}</dd></div></dl><section><h3>Дневные доли</h3>{allocations.length === 0 ? <p className="payroll-sheet-empty">Дневных начислений нет.</p> : <div className="payroll-allocation-list">{allocations.map((allocation) => <article key={`${allocation.workDate}-${allocation.employeeId}`}><span><strong>{new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "long", timeZone: "UTC" }).format(new Date(`${allocation.workDate}T00:00:00Z`))}</strong><small>{formatNumber(allocation.workedHours)} ч в смене</small></span><b>{formatPayrollMoney(allocation.amount)}</b></article>)}</div>}</section><footer><span>Часы сохранены для аудита, но дневной фонд делится поровну между участниками смены.</span></footer></aside></div>;
}

export function PayrollOverview({ detail, preview, onAddAdjustment }: { detail: PayrollRunDetail | null; preview: PayrollPreview | null; onAddAdjustment: () => void }) {
  const [selectedEmployeeId, setSelectedEmployeeId] = useState<string | null>(null);
  const statements = detail?.statements ?? preview?.actualScenario.employees ?? [];
  const totals = summarizeStatements(statements);
  const totalFund = detail ? totalRunFund(detail) : preview?.actualScenario.totalFundAmount ?? null;
  const plan: PayrollPlanResult | null = detail?.run.planResult ?? preview?.planResult ?? null;
  const appliedRates = preview?.actualScenario.appliedRates ?? (detail?.dailyPools[0] ? {
    accessoryPercentage: detail.dailyPools[0].accessoryPercentageRate,
    servicePercentage: detail.dailyPools[0].servicePercentageRate,
    tier1Rate: detail.dailyPools[0].tier1Rate,
    tier2Rate: detail.dailyPools[0].tier2Rate
  } : null);
  const allocations: AllocationRow[] = detail
    ? detail.dailyAllocations
    : (preview?.actualScenario.days.flatMap((day) => day.allocations.map((allocation) => ({ ...allocation, workDate: day.workDate }))) ?? []);
  const selectedStatement = statements.find((statement) => statement.employeeId === selectedEmployeeId) ?? null;

  return <div className="payroll-overview"><section className="payroll-summary-grid" aria-label="Сводка выплаты"><article><span><WalletCards /></span><small>Начислено команде</small><strong>{formatPayrollMoney(totals.earned)}</strong><p>Сумма дневных долей сотрудников</p></article><article><span><MinusCircle /></span><small>Авансы</small><strong>{formatPayrollMoney(totals.advance)}</strong><p>Применены backend по формуле</p></article><article><span><ReceiptText /></span><small>Ручные удержания</small><strong>{formatPayrollMoney(totals.deductions)}</strong><p>Штрафы, инвентаризация и налог</p></article><article className="payroll-summary-card--featured"><span><CircleDollarSign /></span><small>К выплате</small><strong>{formatPayrollMoney(totals.payable)}</strong><p>{detail ? `Зафиксировано в ревизии №${detail.run.revision}` : "Предпросмотр без записи в БД"}</p></article></section>

    {plan && <section className="panel payroll-plan-results"><div className="panel__heading"><div><p className="eyebrow">Фактические условия расчета</p><h2>Три независимых результата плана</h2></div><span>Frontend показывает ставки backend без выбора сценария</span></div><div className="payroll-plan-grid"><PlanResultCard title="Выручка" achieved={plan.revenueAchieved} actual={formatPayrollMoney(plan.actualRevenue)} target={formatPayrollMoney(plan.revenueTarget)} rate={appliedRates ? `${formatPayrollMoney(appliedRates.tier1Rate)} / ${formatPayrollMoney(appliedRates.tier2Rate)}` : "—"} detail="Техника I / II группы за единицу" /><PlanResultCard title="Аксессуары" achieved={plan.accessoryAchieved} actual={formatPercent(plan.actualAccessorySharePercent)} target={formatPercent(plan.accessoryShareTarget)} rate={appliedRates ? formatPercent(appliedRates.accessoryPercentage) : "—"} detail={`${formatPayrollMoney(plan.actualAccessoryTurnover)} оборота`} /><PlanResultCard title="Услуги" achieved={plan.serviceAchieved} actual={formatPercent(plan.actualServiceSharePercent)} target={formatPercent(plan.serviceShareTarget)} rate={appliedRates ? formatPercent(appliedRates.servicePercentage) : "—"} detail={`${formatPayrollMoney(plan.actualServiceTurnover)} оборота`} /></div><footer className="payroll-fund-note"><span>Общий дневной фонд за период</span><strong>{formatPayrollMoney(totalFund)}</strong></footer></section>}

    <section className="panel payroll-statements"><div className="panel__heading"><div><p className="eyebrow">Ведомость</p><h2>Выплаты сотрудникам</h2></div>{detail?.run.status === "CALCULATED" && <button className="button button--ghost payroll-small-button" type="button" onClick={onAddAdjustment}>Добавить удержание</button>}</div>{statements.length === 0 ? <div className="panel-empty"><UsersRound /><strong>В ведомости пока нет сотрудников</strong><p>Проверьте смены выбранного месяца и готовность расчета.</p></div> : <div className="table-scroll"><table className="payroll-table"><thead><tr><th>Сотрудник</th><th>Смены / часы</th><th>Начислено</th><th>Аванс</th><th>Удержания</th><th>К выплате</th><th><span className="sr-only">Детали</span></th></tr></thead><tbody>{statements.map((statement) => { const deductions = statement.penaltyAmount + statement.inventoryAmount + statement.taxAmount; return <tr key={statement.employeeId}><td data-label="Сотрудник"><div className="payroll-employee"><i>{statement.employeeName.slice(0, 1).toUpperCase()}</i><span><strong>{statement.employeeName}</strong><small>{formatNumber(statement.workedHours)} отработано</small></span></div></td><td data-label="Смены / часы"><strong>{statement.shiftCount}</strong><small>{formatNumber(statement.workedHours)} ч</small></td><td data-label="Начислено">{formatPayrollMoney(statement.earnedAmount)}</td><td data-label="Аванс">−{formatPayrollMoney(statement.advanceAmount)}</td><td data-label="Удержания" className={deductions > 0 ? "negative-value" : ""}>{deductions > 0 ? `−${formatPayrollMoney(deductions)}` : "—"}</td><td data-label="К выплате"><strong className={statement.payableAmount < 0 ? "negative-value" : ""}>{formatPayrollMoney(statement.payableAmount)}</strong></td><td><button className="payroll-row-button" type="button" onClick={() => setSelectedEmployeeId(statement.employeeId)} aria-label={`Открыть расчет сотрудника ${statement.employeeName}`}><ChevronRight /></button></td></tr>; })}</tbody></table></div>}</section>
    {selectedStatement && <EmployeeBreakdown statement={selectedStatement} allocations={allocations.filter((allocation) => allocation.employeeId === selectedStatement.employeeId)} onClose={() => setSelectedEmployeeId(null)} />}
  </div>;
}
