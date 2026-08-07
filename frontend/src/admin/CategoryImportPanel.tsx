import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, FileJson, ShieldCheck, TriangleAlert } from "lucide-react";
import { useMemo, useState, type FormEvent } from "react";
import { isApiClientError } from "../api/client";
import { queryKeys } from "../api/queries";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { importProductCategories, type ProductCategoryImportResult } from "./api";
import { instantToReportingDateTime, parseCategoryAssignments, reportingDateTimeToInstant } from "./category-import";

const EXAMPLE = `[
  {
    "externalProductId": "4310",
    "productName": "Cable",
    "categoryCode": "CHARGER_CABLE",
    "conditionType": "NOT_APPLICABLE"
  }
]`;

function resultMessage(result: ProductCategoryImportResult): string {
  return `Проверено ${result.requested}; создано товаров ${result.productsCreated}; `
    + `новых назначений ${result.assignmentsCreated}; без изменений ${result.assignmentsUnchanged}.`;
}

export function CategoryImportPanel() {
  const { month } = useWorkspace();
  const queryClient = useQueryClient();
  const [connectionKey, setConnectionKey] = useState("livesklad-default");
  const [validFrom, setValidFrom] = useState(`${month}-01T00:00`);
  const [ruleVersion, setRuleVersion] = useState("");
  const [changeReason, setChangeReason] = useState("");
  const [source, setSource] = useState(EXAMPLE);
  const [confirmation, setConfirmation] = useState(false);
  const [result, setResult] = useState<ProductCategoryImportResult | null>(null);
  const parsed = useMemo(() => parseCategoryAssignments(source), [source]);
  const instant = reportingDateTimeToInstant(validFrom);
  const applySource = (value: string) => {
    setSource(value);
    setResult(null);
    const candidate = parseCategoryAssignments(value);
    if (!candidate.ok || !candidate.metadata) return;
    const artifactValidFrom = instantToReportingDateTime(candidate.metadata.validFrom);
    if (artifactValidFrom) setValidFrom(artifactValidFrom);
    setRuleVersion(candidate.metadata.ruleVersion);
    setChangeReason(candidate.metadata.changeReason ?? "");
  };
  const valid = connectionKey.trim().length > 0 && ruleVersion.trim().length > 0
    && instant !== null && parsed.ok && confirmation;

  const mutation = useMutation({
    mutationFn: () => {
      if (!instant || !parsed.ok) throw new Error("Invalid import state");
      return importProductCategories(connectionKey.trim(), {
        validFrom: instant,
        ruleVersion: ruleVersion.trim(),
        changeReason: changeReason.trim() || undefined,
        assignments: parsed.assignments
      });
    },
    onSuccess: async (value) => {
      setResult(value);
      setConfirmation(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.stores }),
        queryClient.invalidateQueries({ queryKey: ["admin", "sync-readiness"] })
      ]);
    }
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!valid || !parsed.ok) return;
    if (window.confirm(`Импортировать назначений: ${parsed.assignments.length}?`)) {
      setResult(null);
      mutation.mutate();
    }
  };
  const error = mutation.isError
    ? isApiClientError(mutation.error) ? mutation.error.message : "Не удалось выполнить импорт."
    : null;

  return <div className="category-import-layout">
    <section className="panel category-import-main">
      <div className="panel__heading"><h2>Импорт справочника категорий</h2><FileJson /></div>
      <p className="category-import-lead">Загрузите подготовленный JSON-файл или вставьте список категорий. Перед импортом система проверит данные и покажет ошибки.</p>
      <form className="admin-form category-import-form" onSubmit={submit}>
        <label className="field"><span>Файл классификации</span><input type="file" accept="application/json,.json" onChange={(event) => {
          const input = event.currentTarget;
          const file = input.files?.[0];
          if (!file) return;
          void file.text().then((value) => applySource(value)).finally(() => { input.value = ""; });
        }} /><small>При загрузке полного артефакта дата, версия и причина заполнятся автоматически.</small></label>
        <div className="admin-form-grid">
          <label className="field"><span>Ключ подключения</span><input value={connectionKey} maxLength={120} required autoComplete="off" onChange={(event) => setConnectionKey(event.target.value)} placeholder="Например: primary-store" /></label>
          <label className="field"><span>Версия правила</span><input value={ruleVersion} maxLength={200} required autoComplete="off" onChange={(event) => setRuleVersion(event.target.value)} placeholder="customer-approved-2026-07-v1" /></label>
          <label className="field"><span>Действует с</span><input type="datetime-local" value={validFrom} required onChange={(event) => setValidFrom(event.target.value)} /><small>Часовой пояс: Europe/Kaliningrad</small></label>
          <label className="field"><span>Причина изменения</span><input value={changeReason} maxLength={2_000} onChange={(event) => setChangeReason(event.target.value)} placeholder="Необязательно, но рекомендуется" /></label>
        </div>
        <label className="field category-import-editor"><span>Данные категорий</span><textarea value={source} spellCheck={false} maxLength={5_000_000} onChange={(event) => applySource(event.target.value)} /></label>
        <div className={`category-import-validation ${parsed.ok ? "is-valid" : "is-invalid"}`} role="status">
          {parsed.ok ? <><CheckCircle2 /><span>Структура корректна: {parsed.assignments.length} назначений.</span></> : <><TriangleAlert /><span>{parsed.message}</span></>}
        </div>
        <label className="admin-switch category-import-confirm"><input type="checkbox" checked={confirmation} onChange={(event) => setConfirmation(event.target.checked)} /><span><strong>Подтверждаю согласованную версию</strong><small>Конфликтующая история остановит всю операцию без частичного сохранения.</small></span></label>
        {error && <p className="form-error" role="alert">{error}</p>}
        {result && <p className="admin-form-note" role="status"><CheckCircle2 />{resultMessage(result)}</p>}
        <footer><span className="category-import-count">Лимит: 10 000 записей</span><button className="button button--primary" disabled={!valid || mutation.isPending}>{mutation.isPending ? "Импортируем…" : "Проверить и импортировать"}</button></footer>
      </form>
    </section>
    <aside className="panel category-import-aside"><span className="context-icon"><ShieldCheck /></span><p className="eyebrow">Контроль риска</p><h2>Только для первичной настройки</h2><p>Операция не заменяет ежемесячную классификацию для расчета зарплаты. Используйте ее для заранее согласованного справочника внешних товаров.</p><div className="admin-safety-note"><TriangleAlert /><p><strong>Не вставляйте секреты.</strong><span>JSON должен содержать только идентификатор, название, категорию и состояние товара.</span></p></div></aside>
  </div>;
}
