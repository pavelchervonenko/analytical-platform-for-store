import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, TriangleAlert } from "lucide-react";
import { useState, type FormEvent } from "react";
import { isApiClientError } from "../api/client";
import { QueryError } from "../shared/QueryState";
import { payrollCategoryOptions } from "./classification";
import {
  decideCatalogReview, getCatalogReviewCategories, getCatalogReviewQueue,
  type CatalogReviewCategory, type CatalogReviewCondition,
  type CatalogReviewItem
} from "./catalog-review-api";
import type { PayrollCategory } from "./api";
import "./operations.css";

const queueKey = ["admin", "catalog-product-reviews"] as const;
const categoryKey = ["admin", "catalog-product-review-categories"] as const;
const conditions: { value: CatalogReviewCondition; label: string }[] = [
  { value: "UNKNOWN", label: "Не установлено" },
  { value: "NEW", label: "Новый" },
  { value: "ASIS", label: "Активированный / как есть" },
  { value: "USED", label: "Б/у" },
  { value: "NOT_APPLICABLE", label: "Не относится" }
];

function ReviewForm({ item, categories, onSaved }: {
  item: CatalogReviewItem; categories: CatalogReviewCategory[];
  onSaved: (message: string) => Promise<void>;
}) {
  const [analytics, setAnalytics] = useState(item.assignedAnalyticsCategoryCode ?? "");
  const [payroll, setPayroll] = useState<PayrollCategory | "">(
    (item.assignedPayrollCategoryCode as PayrollCategory | null) ?? "");
  const [condition, setCondition] = useState<CatalogReviewCondition>(
    (item.assignedConditionType as CatalogReviewCondition | null) ?? "UNKNOWN");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const mutation = useMutation({
    mutationFn: () => decideCatalogReview(item.productId, {
      expectedProductVersion: item.productVersion,
      analyticsCategoryCode: analytics, payrollCategoryCode: payroll as PayrollCategory,
      conditionType: condition, reason: reason.trim()
    }),
    onSuccess: async (result) => {
      setError(null);
      await onSaved(`Сохранено. Переклассифицировано позиций: ${result.reclassifiedItems}.`);
    },
    onError: (value) => setError(isApiClientError(value)
      ? value.message : "Не удалось сохранить категории. Обновите очередь и повторите проверку.")
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (analytics && payroll && reason.trim()) mutation.mutate();
  };
  const category = categories.find((value) => value.code === analytics);

  return <form onSubmit={submit} className="catalog-review-form">
    <div className="panel__heading"><div><p className="eyebrow">Первая продажа {item.firstSaleDate}</p>
      <h2>{item.name}</h2></div></div>
    <p>Код LiveSklad: {item.code || "не указан"}. Проданных строк: {item.saleItemCount}.
      {item.sourceGroupPath ? ` Группа: ${item.sourceGroupPath}.` : " Группа в приложении неизвестна."}
      {` Тип: ${item.sourceKind}.`}</p>
    <p>Алгоритм предлагает: <strong>{item.suggestedAnalyticsCategoryCode || "нет уверенной подсказки"}</strong>.
      Это не назначение — проверьте товар перед сохранением.</p>
    <label className="field"><span>Аналитическая категория</span>
      <select required disabled={!!item.assignedAnalyticsCategoryCode} value={analytics} onChange={(event) => setAnalytics(event.target.value)}>
        <option value="">Выберите категорию</option>
        {categories.map((value) => <option key={value.code} value={value.code}>
          {value.name} ({value.code})</option>)}
      </select>
    </label>
    {category && <p>Зарплатная категория по умолчанию для группы: {category.defaultPayrollCategoryCode}.
      Выберите и подтвердите её отдельно для этого товара.</p>}
    <label className="field"><span>Зарплатная категория</span>
      <select required disabled={!!item.assignedPayrollCategoryCode} value={payroll} onChange={(event) => setPayroll(event.target.value as PayrollCategory | "")}>
        <option value="">Выберите категорию</option>
        {payrollCategoryOptions.map((value) => <option key={value.value} value={value.value}>
          {value.label}</option>)}
      </select>
    </label>
    <label className="field"><span>Состояние товара</span>
      <select disabled={!!item.assignedConditionType} value={condition} onChange={(event) => setCondition(event.target.value as CatalogReviewCondition)}>
        {conditions.map((value) => <option key={value.value} value={value.value}>{value.label}</option>)}
      </select>
    </label>
    <label className="field"><span>Основание решения</span>
      <textarea required maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)}
        placeholder="Почему выбраны эти категории" />
    </label>
    {(item.assignedAnalyticsCategoryCode || item.assignedPayrollCategoryCode) &&
      <p>Уже назначенная категория зафиксирована; здесь можно только дополнить недостающее
        назначение или повторить сверку. Исправление принятого решения выполняется отдельно.</p>}
    <p><TriangleAlert size={16} /> Уже загруженные продажи этого нового товара получат выбранную
      аналитическую категорию; суммы продаж не меняются. Если у месяца уже утверждена или
      выплачена зарплата, сохранение будет заблокировано.</p>
    {error && <p className="form-error" role="alert">{error}</p>}
    <button className="button button--primary" disabled={!analytics || !payroll || !reason.trim() || mutation.isPending}>
      {mutation.isPending ? "Сохраняем…" : "Подтвердить обе категории"}
    </button>
  </form>;
}

export function CatalogProductReviewPanel() {
  const queryClient = useQueryClient();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const queue = useQuery({ queryKey: queueKey, queryFn: getCatalogReviewQueue });
  const categories = useQuery({ queryKey: categoryKey, queryFn: getCatalogReviewCategories });
  if (queue.isPending || categories.isPending) return <div className="panel-loader"><span className="spinner" />Проверяем новые товары…</div>;
  if (queue.isError) return <QueryError error={queue.error} onRetry={() => void queue.refetch()} />;
  if (categories.isError) return <QueryError error={categories.error} onRetry={() => void categories.refetch()} />;
  const items = queue.data.items;
  const selected = items.find((item) => item.productId === selectedId) ?? items[0];
  const saved = async (message: string) => {
    setSelectedId(null);
    setNotice(message);
    await queryClient.invalidateQueries();
  };
  return <div className="classification-layout catalog-review-layout">
    <section className="panel classification-main">
      <div className="panel__heading"><div><p className="eyebrow">Каталог LiveSklad</p>
        <h2>Новые товары на проверке</h2></div><span>{items.length}{queue.data.hasMore ? "+" : ""}</span></div>
      {notice && <p role="status"><CheckCircle2 size={16} /> {notice}</p>}
      {!queue.data.activationFrom && <p>Дата включения новой классификации ещё не настроена.</p>}
      {items.length === 0 && queue.data.activationFrom && <div className="panel-empty">
        <CheckCircle2 size={28} /><strong>Очередь пуста</strong>
        <p>Все новые проданные товары имеют подтверждённые назначения.</p></div>}
      {items.length > 0 && <div className="classification-list catalog-review-list">
        {items.map((item) => <button key={item.productId} type="button"
          aria-current={selected?.productId === item.productId ? "true" : undefined}
          onClick={() => { setSelectedId(item.productId); setNotice(null); }}>
          <strong>{item.name}</strong><small>{item.code || item.externalId} · {item.firstSaleDate}</small>
        </button>)}
      </div>}
      {queue.data.hasMore && <p>Показаны первые 100. После сохранения решений очередь обновится.</p>}
    </section>
    {selected && <section className="panel catalog-review-editor" key={selected.productId}>
      <ReviewForm item={selected} categories={categories.data} onSaved={saved} />
    </section>}
  </div>;
}
