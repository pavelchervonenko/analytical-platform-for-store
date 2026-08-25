import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, CircleDollarSign, Pencil, Plus, Save, TrendingUp, X } from "lucide-react";
import { useState } from "react";
import { isApiClientError } from "../api/client";
import type { PerformancePlan, PlanDirection } from "../api/contracts";
import { getPerformancePlan, getPlanProgress, queryKeys, upsertPerformancePlan } from "../api/queries";
import { formatDate, formatMonth } from "../shared/date";
import { formatCompactMoney, formatMoney, formatPercent } from "../shared/format";
import { QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { DailyPlanTable } from "./DailyPlanTable";
import { validatePlanForm, type PlanFormErrors, type PlanFormValues } from "./forms";

const directionLabels: Record<string, string> = {
  REVENUE: "Выручка",
  ACCESSORY: "Аксессуары",
  SERVICE: "Услуги",
  ADDITIONAL: "Доп. выручка"
};

const statusLabels: Record<string, string> = {
  ACHIEVED: "Выполнено",
  ON_TRACK: "По графику",
  AT_RISK: "Есть риск",
  MISSED: "Не выполнено",
  NOT_AVAILABLE: "Нет данных"
};

function statusTone(status: string): string {
  if (["ACHIEVED", "ON_TRACK"].includes(status)) return "success";
  if (status === "MISSED") return "danger";
  return "warning";
}

function valuesFromPlan(plan: PerformancePlan | null): PlanFormValues {
  return {
    revenueTarget: plan ? String(plan.revenueTarget) : "",
    accessoryShareTarget: plan ? String(plan.accessoryShareTarget) : "",
    serviceShareTarget: plan ? String(plan.serviceShareTarget) : "",
    additionalShareTarget: plan ? String(plan.additionalShareTarget) : ""
  };
}

function PlanField({ label, suffix, value, error, onChange }: { label: string; suffix: string; value: string; error?: string; onChange: (value: string) => void }) {
  return <label className={`plan-field ${error ? "plan-field--error" : ""}`}><span>{label}</span><div><input type="text" inputMode="decimal" value={value} onChange={(event) => onChange(event.target.value)} aria-invalid={Boolean(error)} /><i>{suffix}</i></div>{error && <small role="alert">{error}</small>}</label>;
}

const directionPriority: Record<string, number> = {
  MISSED: 0,
  AT_RISK: 1,
  NOT_AVAILABLE: 2,
  ON_TRACK: 3,
  ACHIEVED: 4
};

function isRevenueDirection(direction: PlanDirection): boolean {
  return direction.criterionType === "AMOUNT" || direction.code === "REVENUE";
}

export function DirectionCard({ direction }: { direction: PlanDirection }) {
  const isRevenue = isRevenueDirection(direction);
  const completion = direction.criterionCompletionPercent;
  const amountContext = isRevenue
    ? "Факт к месячной цели"
    : "Факт к ориентиру на текущую выручку";
  const forecastLabel = isRevenue
    ? "Прогноз выручки на конец месяца"
    : "Прогноз суммы на конец месяца";
  const remainingLabel = isRevenue
    ? "Осталось до месячной цели"
    : "Текущее отставание";
  const dailyRequirementLabel = isRevenue
    ? "До месячного плана в день"
    : "Закрыть отставание в день";
  return (
    <article className={`plan-direction-card plan-direction-card--${statusTone(direction.status)}`}>
      <div className="plan-direction-card__head"><span>{isRevenue ? <CircleDollarSign /> : <TrendingUp />}</span><i className={`status status--${statusTone(direction.status)}`}>{statusLabels[direction.status] ?? "Неизвестный статус"}</i></div>
      <small>{directionLabels[direction.code] ?? "Другое направление"}</small>
      <span className="plan-direction-card__value-label">{isRevenue ? "Выполнение месячной цели" : "Доля на текущую дату"}</span>
      <strong>{isRevenue ? formatPercent(completion) : `${formatPercent(direction.actualSharePercent)} / ${formatPercent(direction.targetSharePercent)}`}</strong>
      <p>{amountContext}: {formatMoney(direction.actualAmount)} из {formatMoney(direction.targetAmount)}</p>
      <progress value={Math.min(100, Math.max(0, completion ?? 0))} max={100} aria-label={`${directionLabels[direction.code] ?? "Другое направление"}: ${formatPercent(completion)}`} />
      <dl><div><dt>{forecastLabel}</dt><dd>{formatCompactMoney(direction.projectedAmount)}</dd></div><div><dt>{direction.remainingAmount > 0 ? remainingLabel : "Состояние на дату"}</dt><dd>{direction.remainingAmount > 0 ? formatCompactMoney(direction.remainingAmount) : isRevenue ? "Цель достигнута" : "Отставания нет"}</dd></div><div><dt>{dailyRequirementLabel}</dt><dd>{direction.remainingAmount > 0 ? formatCompactMoney(direction.requiredPerRemainingDay) : "Не требуется"}</dd></div></dl>
    </article>
  );
}

function overallStatus(directions: PlanDirection[], allAchieved: boolean) {
  if (allAchieved) return { label: "План выполнен", tone: "success" };
  if (directions.some((direction) => direction.status === "MISSED")) return { label: "План не выполнен", tone: "danger" };
  if (directions.some((direction) => direction.status === "AT_RISK")) return { label: "План требует внимания", tone: "warning" };
  if (directions.some((direction) => direction.status === "NOT_AVAILABLE")) return { label: "Недостаточно данных", tone: "warning" };
  return { label: "План выполняется по графику", tone: "success" };
}

export function primaryPlanAction(direction: PlanDirection | undefined): string {
  if (!direction) return "";
  const label = directionLabels[direction.code] ?? "Направление";
  if (direction.remainingAmount <= 0) return `${label}: план выполнен.`;
  if (direction.requiredPerRemainingDay == null) {
    return `${label}: проверьте данные и текущий темп.`;
  }
  const dailyAmount = formatCompactMoney(direction.requiredPerRemainingDay);
  return isRevenueDirection(direction)
    ? `${label}: чтобы выполнить месячный план, нужно ${dailyAmount} в день.`
    : `${label}: чтобы закрыть текущее отставание, нужно ${dailyAmount} в день.`;
}

function PlanSkeleton() {
  return <div className="plan-panel-skeleton" aria-busy="true" aria-label="Загрузка плана"><span className="skeleton skeleton--banner" /><div className="plan-direction-grid">{Array.from({ length: 4 }, (_, index) => <span className="skeleton plan-direction-skeleton" key={index} />)}</div><span className="skeleton skeleton--panel" /></div>;
}

export function PlanPanel() {
  const { selectedStore, month, asOfDate } = useWorkspace();
  const storeId = selectedStore.id;
  const queryClient = useQueryClient();
  const planQuery = useQuery({ queryKey: queryKeys.performancePlan(storeId, month), queryFn: () => getPerformancePlan(storeId, month) });
  const progressQuery = useQuery({ queryKey: queryKeys.planProgress(storeId, month, asOfDate), queryFn: () => getPlanProgress(storeId, month, asOfDate) });
  const [draft, setDraft] = useState<PlanDraftState | null>(null);

  const mutation = useMutation({
    mutationFn: (input: NonNullable<ReturnType<typeof validatePlanForm>["data"]>) => upsertPerformancePlan(storeId, month, input, planQuery.data ?? null),
    onSuccess: async (saved) => {
      queryClient.setQueryData(queryKeys.performancePlan(storeId, month), saved);
      const plan = saved.value;
      setDraft({ key: `${storeId}:${month}:${plan.version}`, values: valuesFromPlan(plan), errors: {}, editing: false });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["stores", storeId, "plan-progress"] }),
        queryClient.invalidateQueries({ queryKey: queryKeys.employees(storeId) }),
        queryClient.invalidateQueries({ queryKey: ["stores", storeId, "period-quality"] }),
        queryClient.invalidateQueries({ queryKey: ["stores", storeId, "payroll"] })
      ]);
    },
    onError: (error) => {
      if (isApiClientError(error) && error.status === 412) void planQuery.refetch();
    }
  });

  if (planQuery.isPending || progressQuery.isPending) return <PlanSkeleton />;
  if (planQuery.isError || progressQuery.isError) {
    const failed = planQuery.isError ? planQuery : progressQuery;
    return <QueryError error={failed.error} onRetry={() => void Promise.all([planQuery.refetch(), progressQuery.refetch()])} />;
  }

  const plan = planQuery.data?.value ?? null;
  const progress = progressQuery.data;
  const draftKey = `${storeId}:${month}:${plan?.version ?? "new"}`;
  const form = draft?.key === draftKey ? draft : { key: draftKey, values: valuesFromPlan(plan), errors: {}, editing: plan == null };
  const { values, errors, editing } = form;
  const updateValue = (field: keyof PlanFormValues, value: string) => {
    setDraft({ ...form, values: { ...values, [field]: value }, errors: { ...errors, [field]: undefined } });
  };
  const submit = () => {
    const validation = validatePlanForm(values);
    setDraft({ ...form, errors: validation.errors });
    if (validation.data) mutation.mutate(validation.data);
  };
  const cancel = () => {
    setDraft({ key: draftKey, values: valuesFromPlan(plan), errors: {}, editing: plan == null });
    mutation.reset();
  };
  const sortedDirections = progress
    ? [...progress.directions].sort((left, right) => (directionPriority[left.status] ?? 99) - (directionPriority[right.status] ?? 99))
    : [];
  const summary = progress ? overallStatus(sortedDirections, progress.allDirectionsAchieved) : null;
  const primaryAction = primaryPlanAction(sortedDirections[0]);


  return (
    <div className="plan-panel-view">
      {progress && (!progress.dataQuality.completeThroughAsOf || !progress.dataQuality.classificationComplete) ? <section className="plan-quality-warning"><AlertTriangle /><div><strong>Показатели требуют осторожной интерпретации</strong><p>{!progress.dataQuality.completeThroughAsOf ? "Синхронизация еще не подтвердила данные до даты среза. " : ""}{!progress.dataQuality.classificationComplete ? `Есть неклассифицированные позиции: ${progress.dataQuality.unmappedItemCount}.` : ""}</p></div></section> : null}

      {progress && summary && <>
        <section className={`plan-progress-heading plan-progress-heading--${summary.tone}`}>
          <div><p className="eyebrow">План на {formatMonth(month)}. Данные на {formatDate(progress.asOfDate)}</p><h2>{summary.label}</h2><p>{primaryAction}</p><small>{progress.remainingDays > 0 ? `До конца месяца ${progress.remainingDays} дн.` : "Месяц завершён."}</small></div>
          <span className={`plan-progress-score plan-progress-score--${summary.tone}`}><strong>{progress.achievedDirectionCount}/4</strong><small>направлений выполнено</small></span>
        </section>
        <section className="plan-direction-grid" aria-label="Направления плана">{progress.directions.map((direction) => <DirectionCard direction={direction} key={direction.code} />)}</section>
      </>}

      {!editing && plan ? <details className="panel plan-settings-disclosure">
        <summary><div><p className="eyebrow">Настройки плана</p><h2>Цели на {formatMonth(month)}</h2><p>{formatCompactMoney(plan.revenueTarget)}, аксессуары {formatPercent(plan.accessoryShareTarget)}, услуги {formatPercent(plan.serviceShareTarget)}, доп. выручка {formatPercent(plan.additionalShareTarget)}</p></div><span aria-hidden="true" /></summary>
        <div className="plan-settings-disclosure__content">
          <div className="plan-current-values"><article><small>Выручка</small><strong>{formatMoney(plan.revenueTarget)}</strong></article><article><small>Аксессуары</small><strong>{formatPercent(plan.accessoryShareTarget)}</strong></article><article><small>Услуги</small><strong>{formatPercent(plan.serviceShareTarget)}</strong></article><article><small>Доп. выручка</small><strong>{formatPercent(plan.additionalShareTarget)}</strong></article></div>
          <div className="plan-settings-disclosure__actions"><button className="button button--ghost" type="button" onClick={() => setDraft({ ...form, editing: true })}><Pencil size={15} />Изменить цели</button></div>
          <footer className="plan-settings-meta"><CheckCircle2 size={14} /><span>Обновлено {new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium", timeStyle: "short", timeZone: selectedStore.timezone }).format(new Date(plan.updatedAt))}</span></footer>
        </div>
      </details> : <section className="panel plan-settings-panel">
        <div className="panel__heading"><div><p className="eyebrow">Настройки плана</p><h2>{plan ? `Изменение целей на ${formatMonth(month)}` : "План ещё не задан"}</h2></div></div>
        {!plan && <div className="plan-empty-intro"><span><Plus /></span><div><strong>Заполните четыре цели на месяц</strong><p>План один для всего магазина. Персональные планы сотрудников не создаются.</p></div></div>}
        <div className="plan-form"><PlanField label="План выручки" suffix="₽" value={values.revenueTarget} error={errors.revenueTarget} onChange={(value) => updateValue("revenueTarget", value)} /><PlanField label="Доля аксессуаров" suffix="%" value={values.accessoryShareTarget} error={errors.accessoryShareTarget} onChange={(value) => updateValue("accessoryShareTarget", value)} /><PlanField label="Доля услуг" suffix="%" value={values.serviceShareTarget} error={errors.serviceShareTarget} onChange={(value) => updateValue("serviceShareTarget", value)} /><PlanField label="Доля доп. выручки" suffix="%" value={values.additionalShareTarget} error={errors.additionalShareTarget} onChange={(value) => updateValue("additionalShareTarget", value)} /></div>
        {mutation.isError && <div className="form-alert" role="alert">{isApiClientError(mutation.error) && mutation.error.status === 412 ? "План уже изменён другим пользователем. Загружена актуальная версия — проверьте значения повторно." : "Не удалось сохранить план. Проверьте значения и повторите действие."}</div>}
        <div className="plan-form-actions">{plan && <button className="button button--ghost" type="button" disabled={mutation.isPending} onClick={cancel}><X size={15} />Отмена</button>}<button className="button button--primary" type="button" disabled={mutation.isPending} onClick={submit}><Save size={15} />{mutation.isPending ? "Сохраняем…" : plan ? "Сохранить изменения" : "Создать план"}</button></div>
      </section>}

      {progress && <DailyPlanTable targets={progress.dailyTargets} />}
    </div>
  );
}

interface PlanDraftState {
  key: string;
  values: PlanFormValues;
  errors: PlanFormErrors;
  editing: boolean;
}
