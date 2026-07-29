import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, PackageSearch, TriangleAlert } from "lucide-react";
import { useState, type FormEvent } from "react";
import { isApiClientError } from "../api/client";
import { getPayrollReadiness, queryKeys } from "../api/queries";
import { formatMoney, formatNumber } from "../shared/format";
import { QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { classifyProducts, type PayrollCategory } from "./api";

const categories: { value: PayrollCategory; label: string }[] = [
  { value: "TECH_TIER_1", label: "Техника · уровень 1" }, { value: "TECH_TIER_2", label: "Техника · уровень 2" },
  { value: "ACCESSORY", label: "Аксессуар" }, { value: "SERVICE", label: "Услуга" },
  { value: "PLAYSTATION_SUBSCRIPTION", label: "Подписка PlayStation" }, { value: "PAID_REPAIR", label: "Платный ремонт" },
  { value: "EXCLUDE", label: "Исключить из зарплаты" }
];

export function ClassificationPanel() {
  const { selectedStore, month, asOfDate } = useWorkspace();
  const queryClient = useQueryClient();
  const readinessQuery = useQuery({ queryKey: queryKeys.payrollReadiness(selectedStore.id, month), queryFn: () => getPayrollReadiness(selectedStore.id, month) });
  const [selected, setSelected] = useState<Record<string, PayrollCategory | "">>({});
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const products = readinessQuery.data?.unmappedProducts ?? [];
  const assignments = products.flatMap((product) => selected[product.productId] ? [{ productId: product.productId, categoryCode: selected[product.productId] as PayrollCategory }] : []);
  const mutation = useMutation({
    mutationFn: () => classifyProducts(`${month}-01`, reason.trim(), assignments),
    onSuccess: async () => { setError(null); setReason(""); await Promise.all([queryClient.invalidateQueries({ queryKey: queryKeys.payrollReadiness(selectedStore.id, month) }), queryClient.invalidateQueries({ queryKey: queryKeys.periodQuality(selectedStore.id, month, asOfDate) })]); },
    onError: (value) => setError(isApiClientError(value) ? value.message : "Не удалось применить классификацию.")
  });
  const submit = (event: FormEvent) => { event.preventDefault(); if (assignments.length > 0 && reason.trim()) mutation.mutate(); };

  if (readinessQuery.isPending) return <div className="panel-loader"><span className="spinner" />Проверяем классификацию…</div>;
  if (readinessQuery.isError) return <QueryError error={readinessQuery.error} onRetry={() => void readinessQuery.refetch()} />;
  const readiness = readinessQuery.data;

  return <div className="classification-layout">
    <section className="panel classification-main"><div className="panel__heading"><div><p className="eyebrow">{selectedStore.name} · {month}</p><h2>Неразмеченные товары</h2></div><span>{products.length}</span></div>
      {products.length === 0 ? <div className="panel-empty"><CheckCircle2 size={28} /><strong>Классификация завершена</strong><p>Все товары расчетного месяца имеют payroll-категорию.</p></div> : <form onSubmit={submit}><div className="classification-list">{products.map((product) => <article key={product.productId}><div><strong>{product.productName}</strong><small>{formatNumber(product.netQuantity)} ед. · {formatMoney(product.netRevenue)} · продажи {product.firstSaleDate}—{product.lastSaleDate}</small>{product.suggestionReason && <p>Подсказка: {product.suggestionReason}</p>}</div><label><span className="sr-only">Категория товара {product.productName}</span><select value={selected[product.productId] ?? ""} onChange={(event) => setSelected((current) => ({ ...current, [product.productId]: event.target.value as PayrollCategory | "" }))}><option value="">Не выбрана</option>{categories.map((category) => <option key={category.value} value={category.value}>{category.label}</option>)}</select></label></article>)}</div><label className="field classification-reason"><span>Причина изменения</span><textarea value={reason} required maxLength={500} onChange={(event) => setReason(event.target.value)} placeholder="Например: первичная классификация товаров за месяц" /></label>{error && <p className="form-error">{error}</p>}<footer><small>Будет назначено: {assignments.length} из {products.length}. Пакет применяется атомарно.</small><button className="button button--primary" disabled={assignments.length === 0 || !reason.trim() || mutation.isPending}>{mutation.isPending ? "Применяем…" : "Применить выбранные категории"}</button></footer></form>}
    </section>
    <aside className="panel classification-readiness"><span className="context-icon"><PackageSearch /></span><p className="eyebrow">Готовность зарплаты</p><h2>{readiness.status === "READY" ? "Готово" : readiness.status === "NEEDS_CORRECTION" ? "Нужны исправления" : "Расчет заблокирован"}</h2><dl><div><dt>Неразмечено</dt><dd>{readiness.unmappedItemCount}</dd></div><div><dt>Без себестоимости</dt><dd>{readiness.missingCostItemCount}</dd></div><div><dt>Дней без смен</dt><dd>{readiness.daysWithoutShift}</dd></div></dl>{readiness.missingCostItemCount > 0 && <p className="admin-form-note admin-form-note--warning"><TriangleAlert />Ручное исправление себестоимости публичным API пока не поддерживается. Проверьте данные источника.</p>}<small>Категория действует с первого числа выбранного месяца и не переписывает прошлые снимки.</small></aside>
  </div>;
}
