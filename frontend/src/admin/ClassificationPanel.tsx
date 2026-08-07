import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, PackageSearch, TriangleAlert } from "lucide-react";
import { useState, type FormEvent } from "react";
import { isApiClientError } from "../api/client";
import { getPayrollReadiness, queryKeys } from "../api/queries";
import { formatMoney, formatNumber } from "../shared/format";
import { QueryError } from "../shared/QueryState";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { classifyProducts, type PayrollCategory } from "./api";
import {
  buildClassificationAssignments,
  payrollCategoryOptionLabel,
  payrollCategoryOptions,
  recommendedPayrollCategory,
  selectedPayrollCategory,
  type ClassificationSelections
} from "./classification";

export function ClassificationPanel() {
  const { selectedStore, month, asOfDate } = useWorkspace();
  const queryClient = useQueryClient();
  const readinessQuery = useQuery({
    queryKey: queryKeys.payrollReadiness(selectedStore.id, month),
    queryFn: () => getPayrollReadiness(selectedStore.id, month)
  });
  const [selected, setSelected] = useState<ClassificationSelections>({});
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const products = readinessQuery.data?.unmappedProducts ?? [];
  const assignments = buildClassificationAssignments(products, selected);
  const mutation = useMutation({
    mutationFn: () => classifyProducts(`${month}-01`, reason.trim(), assignments),
    onSuccess: async () => {
      setError(null);
      setReason("");
      setSelected({});
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ["stores", selectedStore.id, "payroll"]
        }),
        queryClient.invalidateQueries({
          queryKey: queryKeys.periodQuality(selectedStore.id, month, asOfDate)
        })
      ]);
    },
    onError: (value) => setError(
      isApiClientError(value)
        ? value.message
        : "Не удалось применить классификацию."
    )
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (assignments.length > 0 && reason.trim()) mutation.mutate();
  };

  if (readinessQuery.isPending) {
    return (
      <div className="panel-loader">
        <span className="spinner" />
        Проверяем классификацию…
      </div>
    );
  }
  if (readinessQuery.isError) {
    return (
      <QueryError
        error={readinessQuery.error}
        onRetry={() => void readinessQuery.refetch()}
      />
    );
  }
  const readiness = readinessQuery.data;

  return (
    <div className="classification-layout">
      <section className="panel classification-main">
        <div className="panel__heading">
          <div>
            <p className="eyebrow">{selectedStore.name}, {month}</p>
            <h2>Неразмеченные товары</h2>
          </div>
          <span>{products.length}</span>
        </div>

        {products.length === 0 ? (
          <div className="panel-empty">
            <CheckCircle2 size={28} />
            <strong>Классификация завершена</strong>
            <p>Все товары расчетного месяца имеют категорию для расчета зарплаты.</p>
          </div>
        ) : (
          <form onSubmit={submit}>
            <div className="classification-list">
              {products.map((product) => {
                const recommendation = recommendedPayrollCategory(product);
                const selectedCategory = selectedPayrollCategory(product, selected);
                return (
                  <article key={product.productId}>
                    <div>
                      <strong>{product.productName}</strong>
                      <small>
                        {formatNumber(product.netQuantity)} ед.,{" "}
                        {formatMoney(product.netRevenue)}, продажи{" "}
                        {product.firstSaleDate}—{product.lastSaleDate}
                      </small>
                      {recommendation ? (
                        <div className="classification-suggestion">
                          <span>Рекомендуем</span>
                          <strong>{payrollCategoryOptionLabel(recommendation)}</strong>
                          <small>
                            {product.suggestionReason
                              ?? "Проверьте рекомендацию перед применением."}
                          </small>
                        </div>
                      ) : (
                        <p className="classification-manual-note">
                          Требуется ручная классификация
                        </p>
                      )}
                    </div>
                    <label className="classification-choice">
                      <span className="sr-only">
                        Категория товара {product.productName}
                      </span>
                      <select
                        value={selectedCategory}
                        onChange={(event) => setSelected((current) => ({
                          ...current,
                          [product.productId]:
                            event.target.value as PayrollCategory | ""
                        }))}
                      >
                        <option value="">Не выбрана</option>
                        {payrollCategoryOptions.map((category) => (
                          <option key={category.value} value={category.value}>
                            {category.label}
                          </option>
                        ))}
                      </select>
                    </label>
                  </article>
                );
              })}
            </div>
            <label className="field classification-reason">
              <span>Причина изменения</span>
              <textarea
                value={reason}
                required
                maxLength={500}
                onChange={(event) => setReason(event.target.value)}
                placeholder="Например: проверены рекомендации за август"
              />
            </label>
            {error && <p className="form-error">{error}</p>}
            <footer>
              <small>
                Предварительно выбрано: {assignments.length} из {products.length}.
                Проверьте рекомендации перед подтверждением.
              </small>
              <button
                className="button button--primary"
                disabled={
                  assignments.length === 0
                  || !reason.trim()
                  || mutation.isPending
                }
              >
                {mutation.isPending
                  ? "Применяем…"
                  : "Подтвердить выбранные категории"}
              </button>
            </footer>
          </form>
        )}
      </section>

      <aside className="panel classification-readiness">
        <span className="context-icon"><PackageSearch /></span>
        <p className="eyebrow">Готовность зарплаты</p>
        <h2>
          {readiness.status === "READY"
            ? "Готово"
            : readiness.status === "NEEDS_CORRECTION"
              ? "Нужны исправления"
              : "Расчет заблокирован"}
        </h2>
        <dl>
          <div>
            <dt>Неразмечено</dt>
            <dd>{readiness.unmappedItemCount}</dd>
          </div>
          <div>
            <dt>Без себестоимости</dt>
            <dd>{readiness.missingCostItemCount}</dd>
          </div>
          <div>
            <dt>Дней без смен</dt>
            <dd>{readiness.daysWithoutShift}</dd>
          </div>
        </dl>
        {readiness.missingCostItemCount > 0 && (
          <p className="admin-form-note admin-form-note--warning">
            <TriangleAlert />
            Себестоимость нельзя исправить здесь. Проверьте данные в учетной системе.
          </p>
        )}
        <small>
          Категория действует с первого числа выбранного месяца и не переписывает
          прошлые снимки.
        </small>
      </aside>
    </div>
  );
}
