import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, CircleDollarSign, Pencil, Plus, Save, Target, TrendingUp, X } from "lucide-react";
import { useState } from "react";
import type { PerformancePlan, PlanDirection } from "../api/contracts";
import { getPerformancePlan, getPlanProgress, queryKeys, upsertPerformancePlan } from "../api/queries";
import { formatDate } from "../shared/date";
import { formatCompactMoney, formatMoney, formatPercent } from "../shared/format";
import { QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { validatePlanForm, type PlanFormErrors, type PlanFormValues } from "./forms";

const directionLabels: Record<string, string> = {
  REVENUE: "Выручка",
  ACCESSORY: "Аксессуары",
  SERVICE: "Услуги",
  ADDITIONAL: "Дополнительная выручка"
};

const statusLabels: Record<string, string> = {
  ACHIEVED: "Выполнено",
  ON_TRACK: "По графику",
  AT_RISK: "Есть риск",
  MISSED: "Не выполнено",
  NOT_AVAILABLE: "Недостаточно данных"
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

function DirectionCard({ direction }: { direction: PlanDirection }) {
  const isRevenue = direction.code === "REVENUE";
  const completion = direction.criterionCompletionPercent;
  return (
    <article className={`plan-direction-card plan-direction-card--${statusTone(direction.status)}`}>
      <div className="plan-direction-card__head"><span>{isRevenue ? <CircleDollarSign /> : <TrendingUp />}</span><i className={`status status--${statusTone(direction.status)}`}>{statusLabels[direction.status] ?? "Неизвестный статус"}</i></div>
      <small>{directionLabels[direction.code] ?? direction.code}</small>
      <strong>{isRevenue ? formatPercent(completion) : `${formatPercent(direction.actualSharePercent)} / ${formatPercent(direction.targetSharePercent)}`}</strong>
      <p>{formatMoney(direction.actualAmount)} из {formatMoney(direction.targetAmount)}</p>
      <progress value={Math.min(100, Math.max(0, completion ?? 0))} max={100} aria-label={`${directionLabels[direction.code] ?? direction.code}: ${formatPercent(completion)}`} />
      <dl><div><dt>Прогноз</dt><dd>{formatCompactMoney(direction.projectedAmount)}</dd></div><div><dt>{direction.remainingAmount > 0 ? "Осталось" : "Темп"}</dt><dd>{direction.remainingAmount > 0 ? formatCompactMoney(direction.remainingAmount) : "План набран"}</dd></div><div><dt>Нужно в день</dt><dd>{formatCompactMoney(direction.requiredPerRemainingDay)}</dd></div></dl>
    </article>
  );
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
    mutationFn: (input: NonNullable<ReturnType<typeof validatePlanForm>["data"]>) => upsertPerformancePlan(storeId, month, input),
    onSuccess: async (plan) => {
      queryClient.setQueryData(queryKeys.performancePlan(storeId, month), plan);
      setDraft({ key: `${storeId}:${month}:${plan.version}`, values: valuesFromPlan(plan), errors: {}, editing: false });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["stores", storeId, "plan-progress"] }),
        queryClient.invalidateQueries({ queryKey: queryKeys.employees(storeId) }),
        queryClient.invalidateQueries({ queryKey: ["stores", storeId, "period-quality"] }),
        queryClient.invalidateQueries({ queryKey: ["stores", storeId, "payroll"] })
      ]);
    }
  });

  if (planQuery.isPending || progressQuery.isPending) return <PlanSkeleton />;
  if (planQuery.isError || progressQuery.isError) {
    const failed = planQuery.isError ? planQuery : progressQuery;
    return <QueryError error={failed.error} onRetry={() => void Promise.all([planQuery.refetch(), progressQuery.refetch()])} />;
  }

  const plan = planQuery.data;
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

  return (
    <div className="plan-panel-view">
      {progress && (!progress.dataQuality.completeThroughAsOf || !progress.dataQuality.classificationComplete) ? <section className="plan-quality-warning"><AlertTriangle /><div><strong>Показатели требуют осторожной интерпретации</strong><p>{!progress.dataQuality.completeThroughAsOf ? "Синхронизация ещё не подтвердила данные до даты среза. " : ""}{!progress.dataQuality.classificationComplete ? `Есть неклассифицированные позиции: ${progress.dataQuality.unmappedItemCount}.` : ""}</p></div></section> : null}

      {progress && <>
        <section className="plan-progress-heading"><div><p className="eyebrow">Выполнение на {formatDate(progress.asOfDate)}</p><h2>{progress.achievedDirectionCount} из 4 направлений выполнено</h2><p>{progress.remainingDays > 0 ? `До конца месяца ${progress.remainingDays} дн.` : "Месяц завершён."}</p></div><span className={`plan-progress-score ${progress.allDirectionsAchieved ? "plan-progress-score--success" : ""}`}><strong>{progress.achievedDirectionCount}/4</strong><small>{progress.allDirectionsAchieved ? "Все цели достигнуты" : "Требуют контроля"}</small></span></section>
        {progress.focusDirections.length > 0 && <section className="plan-focus-banner"><Target /><div><strong>Фокус руководителя</strong><p>{progress.focusDirections.map((code) => directionLabels[code] ?? code).join(" · ")}</p></div></section>}
        <section className="plan-direction-grid" aria-label="Направления плана">{progress.directions.map((direction) => <DirectionCard direction={direction} key={direction.code} />)}</section>
      </>}

      <section className="panel plan-settings-panel">
        <div className="panel__heading"><div><p className="eyebrow">Общий месячный план</p><h2>{plan ? "Целевые показатели магазина" : "План ещё не задан"}</h2></div>{plan && !editing && <button className="button button--ghost" type="button" onClick={() => setDraft({ ...form, editing: true })}><Pencil size={15} />Изменить</button>}</div>
        {!editing && plan ? <div className="plan-current-values"><article><small>Выручка</small><strong>{formatMoney(plan.revenueTarget)}</strong></article><article><small>Аксессуары</small><strong>{formatPercent(plan.accessoryShareTarget)}</strong></article><article><small>Услуги</small><strong>{formatPercent(plan.serviceShareTarget)}</strong></article><article><small>Доп. выручка</small><strong>{formatPercent(plan.additionalShareTarget)}</strong></article></div> : <>
          {!plan && <div className="plan-empty-intro"><span><Plus /></span><div><strong>Заполните четыре цели на месяц</strong><p>План один для всего магазина. Персональные планы сотрудников не создаются.</p></div></div>}
          <div className="plan-form"><PlanField label="План выручки" suffix="₽" value={values.revenueTarget} error={errors.revenueTarget} onChange={(value) => updateValue("revenueTarget", value)} /><PlanField label="Доля аксессуаров" suffix="%" value={values.accessoryShareTarget} error={errors.accessoryShareTarget} onChange={(value) => updateValue("accessoryShareTarget", value)} /><PlanField label="Доля услуг" suffix="%" value={values.serviceShareTarget} error={errors.serviceShareTarget} onChange={(value) => updateValue("serviceShareTarget", value)} /><PlanField label="Доля доп. выручки" suffix="%" value={values.additionalShareTarget} error={errors.additionalShareTarget} onChange={(value) => updateValue("additionalShareTarget", value)} /></div>
          {mutation.isError && <div className="form-alert" role="alert">Не удалось сохранить план. Проверьте значения и повторите действие.</div>}
          <div className="plan-form-actions">{plan && <button className="button button--ghost" type="button" disabled={mutation.isPending} onClick={cancel}><X size={15} />Отмена</button>}<button className="button button--primary" type="button" disabled={mutation.isPending} onClick={submit}><Save size={15} />{mutation.isPending ? "Сохраняем…" : plan ? "Сохранить изменения" : "Создать план"}</button></div>
        </>}
        {plan && <footer className="plan-settings-meta"><CheckCircle2 size={14} /><span>Последнее обновление: {new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium", timeStyle: "short", timeZone: selectedStore.timezone }).format(new Date(plan.updatedAt))}. Версия {plan.version} хранится для аудита и не отправляется при сохранении.</span></footer>}
      </section>
    </div>
  );
}

interface PlanDraftState {
  key: string;
  values: PlanFormValues;
  errors: PlanFormErrors;
  editing: boolean;
}
