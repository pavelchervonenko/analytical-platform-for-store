import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Pencil, Plus, Save, X } from "lucide-react";
import { useState } from "react";
import { isApiClientError } from "../api/client";
import type { PerformancePlan } from "../api/contracts";
import { getPerformancePlan, queryKeys, upsertPerformancePlan } from "../api/queries";
import { formatMonth } from "../shared/date";
import { formatMoney, formatPercent } from "../shared/format";
import { PanelSkeleton, QueryError, StaleDataNote } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { validatePlanForm, type PlanFormErrors, type PlanFormValues } from "./forms";

function valuesFromPlan(plan: PerformancePlan | null): PlanFormValues {
  return {
    revenueTarget: plan ? String(plan.revenueTarget) : "",
    accessoryShareTarget: plan ? String(plan.accessoryShareTarget) : "",
    serviceShareTarget: plan ? String(plan.serviceShareTarget) : "",
    additionalShareTarget: plan ? String(plan.additionalShareTarget) : ""
  };
}

function PlanField({
  label,
  suffix,
  value,
  error,
  onChange
}: {
  label: string;
  suffix: string;
  value: string;
  error?: string;
  onChange: (value: string) => void;
}) {
  return (
    <label className={`plan-field ${error ? "plan-field--error" : ""}`}>
      <span>{label}</span>
      <div>
        <input
          type="text"
          inputMode="decimal"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          aria-invalid={Boolean(error)}
        />
        <i>{suffix}</i>
      </div>
      {error && <small role="alert">{error}</small>}
    </label>
  );
}

export function PlanSettingsPanel() {
  const { selectedStore, month } = useWorkspace();
  const storeId = selectedStore.id;
  const queryClient = useQueryClient();
  const planQuery = useQuery({
    queryKey: queryKeys.performancePlan(storeId, month),
    queryFn: () => getPerformancePlan(storeId, month)
  });
  const [draft, setDraft] = useState<PlanDraftState | null>(null);

  const mutation = useMutation({
    mutationFn: (input: NonNullable<ReturnType<typeof validatePlanForm>["data"]>) => (
      upsertPerformancePlan(storeId, month, input, planQuery.data ?? null)
    ),
    onSuccess: async (saved) => {
      queryClient.setQueryData(queryKeys.performancePlan(storeId, month), saved);
      const plan = saved.value;
      setDraft({
        key: `${storeId}:${month}:${plan.version}`,
        values: valuesFromPlan(plan),
        errors: {},
        editing: false
      });
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

  if (planQuery.isPending && planQuery.data === undefined) {
    return <section className="panel plan-settings-panel"><PanelSkeleton rows={3} /></section>;
  }
  if (planQuery.isError && planQuery.data === undefined) {
    return <QueryError error={planQuery.error} onRetry={() => void planQuery.refetch()} />;
  }

  const plan = planQuery.data?.value ?? null;
  const draftKey = `${storeId}:${month}:${plan?.version ?? "new"}`;
  const form = draft?.key === draftKey
    ? draft
    : { key: draftKey, values: valuesFromPlan(plan), errors: {}, editing: plan == null };
  const { values, errors, editing } = form;

  const updateValue = (field: keyof PlanFormValues, value: string) => {
    setDraft({
      ...form,
      values: { ...values, [field]: value },
      errors: { ...errors, [field]: undefined }
    });
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
    <div className="plan-settings-view">
      {planQuery.isError && planQuery.data !== undefined && (
        <StaleDataNote error={planQuery.error} onRetry={() => void planQuery.refetch()} />
      )}

      {!editing && plan ? (
        <section className="panel plan-settings-panel">
          <div className="panel__heading">
            <div>
              <p className="eyebrow">Настройка плана</p>
              <h2>Цели на {formatMonth(month)}</h2>
            </div>
            <button
              className="button button--ghost"
              type="button"
              onClick={() => setDraft({ ...form, editing: true })}
            >
              <Pencil size={15} />
              Изменить цели
            </button>
          </div>
          <div className="plan-current-values">
            <article><small>Выручка</small><strong>{formatMoney(plan.revenueTarget)}</strong></article>
            <article><small>Аксессуары</small><strong>{formatPercent(plan.accessoryShareTarget)}</strong></article>
            <article><small>Услуги</small><strong>{formatPercent(plan.serviceShareTarget)}</strong></article>
            <article><small>Доп. выручка</small><strong>{formatPercent(plan.additionalShareTarget)}</strong></article>
          </div>
          <footer className="plan-settings-meta">
            <CheckCircle2 size={14} />
            <span>
              Обновлено {new Intl.DateTimeFormat("ru-RU", {
                dateStyle: "medium",
                timeStyle: "short",
                timeZone: selectedStore.timezone
              }).format(new Date(plan.updatedAt))}
            </span>
          </footer>
        </section>
      ) : (
        <section className="panel plan-settings-panel">
          <div className="panel__heading">
            <div>
              <p className="eyebrow">Настройка плана</p>
              <h2>{plan ? `Изменение целей на ${formatMonth(month)}` : "План ещё не задан"}</h2>
            </div>
          </div>
          {!plan && (
            <div className="plan-empty-intro">
              <span><Plus /></span>
              <div>
                <strong>Заполните четыре цели на месяц</strong>
                <p>План один для всего магазина. Персональные планы сотрудников не создаются.</p>
              </div>
            </div>
          )}
          <div className="plan-form">
            <PlanField label="План выручки" suffix="₽" value={values.revenueTarget} error={errors.revenueTarget} onChange={(value) => updateValue("revenueTarget", value)} />
            <PlanField label="Доля аксессуаров" suffix="%" value={values.accessoryShareTarget} error={errors.accessoryShareTarget} onChange={(value) => updateValue("accessoryShareTarget", value)} />
            <PlanField label="Доля услуг" suffix="%" value={values.serviceShareTarget} error={errors.serviceShareTarget} onChange={(value) => updateValue("serviceShareTarget", value)} />
            <PlanField label="Доля доп. выручки" suffix="%" value={values.additionalShareTarget} error={errors.additionalShareTarget} onChange={(value) => updateValue("additionalShareTarget", value)} />
          </div>
          {mutation.isError && (
            <div className="form-alert" role="alert">
              {isApiClientError(mutation.error) && mutation.error.status === 412
                ? "План уже изменён другим пользователем. Загружена актуальная версия. Проверьте значения повторно."
                : isApiClientError(mutation.error)
                  ? mutation.error.message
                  : "Не удалось сохранить план. Проверьте значения и повторите действие."}
            </div>
          )}
          <div className="plan-form-actions">
            {plan && (
              <button className="button button--ghost" type="button" disabled={mutation.isPending} onClick={cancel}>
                <X size={15} />
                Отмена
              </button>
            )}
            <button className="button button--primary" type="button" disabled={mutation.isPending} onClick={submit}>
              <Save size={15} />
              {mutation.isPending ? "Сохраняем…" : plan ? "Сохранить изменения" : "Создать план"}
            </button>
          </div>
        </section>
      )}
    </div>
  );
}

interface PlanDraftState {
  key: string;
  values: PlanFormValues;
  errors: PlanFormErrors;
  editing: boolean;
}
