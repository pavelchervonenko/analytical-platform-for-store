import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Calculator, CalendarDays, Check, CheckCircle2, CircleDollarSign, FileClock, History, Plus, ReceiptText, RefreshCw, ShieldCheck, X } from "lucide-react";
import { Link, useSearchParams } from "react-router";
import { useState } from "react";
import { isApiClientError } from "../api/client";
import type { PayrollAdjustment, PayrollAdjustmentInput, PayrollRunDetail } from "../api/contracts";
import { addPayrollAdjustment, approvePayroll, calculatePayroll, getEmployeeRatingSettings, getLatestPayroll, getPayrollPreview, getPayrollReadiness, markPayrollPaid, queryKeys, voidPayrollAdjustment } from "../api/queries";
import { useAuth } from "../auth/AuthProvider";
import { QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { PayrollAuditPanel } from "./PayrollAuditPanel";
import { PayrollDailyPanel } from "./PayrollDailyPanel";
import { AdjustmentDialog, ConfirmPayrollDialog, ReasonDialog } from "./PayrollDialogs";
import { PayrollOverview } from "./PayrollOverview";
import { staleReasonLabel } from "./payroll-ui";
import "./styles.css";

type PayrollSection = "statement" | "days" | "audit";
type DialogState = { type: "adjustment" } | { type: "revision" } | { type: "void"; adjustment: PayrollAdjustment } | { type: "approve" } | { type: "paid" } | null;

function payrollErrorMessage(error: unknown): string {
  if (isApiClientError(error)) {
    if (error.code === "PAYROLL_SOURCE_DATA_CHANGED") return "Исходные данные изменились. Расчет обновлен для проверки — выполните явный перерасчет.";
    if (error.status === 409) return "Ревизия уже изменилась. Данные перечитаны; проверьте актуальную версию и повторите действие.";
    if (error.status === 403) return "Недостаточно прав для изменения этого расчета.";
    if (error.status === 400) return "Backend отклонил данные. Проверьте заполненные поля.";
  }
  return "Не удалось выполнить действие. Обновите данные и повторите попытку.";
}

function statusLabel(status: string): string {
  if (status === "CALCULATED") return "Черновик рассчитан";
  if (status === "APPROVED") return "Расчет утвержден";
  if (status === "PAID") return "Выплата отмечена";
  return "Статус неизвестен";
}

function Workflow({ canCalculate, run }: { canCalculate: boolean; run: PayrollRunDetail["run"] | null }) {
  const approved = run?.status === "APPROVED" || run?.status === "PAID";
  const paid = run?.status === "PAID";
  const steps = [
    { title: "Готовность", caption: canCalculate ? "Проверено" : "Нужны данные", complete: canCalculate, active: !canCalculate },
    { title: "Предпросмотр", caption: canCalculate ? "Доступен" : "Ожидает", complete: Boolean(run), active: canCalculate && !run },
    { title: "Расчет", caption: run ? `Ревизия №${run.revision}` : "Не создан", complete: Boolean(run), active: run?.status === "CALCULATED" },
    { title: "Утверждение", caption: approved ? "Зафиксировано" : "Ожидает", complete: approved, active: run?.status === "CALCULATED" },
    { title: "Выплата", caption: paid ? "Завершено" : "Ожидает", complete: paid, active: run?.status === "APPROVED" }
  ];
  return <ol className="payroll-workflow" aria-label="Этапы расчета зарплаты">{steps.map((step, index) => <li className={`${step.complete ? "complete" : ""} ${step.active ? "active" : ""}`} key={step.title}><span>{step.complete ? <Check /> : index + 1}</span><div><strong>{step.title}</strong><small>{step.caption}</small></div></li>)}</ol>;
}

function ReadinessBanner({ readiness, run, search, isAdmin }: { readiness: Awaited<ReturnType<typeof getPayrollReadiness>>; run: PayrollRunDetail["run"] | null; search: string; isAdmin: boolean }) {
  const freshnessProblem = run?.freshness.requiresRecalculation;
  const issueCount = readiness.unmappedItemCount + readiness.missingCostItemCount + readiness.daysWithoutShift + Number(!readiness.planPresent) + Number(!readiness.schemePresent) + Number(Boolean(freshnessProblem));
  const tone = !readiness.canCalculate ? "danger" : freshnessProblem || !readiness.canApprove ? "warning" : "success";
  const title = !readiness.canCalculate ? "Расчет пока заблокирован" : freshnessProblem ? "Источники изменились после расчета" : !readiness.canApprove ? "Расчет доступен, утверждение — после исправлений" : "Данные готовы к расчету и утверждению";
  const description = !readiness.canCalculate ? "Нужны план магазина и действующая версия формулы." : freshnessProblem ? "Текущую ревизию нельзя утверждать или отмечать выплаченной до перерасчета." : !readiness.canApprove ? "Предпросмотр и черновик доступны, но качество данных пока не позволяет зафиксировать выплату." : "План, формула, классификация, себестоимость и смены прошли проверки backend.";
  return <details className={`payroll-readiness payroll-readiness--${tone}`}><summary><span className="payroll-readiness__icon">{tone === "success" ? <ShieldCheck /> : <AlertTriangle />}</span><div><strong>{title}</strong><p>{description}</p></div><b>{readiness.status === "READY" ? "Готово" : `${issueCount} проблем`}</b><i>Проверки</i></summary><div className="payroll-readiness-detail"><div className="payroll-check-grid"><article className={readiness.planPresent ? "ok" : "error"}><span>{readiness.planPresent ? <CheckCircle2 /> : <AlertTriangle />}</span><div><strong>План магазина</strong><small>{readiness.planPresent ? "Найден" : "Не задан"}</small></div>{!readiness.planPresent && <Link to={{ pathname: "/plan", search: `${search}&section=plan` }}>Заполнить</Link>}</article><article className={readiness.schemePresent ? "ok" : "error"}><span>{readiness.schemePresent ? <CheckCircle2 /> : <AlertTriangle />}</span><div><strong>Версия формулы</strong><small>{readiness.schemePresent ? "Найдена" : "Отсутствует"}</small></div>{!readiness.schemePresent && <span>{isAdmin ? "Откройте настройки" : "Нужен администратор"}</span>}</article><article className={readiness.unmappedItemCount === 0 ? "ok" : "error"}><span>{readiness.unmappedItemCount === 0 ? <CheckCircle2 /> : <AlertTriangle />}</span><div><strong>Классификация</strong><small>{readiness.unmappedItemCount === 0 ? "Все позиции определены" : `${readiness.unmappedItemCount} позиций без категории`}</small></div></article><article className={readiness.missingCostItemCount === 0 ? "ok" : "error"}><span>{readiness.missingCostItemCount === 0 ? <CheckCircle2 /> : <AlertTriangle />}</span><div><strong>Себестоимость</strong><small>{readiness.missingCostItemCount === 0 ? "Данные заполнены" : `${readiness.missingCostItemCount} позиций требуют проверки`}</small></div></article><article className={readiness.daysWithoutShift === 0 ? "ok" : "error"}><span>{readiness.daysWithoutShift === 0 ? <CheckCircle2 /> : <AlertTriangle />}</span><div><strong>Смены</strong><small>{readiness.daysWithoutShift === 0 ? `${readiness.scheduledDayCount} дней заполнено` : `${readiness.daysWithoutShift} дней с фондом без смены`}</small></div>{readiness.daysWithoutShift > 0 && <Link to={{ pathname: "/plan", search: `${search}&section=shifts` }}>Исправить</Link>}</article><article className="ok"><span><CheckCircle2 /></span><div><strong>Продажи</strong><small>{readiness.salesDayCount} дней с операциями</small></div></article></div>{run?.freshness.reasons.length ? <section className="payroll-stale-reasons"><strong>Что изменилось после расчета</strong>{run.freshness.reasons.map((reason) => <span key={reason}><RefreshCw />{staleReasonLabel(reason)}</span>)}</section> : null}{readiness.unmappedProducts.length > 0 && <details className="payroll-issue-details"><summary>Товары без зарплатной категории <span>{readiness.unmappedProducts.length}</span></summary>{readiness.unmappedProducts.slice(0, 20).map((product) => <article key={product.productId}><span><strong>{product.productName}</strong><small>{product.suggestedCategoryCode ? `Подсказка: ${product.suggestedCategoryCode}` : "Требуется ручная классификация"}</small></span><b>{product.netQuantity} шт.</b></article>)}</details>}{readiness.missingCosts.length > 0 && <details className="payroll-issue-details"><summary>Позиции без себестоимости <span>{readiness.missingCosts.length}</span></summary>{readiness.missingCosts.slice(0, 20).map((item, index) => <article key={`${item.productId}-${item.payrollDate}-${index}`}><span><strong>{item.productName}</strong><small>{item.payrollDate} · {item.payrollCategoryCode}</small></span><b>{item.quantity} шт.</b></article>)}</details>}{readiness.shiftIssues.length > 0 && <details className="payroll-issue-details"><summary>Дни без состава смены <span>{readiness.shiftIssues.length}</span></summary>{readiness.shiftIssues.map((issue) => <article key={issue.workDate}><span><strong>{issue.workDate}</strong><small>Фонд нельзя надежно распределить</small></span><b>{new Intl.NumberFormat("ru-RU", { style: "currency", currency: "RUB" }).format(issue.fundAmount)}</b></article>)}</details>}</div></details>;
}

function PayrollSkeleton() {
  return <div className="payroll-skeleton" aria-busy="true" aria-label="Загрузка зарплаты"><span className="skeleton payroll-skeleton--workflow" /><span className="skeleton payroll-skeleton--banner" /><div>{Array.from({ length: 4 }, (_, index) => <span className="skeleton" key={index} />)}</div><span className="skeleton payroll-skeleton--panel" /></div>;
}

export function PayrollPage() {
  const { user } = useAuth();
  const { selectedStore, month } = useWorkspace();
  const storeId = selectedStore.id;
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const [dialog, setDialog] = useState<DialogState>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const requestedSection = searchParams.get("payrollView");
  const section: PayrollSection = requestedSection === "days" || requestedSection === "audit" ? requestedSection : "statement";
  const baseSearch = new URLSearchParams({ store: storeId, month }).toString();

  const readinessQuery = useQuery({ queryKey: queryKeys.payrollReadiness(storeId, month), queryFn: () => getPayrollReadiness(storeId, month) });
  const latestQuery = useQuery({ queryKey: queryKeys.payrollLatest(storeId, month), queryFn: () => getLatestPayroll(storeId, month) });
  const previewQuery = useQuery({ queryKey: queryKeys.payrollPreview(storeId, month), queryFn: () => getPayrollPreview(storeId, month), enabled: readinessQuery.data?.canCalculate === true && latestQuery.data === null });
  const settingsQuery = useQuery({ queryKey: queryKeys.employeeRatingSettings(storeId), queryFn: () => getEmployeeRatingSettings(storeId), enabled: dialog?.type === "adjustment", staleTime: 2 * 60_000 });

  const refreshRelated = async (detail?: PayrollRunDetail) => {
    if (detail) queryClient.setQueryData(queryKeys.payrollLatest(storeId, month), detail);
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.payrollReadiness(storeId, month) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.payrollLatest(storeId, month) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.payrollPreview(storeId, month) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.payrollRuns(storeId) }),
      queryClient.invalidateQueries({ queryKey: ["stores", storeId, "employees", "card"] }),
      queryClient.invalidateQueries({ queryKey: ["stores", storeId, "period-quality"] })
    ]);
  };
  const onConflict = (error: unknown) => {
    if (isApiClientError(error) && error.status === 409) void refreshRelated();
  };
  const onSuccess = (message: string) => async (detail: PayrollRunDetail) => {
    await refreshRelated(detail);
    setDialog(null);
    setNotice(message);
  };

  const calculateMutation = useMutation({ mutationFn: (reason?: string) => calculatePayroll(storeId, month, reason), onSuccess: onSuccess("Расчет сохранен. Актуальная ревизия загружена."), onError: onConflict });
  const adjustmentMutation = useMutation({ mutationFn: (input: PayrollAdjustmentInput) => addPayrollAdjustment(storeId, latestQuery.data!.run.id, input), onSuccess: onSuccess("Удержание добавлено, ведомость пересчитана."), onError: onConflict });
  const voidMutation = useMutation({ mutationFn: ({ adjustment, reason }: { adjustment: PayrollAdjustment; reason: string }) => voidPayrollAdjustment(storeId, latestQuery.data!.run.id, adjustment.id, { reason, runVersion: latestQuery.data!.run.version, adjustmentVersion: adjustment.version }), onSuccess: onSuccess("Удержание отменено, изменение сохранено в аудите."), onError: onConflict });
  const approveMutation = useMutation({ mutationFn: () => approvePayroll(storeId, latestQuery.data!.run.id, latestQuery.data!.run.version), onSuccess: onSuccess("Расчет утвержден и больше не редактируется."), onError: onConflict });
  const paidMutation = useMutation({ mutationFn: () => markPayrollPaid(storeId, latestQuery.data!.run.id, latestQuery.data!.run.version), onSuccess: onSuccess("Выплата отмечена. Месячный workflow завершен."), onError: onConflict });
  const mutations = [calculateMutation, adjustmentMutation, voidMutation, approveMutation, paidMutation];
  const busy = mutations.some((mutation) => mutation.isPending);
  const closeDialog = () => { if (!busy) { setDialog(null); mutations.forEach((mutation) => mutation.reset()); } };

  if (readinessQuery.isPending || latestQuery.isPending) return <PayrollSkeleton />;
  if (readinessQuery.isError || latestQuery.isError) { const failed = readinessQuery.isError ? readinessQuery : latestQuery; return <QueryError error={failed.error} onRetry={() => void Promise.all([readinessQuery.refetch(), latestQuery.refetch()])} />; }
  const readiness = readinessQuery.data;
  const detail = latestQuery.data;
  if (!detail && readiness.canCalculate && previewQuery.isError) return <QueryError error={previewQuery.error} onRetry={() => void previewQuery.refetch()} />;
  const preview = previewQuery.data ?? null;
  const canApprove = Boolean(detail && detail.run.status === "CALCULATED" && detail.run.calculationComplete && detail.run.freshness.status === "CURRENT" && readiness.canApprove);
  const canPaid = Boolean(detail && detail.run.status === "APPROVED" && detail.run.freshness.status === "CURRENT");
  const adjustmentEmployees = (() => { const employees = new Map<string, string>(); (settingsQuery.data ?? []).filter((setting) => setting.employeeActive && setting.assignmentActive).forEach((setting) => employees.set(setting.employeeId, setting.displayName)); detail?.statements.forEach((statement) => employees.set(statement.employeeId, statement.employeeName)); return [...employees].map(([employeeId, employeeName]) => ({ employeeId, employeeName })).sort((left, right) => left.employeeName.localeCompare(right.employeeName, "ru-RU")); })();
  const selectSection = (next: PayrollSection) => setSearchParams((current) => { const value = new URLSearchParams(current); value.set("payrollView", next); return value; });
  const startCalculation = () => {
    if (!detail) calculateMutation.mutate(undefined);
    else if (detail.run.status === "CALCULATED") {
      if (window.confirm("Пересчитать текущую черновую ревизию по актуальным источникам?")) calculateMutation.mutate(undefined);
    } else setDialog({ type: "revision" });
  };

  return <div className="payroll-page"><header className="page-heading payroll-heading"><div><p className="eyebrow">{selectedStore.name}</p><h1>Зарплата и аудит</h1></div>{detail && <div className="payroll-heading-status"><small>Текущая ревизия</small><strong>№{detail.run.revision} · {statusLabel(detail.run.status)}</strong><span className={`status ${detail.run.freshness.status === "CURRENT" ? "status--success" : "status--warning"}`}>{detail.run.freshness.status === "CURRENT" ? "Актуальна" : "Нужен перерасчет"}</span></div>}</header><Workflow canCalculate={readiness.canCalculate} run={detail?.run ?? null} /><ReadinessBanner readiness={readiness} run={detail?.run ?? null} search={baseSearch} isAdmin={user?.role === "ADMIN"} />{notice && <section className="payroll-notice" role="status"><CheckCircle2 /><span>{notice}</span><button type="button" onClick={() => setNotice(null)} aria-label="Скрыть уведомление"><X /></button></section>}

    <nav className="payroll-tabs" aria-label="Разделы зарплаты"><button className={section === "statement" ? "active" : ""} type="button" onClick={() => selectSection("statement")}><ReceiptText />Ведомость<span>{detail ? `ревизия №${detail.run.revision}` : "предпросмотр"}</span></button><button className={section === "days" ? "active" : ""} type="button" onClick={() => selectSection("days")}><CalendarDays />Дневные фонды<span>{detail?.dailyPools.length ?? preview?.actualScenario.days.length ?? 0} дней</span></button><button className={section === "audit" ? "active" : ""} type="button" onClick={() => selectSection("audit")}><History />Аудит<span>{detail ? `${detail.events.length} событий` : "после расчета"}</span></button></nav>

    {previewQuery.isPending && !detail ? <PayrollSkeleton /> : section === "statement" ? <PayrollOverview detail={detail} preview={preview} onAddAdjustment={() => setDialog({ type: "adjustment" })} /> : section === "days" ? <PayrollDailyPanel detail={detail} preview={preview} /> : <PayrollAuditPanel storeId={storeId} month={month} timeZone={selectedStore.timezone} detail={detail} onVoidAdjustment={(adjustment) => setDialog({ type: "void", adjustment })} />}

    <aside className="payroll-action-bar"><div><span>{detail ? <FileClock /> : <Calculator />}</span><div><strong>{!detail ? "Предпросмотр ничего не сохраняет" : detail.run.freshness.requiresRecalculation ? "Расчет потерял актуальность" : statusLabel(detail.run.status)}</strong><small>{!detail ? "После создания появится ревизия №1 с полным аудитом." : detail.run.status === "CALCULATED" ? "Черновик можно пересчитывать и дополнять удержаниями." : "Изменения возможны только через новую ревизию с причиной."}</small></div></div><section>{detail?.run.status === "CALCULATED" && <button className="button button--ghost" type="button" disabled={busy} onClick={() => setDialog({ type: "adjustment" })}><Plus />Удержание</button>}{detail?.run.status === "CALCULATED" && <button className="button button--ghost" type="button" disabled={!canApprove || busy} title={!canApprove ? "Нужны полный, актуальный расчет и готовность к утверждению" : undefined} onClick={() => setDialog({ type: "approve" })}><ShieldCheck />Утвердить</button>}{detail?.run.status === "APPROVED" && <button className="button button--primary" type="button" disabled={!canPaid || busy} title={!canPaid ? "Перед выплатой требуется перерасчет актуальных источников" : undefined} onClick={() => setDialog({ type: "paid" })}><CircleDollarSign />Отметить выплаченным</button>}{(!detail || detail.run.status === "CALCULATED") && <button className="button button--primary" type="button" disabled={!readiness.canCalculate || busy} onClick={startCalculation}>{detail ? <RefreshCw /> : <Calculator />}{busy && calculateMutation.isPending ? "Выполняем…" : detail?.run.status === "CALCULATED" ? "Пересчитать" : detail ? "Новая ревизия" : "Создать расчет"}</button>}{detail?.run.status === "APPROVED" && <button className="button button--ghost" type="button" disabled={!readiness.canCalculate || busy} onClick={() => setDialog({ type: "revision" })}><FileClock />Новая ревизия</button>}{detail?.run.status === "PAID" && <button className="button button--primary" type="button" disabled={!readiness.canCalculate || busy} onClick={() => setDialog({ type: "revision" })}><FileClock />Новая ревизия</button>}</section></aside>

    {dialog?.type === "adjustment" && <AdjustmentDialog employees={adjustmentEmployees} runVersion={detail!.run.version} busy={adjustmentMutation.isPending || settingsQuery.isPending} error={adjustmentMutation.isError ? payrollErrorMessage(adjustmentMutation.error) : undefined} onClose={closeDialog} onConfirm={(input) => adjustmentMutation.mutate(input)} />}{dialog?.type === "revision" && <ReasonDialog title="Создать новую ревизию" description="Текущая утвержденная или выплаченная ревизия останется неизменной. Активные удержания будут перенесены." confirmLabel="Создать ревизию" busy={calculateMutation.isPending} error={calculateMutation.isError ? payrollErrorMessage(calculateMutation.error) : undefined} onClose={closeDialog} onConfirm={(reason) => calculateMutation.mutate(reason)} />}{dialog?.type === "void" && <ReasonDialog title="Отменить удержание" description={`${dialog.adjustment.employeeName} · ${dialog.adjustment.reason}. Отмена останется в аудите и пересчитает ведомость.`} confirmLabel="Отменить удержание" danger busy={voidMutation.isPending} error={voidMutation.isError ? payrollErrorMessage(voidMutation.error) : undefined} onClose={closeDialog} onConfirm={(reason) => voidMutation.mutate({ adjustment: dialog.adjustment, reason })} />}{dialog?.type === "approve" && <ConfirmPayrollDialog title="Утвердить расчет" description={`Ревизия №${detail!.run.revision} станет неизменяемой. Для следующих изменений потребуется новая ревизия.`} confirmLabel="Утвердить" danger busy={approveMutation.isPending} error={approveMutation.isError ? payrollErrorMessage(approveMutation.error) : undefined} onClose={closeDialog} onConfirm={() => approveMutation.mutate()} />}{dialog?.type === "paid" && <ConfirmPayrollDialog title="Отметить выплату" description={`Подтвердите, что выплата по ревизии №${detail!.run.revision} фактически произведена.`} confirmLabel="Отметить выплаченным" danger busy={paidMutation.isPending} error={paidMutation.isError ? payrollErrorMessage(paidMutation.error) : undefined} onClose={closeDialog} onConfirm={() => paidMutation.mutate()} />}
  </div>;
}
