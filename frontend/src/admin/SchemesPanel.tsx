import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Calculator, History, Plus, ShieldCheck } from "lucide-react";
import { useState, type FormEvent } from "react";
import { isApiClientError } from "../api/client";
import { formatDate } from "../shared/date";
import { formatMoney, formatPercent } from "../shared/format";
import { QueryError } from "../shared/QueryState";
import { adminKeys, createPayrollScheme, createRatingScheme, getPayrollSchemes, getRatingSchemes, type PayrollSchemeInput, type RatingSchemeInput } from "./api";

const number = (form: FormData, name: string) => Number(form.get(name));
const errorText = (error: unknown) => isApiClientError(error) ? error.message : "Не удалось создать версию.";

function NumberField({ name, label, defaultValue, min = 0, max, step = "0.01" }: { name: string; label: string; defaultValue: number; min?: number; max?: number; step?: string }) {
  return <label className="field"><span>{label}</span><input name={name} type="number" required min={min} max={max} step={step} defaultValue={defaultValue} /></label>;
}

export function SchemesPanel() {
  const queryClient = useQueryClient();
  const ratingQuery = useInfiniteQuery({
    queryKey: adminKeys.ratingSchemes,
    initialPageParam: 0,
    queryFn: ({ pageParam }) => getRatingSchemes(pageParam),
    getNextPageParam: (lastPage) => lastPage.hasNext ? lastPage.page + 1 : undefined,
    select: (data) => data.pages.flatMap((page) => page.items)
  });
  const payrollQuery = useInfiniteQuery({
    queryKey: adminKeys.payrollSchemes,
    initialPageParam: 0,
    queryFn: ({ pageParam }) => getPayrollSchemes(pageParam),
    getNextPageParam: (lastPage) => lastPage.hasNext ? lastPage.page + 1 : undefined,
    select: (data) => data.pages.flatMap((page) => page.items)
  });
  const [ratingError, setRatingError] = useState<string | null>(null);
  const [payrollError, setPayrollError] = useState<string | null>(null);
  const ratingMutation = useMutation({ mutationFn: createRatingScheme, onSuccess: async () => { setRatingError(null); await Promise.all([queryClient.invalidateQueries({ queryKey: adminKeys.ratingSchemes }), queryClient.invalidateQueries({ queryKey: ["stores"] })]); }, onError: (error) => setRatingError(errorText(error)) });
  const payrollMutation = useMutation({ mutationFn: createPayrollScheme, onSuccess: async () => { setPayrollError(null); await Promise.all([queryClient.invalidateQueries({ queryKey: adminKeys.payrollSchemes }), queryClient.invalidateQueries({ queryKey: ["stores"] })]); }, onError: (error) => setPayrollError(errorText(error)) });

  if (ratingQuery.isPending || payrollQuery.isPending) return <div className="panel-loader"><span className="spinner" />Загружаем версии…</div>;
  const queryError = ratingQuery.error ?? payrollQuery.error;
  if (queryError) return <QueryError error={queryError} onRetry={() => { void ratingQuery.refetch(); void payrollQuery.refetch(); }} />;
  if (!ratingQuery.data || !payrollQuery.data) return <div className="panel-loader"><span className="spinner" />Загружаем версии…</div>;

  const submitRating = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = new FormData(event.currentTarget);
    const input: RatingSchemeInput = { code: String(form.get("code")), effectiveFrom: String(form.get("effectiveFrom")), contributionWeight: number(form, "contributionWeight"), efficiencyWeight: number(form, "efficiencyWeight"), structureWeight: number(form, "structureWeight"), attachWeight: number(form, "attachWeight"), accessoryStructureWeight: number(form, "accessoryStructureWeight"), serviceStructureWeight: number(form, "serviceStructureWeight"), minimumAttachDenominator: number(form, "minimumAttachDenominator"), scoreCap: number(form, "scoreCap"), minimumCoveragePercent: number(form, "minimumCoveragePercent") };
    if (Math.abs(input.contributionWeight + input.efficiencyWeight + input.structureWeight + input.attachWeight - 100) > 0.001 || Math.abs(input.accessoryStructureWeight + input.serviceStructureWeight - 100) > 0.001) { setRatingError("Основные веса и веса структуры должны отдельно давать 100%."); return; }
    ratingMutation.mutate(input);
  };
  const submitPayroll = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const form = new FormData(event.currentTarget); const effectiveFrom = String(form.get("effectiveFrom"));
    if (!effectiveFrom.endsWith("-01")) { setPayrollError("Версия зарплаты должна начинаться с первого числа месяца."); return; }
    const input: PayrollSchemeInput = { code: String(form.get("code")), effectiveFrom, achievedPercentage: number(form, "achievedPercentage"), missedPercentage: number(form, "missedPercentage"), achievedTier1Rate: number(form, "achievedTier1Rate"), missedTier1Rate: number(form, "missedTier1Rate"), achievedTier2Rate: number(form, "achievedTier2Rate"), missedTier2Rate: number(form, "missedTier2Rate"), advanceAmount: number(form, "advanceAmount") };
    payrollMutation.mutate(input);
  };

  return <>
    <div className="admin-scheme-grid">
    <section className="panel admin-scheme-card"><div className="panel__heading"><div><p className="eyebrow">Рейтинг сотрудников</p><h2>Версии формулы</h2></div><Calculator /></div><p className="admin-immutable-note"><ShieldCheck />Созданные версии неизменяемы. Дата действия определяет версию для отчета.</p><div className="admin-version-list">{ratingQuery.data.map((scheme, index) => <article key={scheme.id}><span>{index === 0 ? "Актуальная" : "Версия"}</span><div><strong>{scheme.code}</strong><small>с {formatDate(scheme.effectiveFrom)}</small></div><div><strong>{formatPercent(scheme.minimumCoveragePercent)}</strong><small>минимальное покрытие, максимум {scheme.scoreCap}</small></div></article>)}</div><details className="admin-create-scheme"><summary><Plus />Новая версия рейтинга</summary><form className="admin-form" onSubmit={submitRating}><div className="admin-form-grid"><label className="field"><span>Код версии</span><input name="code" required /></label><label className="field"><span>Действует с</span><input name="effectiveFrom" type="date" required /></label><NumberField name="contributionWeight" label="Вклад, %" defaultValue={35} max={100} /><NumberField name="efficiencyWeight" label="Эффективность, %" defaultValue={25} max={100} /><NumberField name="structureWeight" label="Структура, %" defaultValue={25} max={100} /><NumberField name="attachWeight" label="Допродажи, %" defaultValue={15} max={100} /><NumberField name="accessoryStructureWeight" label="Аксессуары внутри структуры, %" defaultValue={50} max={100} /><NumberField name="serviceStructureWeight" label="Услуги внутри структуры, %" defaultValue={50} max={100} /><NumberField name="minimumAttachDenominator" label="Минимум основных продаж" defaultValue={1} min={0.001} step="0.001" /><NumberField name="scoreCap" label="Макс. балл" defaultValue={150} min={100} /><NumberField name="minimumCoveragePercent" label="Мин. покрытие, %" defaultValue={70} max={100} /></div>{ratingError && <p className="form-error">{ratingError}</p>}<button className="button button--primary" disabled={ratingMutation.isPending}>Создать неизменяемую версию</button></form></details></section>
    <section className="panel admin-scheme-card"><div className="panel__heading"><div><p className="eyebrow">Зарплата</p><h2>Версии схемы</h2></div><History /></div><p className="admin-immutable-note"><ShieldCheck />Новая версия начинает действовать только с первого числа будущего месяца.</p><div className="admin-version-list">{payrollQuery.data.map((scheme, index) => <article key={scheme.id}><span>{index === 0 ? "Актуальная" : "Версия"}</span><div><strong>{scheme.code}</strong><small>с {formatDate(scheme.effectiveFrom)}</small></div><div><strong>{formatMoney(scheme.advanceAmount)}</strong><small>аванс</small></div></article>)}</div><details className="admin-create-scheme"><summary><Plus />Новая версия зарплаты</summary><form className="admin-form" onSubmit={submitPayroll}><div className="admin-form-grid"><label className="field"><span>Код версии</span><input name="code" required /></label><label className="field"><span>Действует с</span><input name="effectiveFrom" type="date" required /></label><NumberField name="achievedPercentage" label="Доля при плане, %" defaultValue={10} max={100} /><NumberField name="missedPercentage" label="Доля без плана, %" defaultValue={5} max={100} /><NumberField name="achievedTier1Rate" label="Категория 1 при плане, ₽" defaultValue={1000} /><NumberField name="missedTier1Rate" label="Категория 1 без плана, ₽" defaultValue={500} /><NumberField name="achievedTier2Rate" label="Категория 2 при плане, ₽" defaultValue={500} /><NumberField name="missedTier2Rate" label="Категория 2 без плана, ₽" defaultValue={250} /><NumberField name="advanceAmount" label="Аванс, ₽" defaultValue={50000} /></div>{payrollError && <p className="form-error">{payrollError}</p>}<button className="button button--primary" disabled={payrollMutation.isPending}>Создать неизменяемую версию</button></form></details></section>
    </div>
    {(ratingQuery.hasNextPage || payrollQuery.hasNextPage) && <nav className="admin-pagination" aria-label="Загрузка версий схем">{ratingQuery.hasNextPage && <button className="button button--ghost" type="button" disabled={ratingQuery.isFetchingNextPage} onClick={() => void ratingQuery.fetchNextPage()}>Еще версии рейтинга</button>}{payrollQuery.hasNextPage && <button className="button button--ghost" type="button" disabled={payrollQuery.isFetchingNextPage} onClick={() => void payrollQuery.fetchNextPage()}>Еще версии зарплаты</button>}</nav>}
  </>;
}
