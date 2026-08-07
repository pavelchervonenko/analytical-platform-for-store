import { useQuery } from "@tanstack/react-query";
import { Archive, CalendarDays, CheckCircle2, FileText, History, ShieldCheck } from "lucide-react";
import { useState } from "react";
import type { AnnualReportPayload, MonthlyReportPayload, ReportSummary, ReportType } from "../api/contracts";
import { getReport, getReports, getReportYears, queryKeys } from "../api/queries";
import { formatDate } from "../shared/date";
import { formatMoney, formatNumber, formatPercent } from "../shared/format";
import { QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import "./styles.css";

const typeLabels: Record<ReportType | "UNKNOWN", string> = { MONTHLY: "Месячный", ANNUAL: "Годовой", UNKNOWN: "Неизвестный" };
const directionLabels: Readonly<Record<string, string>> = {
  REVENUE: "Выручка",
  ACCESSORY: "Аксессуары",
  SERVICE: "Услуги",
  ADDITIONAL: "Дополнительная выручка"
};

function periodLabel(report: ReportSummary): string {
  if (report.type === "ANNUAL") {
    return report.coverage === "PARTIAL_FIRST_YEAR"
      ? `${report.periodStart.slice(0, 7)} — ${report.periodEnd.slice(0, 7)}`
      : report.periodEnd.slice(0, 4);
  }
  if (report.type === "UNKNOWN") {
    return `${formatDate(report.periodStart)} — ${formatDate(report.periodEnd)}`;
  }
  return new Date(`${report.periodStart}T00:00:00`).toLocaleDateString("ru-RU", {
    month: "long", year: "numeric"
  });
}

function ArchiveItem({ report, active, select }: {
  report: ReportSummary; active: boolean; select: () => void;
}) {
  return <button className={`report-archive-item ${active ? "report-archive-item--active" : ""}`} type="button" onClick={select}>
    <span className="report-archive-item__icon">{report.type === "ANNUAL" ? <Archive size={18} /> : <FileText size={18} />}</span>
    <span><strong>{periodLabel(report)}</strong><small>{typeLabels[report.type]} отчет</small></span>
    <i className={report.currentRevision ? "status status--success" : "status"}>{report.currentRevision ? "Актуальная" : "История"}</i>
  </button>;
}

function Provenance({ report }: { report: ReportSummary }) {
  return <section className="report-provenance">
    <span><ShieldCheck size={18} /></span>
    <div><strong>Сохраненный отчет</strong><p>Сохранен {new Date(report.finalizedAt).toLocaleString("ru-RU")}{report.finalizedBy ? `, ${report.finalizedBy.displayName}` : ", автоматически"}</p></div>
  </section>;
}

function SummaryCards({ revenue, grossProfit, margin, payroll, quantity }: {
  revenue: number; grossProfit: number | null; margin: number | null; payroll: number; quantity: number;
}) {
  const values = [
    ["Чистая выручка", formatMoney(revenue)],
    ["Валовая прибыль", formatMoney(grossProfit)],
    ["Маржинальность", formatPercent(margin)],
    ["К выплате сотрудникам", formatMoney(payroll)],
    ["Чистое количество", formatNumber(quantity)]
  ];
  return <section className="report-metric-grid">{values.map(([label, value]) => <article key={label}><small>{label}</small><strong>{value}</strong></article>)}</section>;
}

function EmployeeTable({ rows }: {
  rows: Array<{ id: string; name: string; rank?: number | null; shifts: number; hours: number; revenue: number; earned: number; payable: number }>;
}) {
  return <div className="table-scroll"><table><thead><tr><th>Сотрудник</th><th>Место</th><th>Смены</th><th>Часы</th><th>Выручка</th><th>Начислено</th><th>К выплате</th></tr></thead><tbody>
    {rows.map((row) => <tr key={row.id}><td><strong>{row.name}</strong></td><td>{row.rank ?? "—"}</td><td>{row.shifts}</td><td>{formatNumber(row.hours)}</td><td>{formatMoney(row.revenue)}</td><td>{formatMoney(row.earned)}</td><td><strong>{formatMoney(row.payable)}</strong></td></tr>)}
  </tbody></table></div>;
}

function MonthlyView({ report }: { report: MonthlyReportPayload }) {
  const payable = report.payroll.statements.reduce((sum, item) => sum + item.payableAmount, 0);
  const employees = report.payroll.statements.map((statement) => {
    const rating = report.employeeRating.employees.find((item) => item.employeeId === statement.employeeId);
    return { id: statement.employeeId, name: statement.employeeName, rank: rating?.rank, shifts: statement.shiftCount, hours: statement.workedHours, revenue: rating?.netRevenue ?? 0, earned: statement.earnedAmount, payable: statement.payableAmount };
  });
  return <>
    <SummaryCards revenue={report.storeKpi.netRevenue} grossProfit={report.storeKpi.grossProfit} margin={report.storeKpi.marginPercent} payroll={payable} quantity={report.storeKpi.netQuantity} />
    <div className="report-detail-grid">
      <section className="panel"><div className="panel__heading"><div><p className="eyebrow">План магазина</p><h2>Итог месяца</h2></div><span>{report.planProgress.achievedDirectionCount} из {report.planProgress.directions.length}</span></div>
        <div className="report-plan-list">{report.planProgress.directions.map((item) => <div key={item.code}><span><strong>{directionLabels[item.code] ?? "Другое направление"}</strong><small>{formatMoney(item.actualAmount)} из {formatMoney(item.targetAmount)}</small></span><i className={`status ${item.achieved ? "status--success" : "status--warning"}`}>{item.achieved ? "Выполнен" : "Не выполнен"}</i></div>)}</div>
      </section>
      <section className="panel"><div className="panel__heading"><div><p className="eyebrow">Средние показатели</p><h2>Контекст отчета</h2></div></div>
        <dl className="report-definition-list"><div><dt>Средний чек</dt><dd>{formatMoney(report.averageKpi.averageReceipt.value)}</dd></div><div><dt>Доп. выручка на телефон</dt><dd>{formatMoney(report.averageKpi.additionalRevenuePerPhone.value)}</dd></div><div><dt>Сотрудников</dt><dd>{employees.length}</dd></div><div><dt>Замечаний качества</dt><dd>{report.quality.issues.length}</dd></div></dl>
      </section>
    </div>
    <section className="panel report-table-panel"><div className="panel__heading"><div><p className="eyebrow">Сотрудники</p><h2>Результаты и выплата</h2></div><span>{employees.length}</span></div><EmployeeTable rows={employees} /></section>
    <section className="panel report-table-panel"><div className="panel__heading"><div><p className="eyebrow">Категории</p><h2>Подробные показатели</h2></div><span>{report.categoryKpi.categories.length}</span></div>
      <div className="table-scroll"><table><thead><tr><th>Категория</th><th>Выручка</th><th>Количество</th><th>Валовая прибыль</th><th>Маржа</th></tr></thead><tbody>{report.categoryKpi.categories.map((item) => <tr key={item.categoryCode}><td><strong>{item.categoryName}</strong></td><td>{formatMoney(item.metrics.netRevenue)}</td><td>{formatNumber(item.metrics.netQuantity)}</td><td>{formatMoney(item.metrics.grossProfit)}</td><td>{formatPercent(item.metrics.marginPercent)}</td></tr>)}</tbody></table></div>
    </section>
  </>;
}

function AnnualView({ report, openMonth }: { report: AnnualReportPayload; openMonth: (id: string) => void }) {
  const employees = report.employees.map((item) => ({ id: item.employeeId, name: item.employeeName, shifts: item.shiftCount, hours: item.workedHours, revenue: item.netRevenue, earned: item.earnedAmount, payable: item.payableAmount }));
  return <>
    <SummaryCards revenue={report.totals.netRevenue} grossProfit={report.totals.grossProfit} margin={report.totals.marginPercent} payroll={report.totals.payrollPayableAmount} quantity={report.totals.netQuantity} />
    <section className="panel report-months"><div className="panel__heading"><div><p className="eyebrow">Состав годового отчета</p><h2>Зафиксированные месяцы</h2></div><span>{report.totals.monthCount}</span></div>
      <div className="report-month-grid">{report.months.map((month) => <button key={month.snapshotId} type="button" onClick={() => openMonth(month.snapshotId)}><CalendarDays size={17} /><span><strong>{new Date(`${month.report.header.periodStart}T00:00:00`).toLocaleDateString("ru-RU", { month: "long" })}</strong><small>Версия {month.revision}, {formatMoney(month.report.storeKpi.netRevenue)}</small></span></button>)}</div>
    </section>
    <section className="panel report-table-panel"><div className="panel__heading"><div><p className="eyebrow">Сотрудники за год</p><h2>Накопленные фактические показатели</h2></div><span>{employees.length}</span></div><EmployeeTable rows={employees} /></section>
    <section className="panel report-table-panel"><div className="panel__heading"><div><p className="eyebrow">Категории за год</p><h2>Накопленная структура</h2></div><span>{report.categories.length}</span></div>
      <div className="table-scroll"><table><thead><tr><th>Категория</th><th>Выручка</th><th>Количество</th><th>Валовая прибыль</th><th>Маржа</th></tr></thead><tbody>{report.categories.map((item) => <tr key={item.categoryCode}><td><strong>{item.categoryName}</strong></td><td>{formatMoney(item.netRevenue)}</td><td>{formatNumber(item.netQuantity)}</td><td>{formatMoney(item.grossProfit)}</td><td>{formatPercent(item.marginPercent)}</td></tr>)}</tbody></table></div>
    </section>
  </>;
}

function ReportsPageContent() {
  const { selectedStore } = useWorkspace();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [type, setType] = useState<ReportType | "ALL">("ALL");
  const [year, setYear] = useState<number | "ALL">("ALL");
  const [page, setPage] = useState(0);
  const requestedYear = year === "ALL" ? undefined : year;
  const requestedType = type === "ALL" ? undefined : type;
  const yearsQuery = useQuery({
    queryKey: queryKeys.reportYears(selectedStore.id),
    queryFn: () => getReportYears(selectedStore.id)
  });
  const archive = useQuery({
    queryKey: queryKeys.reports(selectedStore.id, requestedYear, requestedType, page),
    queryFn: () => getReports(selectedStore.id, requestedYear, requestedType, page)
  });
  const reports = archive.data?.items ?? [];
  const years = yearsQuery.data ?? [];
  const effectiveId = selectedId ?? reports[0]?.id ?? null;
  const detail = useQuery({ queryKey: queryKeys.report(selectedStore.id, effectiveId ?? "none"), queryFn: () => getReport(selectedStore.id, effectiveId as string), enabled: effectiveId != null });

  if (archive.error) return <QueryError error={archive.error} onRetry={() => void archive.refetch()} />;
  return <div className="reports-page">
    <header className="page-heading"><h1>Отчеты</h1></header>
    <div className="reports-layout">
      <aside className="panel report-archive"><div className="panel__heading"><div><p className="eyebrow">Архив</p><h2>Сохраненные отчеты</h2></div><span>{archive.data?.totalElements ?? 0}</span></div>
        <div className="report-filters"><label>Тип<select aria-label="Тип" value={type} onChange={(event) => { setType(event.target.value as ReportType | "ALL"); setSelectedId(null); setPage(0); }}><option value="ALL">Все</option><option value="MONTHLY">Месячные</option><option value="ANNUAL">Годовые</option></select></label><label>Год<select aria-label="Год" value={year} onChange={(event) => { setYear(event.target.value === "ALL" ? "ALL" : Number(event.target.value)); setSelectedId(null); setPage(0); }}><option value="ALL">Все</option>{years.map((item) => <option value={item} key={item}>{item}</option>)}</select></label></div>
        {archive.isPending ? <div className="report-loading"><span className="spinner" />Загружаем архив…</div> : reports.length === 0 ? <div className="panel-empty"><Archive /><strong>Отчетов пока нет</strong></div> : <><div className="report-archive-list">{reports.map((item) => <ArchiveItem key={item.id} report={item} active={item.id === effectiveId} select={() => setSelectedId(item.id)} />)}</div><footer className="report-pagination"><button className="button button--ghost" type="button" disabled={!archive.data?.hasPrevious} onClick={() => { setSelectedId(null); setPage((value) => Math.max(0, value - 1)); }}>Назад</button><span>{page + 1} из {archive.data?.totalPages ?? 1}</span><button className="button button--ghost" type="button" disabled={!archive.data?.hasNext} onClick={() => { setSelectedId(null); setPage((value) => value + 1); }}>Далее</button></footer></>}
      </aside>
      <main className="report-view">
        {detail.isPending && effectiveId && <div className="panel report-loading"><span className="spinner" />Загружаем отчет…</div>}
        {detail.error && <QueryError error={detail.error} onRetry={() => void detail.refetch()} />}
        {detail.data && <><header className="report-title"><div><span className="report-title__icon">{detail.data.report.type === "ANNUAL" ? <Archive /> : <FileText />}</span><div><p className="eyebrow">{typeLabels[detail.data.report.type]} отчет</p><h2>{periodLabel(detail.data.report)}</h2><p>{detail.data.report.coverage === "PARTIAL_FIRST_YEAR" ? "Частичный первый календарный год" : `${formatDate(detail.data.report.periodStart)} — ${formatDate(detail.data.report.periodEnd)}`}</p></div></div>{detail.data.report.revisionReason && <span className="report-revision-reason"><History size={15} />{detail.data.report.revisionReason}</span>}</header><Provenance report={detail.data.report} />{detail.data.monthly && <MonthlyView report={detail.data.monthly} />}{detail.data.annual && <AnnualView report={detail.data.annual} openMonth={(id) => { setType("MONTHLY"); setYear("ALL"); setSelectedId(id); }} />}</>}
        {!effectiveId && !archive.isPending && <section className="panel report-welcome"><CheckCircle2 size={30} /><h2>Архив готов к накоплению</h2></section>}
      </main>
    </div>
  </div>;
}

export function ReportsPage() {
  const { selectedStore } = useWorkspace();
  return <ReportsPageContent key={selectedStore.id} />;
}
