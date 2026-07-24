import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, Ban, CheckCircle2, GitCompareArrows, History, ReceiptText } from "lucide-react";
import type { PayrollAdjustment, PayrollRunDetail } from "../api/contracts";
import { comparePayrollRevisions, getPayrollRuns, queryKeys } from "../api/queries";
import { formatDate } from "../shared/date";
import { PanelSkeleton, QueryError } from "../shared/QueryState";
import { adjustmentTypeLabel, comparisonReasonLabel, formatPayrollMoney, payrollEventLabel } from "./payroll-ui";

function formatInstant(value: string, timeZone: string): string {
  return new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium", timeStyle: "short", timeZone }).format(new Date(value));
}

function statusLabel(status: string): string {
  if (status === "CALCULATED") return "Черновик";
  if (status === "APPROVED") return "Утверждён";
  if (status === "PAID") return "Выплачен";
  return "Неизвестный статус";
}

export function PayrollAuditPanel({ storeId, month, timeZone, detail, onVoidAdjustment }: { storeId: string; month: string; timeZone: string; detail: PayrollRunDetail | null; onVoidAdjustment: (adjustment: PayrollAdjustment) => void }) {
  const runsQuery = useQuery({ queryKey: queryKeys.payrollRuns(storeId), queryFn: () => getPayrollRuns(storeId) });
  const previousId = detail?.run.supersedesRunId ?? null;
  const currentId = detail?.run.id ?? null;
  const comparisonQuery = useQuery({
    queryKey: queryKeys.payrollComparison(storeId, previousId ?? "none", currentId ?? "none"),
    queryFn: () => comparePayrollRevisions(storeId, previousId!, currentId!),
    enabled: Boolean(previousId && currentId)
  });

  if (!detail) return <section className="panel"><div className="panel-empty"><History /><strong>Аудит появится после создания расчёта</strong><p>Предпросмотр ничего не сохраняет и не создаёт ревизию.</p></div></section>;
  const monthRuns = (runsQuery.data ?? []).filter((run) => run.periodMonth.startsWith(month));
  return <div className="payroll-audit-grid"><section className="panel payroll-adjustments-panel"><div className="panel__heading"><div><p className="eyebrow">Ручные изменения</p><h2>Удержания</h2></div><span>{detail.adjustments.filter((adjustment) => adjustment.active).length} активных</span></div>{detail.adjustments.length === 0 ? <div className="payroll-inline-empty"><ReceiptText /><span><strong>Удержаний нет</strong><small>Ведомость рассчитана без ручных вычетов.</small></span></div> : <div className="payroll-adjustment-list">{detail.adjustments.map((adjustment) => <article className={!adjustment.active ? "payroll-adjustment--voided" : ""} key={adjustment.id}><header><span><strong>{adjustment.employeeName}</strong><small>{adjustmentTypeLabel(adjustment.type)}</small></span><b>−{formatPayrollMoney(adjustment.amount)}</b></header><p>{adjustment.reason}</p><footer><span>{formatInstant(adjustment.createdAt, timeZone)}</span>{adjustment.active && detail.run.status === "CALCULATED" ? <button type="button" onClick={() => onVoidAdjustment(adjustment)}><Ban />Отменить</button> : <i>{adjustment.active ? "Зафиксировано" : `Отменено${adjustment.voidedAt ? ` · ${formatInstant(adjustment.voidedAt, timeZone)}` : ""}`}</i>}</footer>{!adjustment.active && adjustment.voidReason && <aside>Причина отмены: {adjustment.voidReason}</aside>}</article>)}</div>}</section>

    <section className="panel payroll-events-panel"><div className="panel__heading"><div><p className="eyebrow">Неизменяемая история</p><h2>События ревизии</h2></div><span>Ревизия №{detail.run.revision}</span></div><ol className="payroll-event-list">{detail.events.map((event) => <li key={event.id}><span><CheckCircle2 /></span><div><strong>{payrollEventLabel(event.type)}</strong><small>{formatInstant(event.createdAt, timeZone)}</small></div></li>)}</ol></section>

    <section className="panel payroll-revisions-panel"><div className="panel__heading"><div><p className="eyebrow">История месяца</p><h2>Ревизии расчёта</h2></div><span>Старые ревизии доступны только для чтения</span></div>{runsQuery.isPending ? <PanelSkeleton rows={3} /> : runsQuery.isError ? <QueryError compact error={runsQuery.error} onRetry={() => void runsQuery.refetch()} /> : <div className="payroll-revision-list">{monthRuns.map((run) => <article className={run.id === detail.run.id ? "payroll-revision--current" : ""} key={run.id}><span>№{run.revision}</span><div><strong>{statusLabel(run.status)}</strong><small>{run.revisionReason ?? (run.revision === 1 ? "Первичный расчёт" : "Причина не указана")}</small></div><time>{formatInstant(run.createdAt, timeZone)}</time>{run.id === detail.run.id && <i>Текущая</i>}</article>)}</div>}</section>

    <section className="panel payroll-comparison-panel"><div className="panel__heading"><div><p className="eyebrow">Контроль изменений</p><h2>Сравнение с предыдущей ревизией</h2></div><GitCompareArrows /></div>{!previousId ? <div className="payroll-inline-empty"><GitCompareArrows /><span><strong>Это первая ревизия</strong><small>Сравнение появится после создания следующей.</small></span></div> : comparisonQuery.isPending ? <PanelSkeleton rows={4} /> : comparisonQuery.isError ? <QueryError compact error={comparisonQuery.error} onRetry={() => void comparisonQuery.refetch()} /> : comparisonQuery.data && <><div className="payroll-comparison-totals"><article><small>Фонд</small><strong>{formatPayrollMoney(comparisonQuery.data.currentTotalFund)}</strong><span className={(comparisonQuery.data.totalFundChange ?? 0) < 0 ? "negative-value" : ""}>{formatPayrollMoney(comparisonQuery.data.totalFundChange, true)}</span></article><article><small>К выплате</small><strong>{formatPayrollMoney(comparisonQuery.data.currentTotalPayable)}</strong><span className={comparisonQuery.data.totalPayableChange < 0 ? "negative-value" : ""}>{formatPayrollMoney(comparisonQuery.data.totalPayableChange, true)}</span></article></div>{(comparisonQuery.data.revenuePlanStatusChanged || comparisonQuery.data.accessoryPlanStatusChanged || comparisonQuery.data.servicePlanStatusChanged || comparisonQuery.data.schemeChanged) && <aside className="payroll-comparison-warning"><AlertTriangle /><span>Между ревизиями изменились статусы плана или версия формулы.</span></aside>}<div className="payroll-change-groups"><details open><summary>Сотрудники <span>{comparisonQuery.data.employeeChanges.length}</span></summary>{comparisonQuery.data.employeeChanges.length === 0 ? <p>Изменений по сотрудникам нет.</p> : comparisonQuery.data.employeeChanges.map((change) => <article key={change.employeeId}><span><strong>{change.employeeName}</strong><small>{change.reasons.map(comparisonReasonLabel).join(" · ")}</small></span><b className={change.payableChange < 0 ? "negative-value" : ""}>{formatPayrollMoney(change.payableChange, true)}</b></article>)}</details><details><summary>Дни <span>{comparisonQuery.data.dayChanges.length}</span></summary>{comparisonQuery.data.dayChanges.length === 0 ? <p>Изменений по дням нет.</p> : comparisonQuery.data.dayChanges.map((change) => <article key={change.workDate}><span><strong>{formatDate(change.workDate)}</strong><small>{change.reasons.map(comparisonReasonLabel).join(" · ")}</small></span><b className={(change.fundChange ?? 0) < 0 ? "negative-value" : ""}>{formatPayrollMoney(change.fundChange, true)}</b></article>)}</details></div></>}</section>
  </div>;
}
