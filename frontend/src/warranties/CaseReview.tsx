import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Check, PackageSearch } from "lucide-react";
import { isApiClientError, type EtaggedResource } from "../api/client";
import { InlineQueryError, PanelSkeleton } from "../shared/QueryState";
import { formatDate } from "../shared/date";
import { formatNumber } from "../shared/format";
import {
  caseKeys, getCaseDetail, getCaseEstimates, getCaseQueue, previewCaseDecision,
  saveCaseDecision, type CaseAttachDecision, type CaseAttachDetail, type CaseAttachItem
} from "./caseApi";
import "./caseStyles.css";

const proposalLabels: Record<CaseAttachItem["proposedTarget"], string> = {
  IPHONE: "Предположение: iPhone", SAMSUNG: "Предположение: Samsung",
  CONFLICT: "Конфликт: оба бренда", NONE: "Телефон в чеке не найден"
};
const targetLabels: Record<CaseAttachDecision["targetCode"], string> = {
  CASE_APPLE_IPHONE: "Чехол iPhone", CASE_SAMSUNG: "Чехол Samsung",
  CASE_OTHER_DEVICE: "Другое устройство", GLASS_IPHONE: "Защита дисплея iPhone",
  GLASS_SAMSUNG: "Защита дисплея Samsung", GLASS_OTHER: "Защита дисплея другого телефона",
  FILM_PHONE: "Плёнка для телефона", FILM_NON_PHONE: "Плёнка не для телефона", DEFER: "Оставить на проверке",
  CHARGER_CABLE: "Зарядка для телефонного attach-rate", ACCESSORY_AIRPODS: "Аксессуар AirPods",
  ACCESSORY_APPLE_WATCH: "Аксессуар Apple Watch", NO_ATTACH: "Не участвует в attach-rate"
};

const categoryLabels: Record<CaseAttachItem["categoryCode"], string> = {
  OTHER_CASE: "Чехол: совместимость неизвестна", CASE_UNIVERSAL: "Универсальный чехол",
  GLASS_PHONE_UNRESOLVED: "Защита дисплея: телефон неизвестен", PROTECTIVE_FILM: "Защитная плёнка",
  CHARGER_CABLE: "Зарядка: требуется проверка назначения", ACCESSORY_AIRPODS: "Аксессуар AirPods",
  ACCESSORY_APPLE_WATCH: "Аксессуар Apple Watch"
};

const roleReview = (item: CaseAttachItem) =>
  ["CHARGER_CABLE", "ACCESSORY_AIRPODS", "ACCESSORY_APPLE_WATCH"].includes(item.categoryCode);

export function CaseReview({ storeId, periodStart, periodEnd }: {
  storeId: string; periodStart: string; periodEnd: string;
}) {
  const [filter, setFilter] = useState("OPEN");
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [refreshFailed, setRefreshFailed] = useState(false);
  const client = useQueryClient();
  const refreshAfterSave = async () => {
    setNotice("Решение сохранено. Обновляем показатели…");
    setRefreshFailed(false);
    try {
      await client.invalidateQueries({ queryKey: ["stores", storeId] }, { throwOnError: true });
      setNotice("Решение сохранено. Текущие показатели обновлены; история решений сохранена.");
      if (filter === "OPEN") setSelected(null);
    } catch {
      setNotice("");
      setRefreshFailed(true);
    }
  };
  const queue = useQuery({ queryKey: caseKeys.queue(storeId, filter, offset),
    queryFn: () => getCaseQueue(storeId, filter, offset) });
  const estimates = useQuery({ queryKey: caseKeys.estimates(storeId, periodStart, periodEnd),
    queryFn: () => getCaseEstimates(storeId, periodStart, periodEnd) });
  const detail = useQuery({ queryKey: caseKeys.detail(storeId, selected ?? ""),
    queryFn: () => getCaseDetail(storeId, selected!), enabled: selected != null,
    refetchOnWindowFocus: false });
  return <>
    <div className="case-estimates" aria-label="Оценка чехлов с неизвестной совместимостью">
      <strong>Чехлы без подтверждённой модели</strong>
      <p>Продажа рядом с телефоном — только предположение. Она не меняет официальный attach-rate.</p>
      {estimates.isPending ? <PanelSkeleton rows={1} /> : estimates.isError
        ? <InlineQueryError error={estimates.error} onRetry={() => void estimates.refetch()} />
        : <div className="case-estimates__rates">{estimates.data.rates.map((rate) => <div key={rate.metricCode}>
          <strong>{rate.metricCode === "CASE_APPLE_IPHONE" ? "iPhone" : "Samsung"}</strong>
          <span>Подтверждено: {formatNumber(rate.confirmedQuantity)} шт. / {formatNumber(rate.denominatorQuantity)} телефонов</span>
          <span>По чекам предполагается ещё: {formatNumber(rate.inferredQuantity)} шт.</span>
          <small>Attach-rate: {rate.confirmedRatePerHundred == null ? "—" : `${formatNumber(rate.confirmedRatePerHundred)}%`}
            {rate.indicativeRatePerHundred == null ? "" : ` · с предположением: ${formatNumber(rate.indicativeRatePerHundred)}%`}</small>
        </div>)}</div>}
      {estimates.data && <p className="warranty-muted">За выбранный период: конфликтов {estimates.data.conflictCount}, без телефона в чеке {estimates.data.unresolvedCount}.</p>}
      {estimates.data && estimates.data.unresolvedReturnCount > 0 && <p className="warranty-warning" role="alert">Возвратов аксессуаров без надёжной связи с исходной продажей: {estimates.data.unresolvedReturnCount}. Они не вычтены из подтверждённого attach-rate; показатель может быть завышен.</p>}
    </div>
    {notice && <p className="warranty-success" role="status"><Check size={16} />{notice}</p>}
    {refreshFailed && <div role="alert" className="warranty-warning">Решение сохранено, но не все показатели удалось обновить.
      Не сохраняйте его повторно. <button className="button button--ghost" onClick={() => void refreshAfterSave()}>Обновить показатели</button></div>}
    <div className="warranty-workspace__body">
      <aside className="warranty-queue" aria-label="Аксессуары на проверке">
        <label className="field"><span>Показать</span><select value={filter} onChange={(event) => {
          setFilter(event.target.value); setOffset(0); setSelected(null); setNotice("");
        }}><option value="OPEN">Требуют проверки</option><option value="RESOLVED">Разобранные</option><option value="ALL">Все аксессуары</option></select></label>
        {queue.isPending ? <PanelSkeleton rows={3} /> : queue.isError
          ? <InlineQueryError error={queue.error} onRetry={() => void queue.refetch()} />
          : <><p className="warranty-muted warranty-meta"><span>Открыто: {queue.data.openCount}</span>
            <span>Конфликтов: {queue.data.conflictCount}</span></p>
            {queue.data.items.length === 0 && <p>Нет аксессуаров в этом списке.</p>}
            {queue.data.items.map((item) => <button key={item.id} className="warranty-queue__item"
              aria-pressed={selected === item.id} onClick={() => { setSelected(item.id); setNotice(""); }}>
              <span>Код {item.productCode ?? "—"} · продажа №{item.documentNumber ?? "—"}</span>
              <strong>{item.name}</strong><span>{categoryLabels[item.categoryCode]}</span>
              <span>{roleReview(item) ? "Проверьте назначение продажи и связанных возвратов" : proposalLabels[item.proposedTarget]}</span>
              <span>{formatNumber(item.quantity)} шт. · {formatDate(item.businessDate)}</span>
            </button>)}
            <nav className="warranty-pagination" aria-label="Страницы аксессуаров">
              <button className="button button--ghost" disabled={offset === 0}
                onClick={() => setOffset(Math.max(0, offset - 30))}>Назад</button>
              <button className="button button--ghost" disabled={offset + 30 >= queue.data.total}
                onClick={() => setOffset(offset + 30)}>Далее</button>
            </nav></>}
      </aside>
      <div className="warranty-review">
        {!selected ? <div className="warranty-empty"><PackageSearch size={36} /><h3>Выберите аксессуар</h3>
          <p>Проверьте маркировку или документы поставщика. Соседний телефон в чеке не доказывает совместимость.</p></div>
          : detail.isPending ? <PanelSkeleton rows={4} />
            : detail.isError ? <InlineQueryError error={detail.error} onRetry={() => void detail.refetch()} />
              : <CaseEditor key={`${selected}:${detail.data.etag}`} storeId={storeId} resource={detail.data}
                onReload={() => void detail.refetch()} onSaved={refreshAfterSave} />}
      </div>
    </div>
  </>;
}

function CaseEditor({ storeId, resource, onReload, onSaved }: {
  storeId: string; resource: EtaggedResource<CaseAttachDetail>;
  onReload: () => void; onSaved: () => Promise<void>;
}) {
  const source = resource.value.item;
  const [target, setTarget] = useState<CaseAttachDecision["targetCode"]>("DEFER");
  const [reason, setReason] = useState("");
  const [previewed, setPreviewed] = useState(false);
  const body: CaseAttachDecision = { targetCode: target, reason: reason.trim() };
  const preview = useMutation({ mutationFn: () => previewCaseDecision(storeId, source.id, body),
    onSuccess: () => setPreviewed(true) });
  const save = useMutation({ mutationFn: () => saveCaseDecision(storeId, source.id, resource.etag, body),
    onSuccess: onSaved });
  const changed = () => { setPreviewed(false); preview.reset(); save.reset(); };
  const failure = save.error ?? preview.error;
  const stale = isApiClientError(failure) && failure.status === 412;
  return <article>
    <header><p className="warranty-muted">Код {source.productCode ?? "—"} · продажа №{source.documentNumber ?? "—"} от {formatDate(source.businessDate)}</p>
      <p className="warranty-muted">{categoryLabels[source.categoryCode]}</p>
      <h3>{source.name} <span className="warranty-title-quantity">{formatNumber(source.quantity)} шт.</span></h3></header>
    <section className="warranty-step"><h4><span className="warranty-step__number">1</span> Основание для проверки</h4>
      {roleReview(source) ? <p className="warranty-reason">Автоматическое назначение требует сверки.
        Проверьте совместимость аксессуара и связанные возвраты. Наличие телефона в чеке не определяет назначение.
        Решение меняет attach-rate, но не денежную категорию и не постоянную совместимость товара.</p>
        : <p className="warranty-reason">{proposalLabels[source.proposedTarget]}. В чеке:
        {source.hasIphone ? " iPhone" : ""}{source.hasSamsung ? " Samsung" : ""}
        {!source.hasIphone && !source.hasSamsung ? " нет подходящего телефона" : ""}.
        Это не подтверждение назначения аксессуара.</p>}
      {source.decisionTarget && <p>Текущее решение: {targetLabels[source.decisionTarget as CaseAttachDecision["targetCode"]] ?? source.decisionTarget}
        {!source.decisionCurrent && " (устарело после изменения товара)"}.</p>}
    </section>
    <section className="warranty-step"><h4><span className="warranty-step__number">2</span> Укажите проверенную совместимость</h4>
      <fieldset className="warranty-editor-fields" disabled={preview.isPending || save.isPending || save.isSuccess}>
        <label className="field"><span>Решение</span><select value={target} onChange={(event) => {
          setTarget(event.target.value as CaseAttachDecision["targetCode"]); changed();
        }}>{source.allowedTargets.map((code) => <option key={code} value={code}>{targetLabels[code]}</option>)}</select></label>
        <label className="field"><span>{target === "DEFER" ? "Что нужно уточнить" : "Основание: маркировка, артикул или документ поставщика"}</span>
          <textarea rows={3} maxLength={1000} value={reason} onChange={(event) => { setReason(event.target.value); changed(); }}
            placeholder="Не используйте только соседство с телефоном в чеке" /></label>
      </fieldset>
    </section>
    <section className="warranty-step"><h4><span className="warranty-step__number">3</span> Проверьте результат</h4>
      {previewed && preview.data && <div className="warranty-preview">
        <p>{preview.data.nextTarget === "DEFER" ? "Аксессуар останется на проверке."
          : `Назначение для attach-rate: ${targetLabels[preview.data.nextTarget as CaseAttachDecision["targetCode"]] ?? preview.data.nextTarget}.`}</p>
        <p>Нетто с учётом связанных возвратов: {formatNumber(preview.data.netQuantity)} шт.</p>
        <p>Затронутые даты: {preview.data.affectedDates.map(formatDate).join(", ")}.</p>
        {preview.data.warnings.map((warning) => <p key={warning} className="warranty-warning">{warning}</p>)}
      </div>}
      {failure && <div role="alert" className="form-error">{stale
        ? "Данные изменились. Обновите карточку и проверьте решение заново."
        : isApiClientError(failure) ? failure.message : "Не удалось выполнить действие."}
        {stale && <button className="button button--ghost" onClick={onReload}>Обновить</button>}</div>}
      <footer className="warranty-editor-actions">{previewed
        ? <button className="button button--primary" disabled={save.isPending || save.isSuccess || stale} onClick={() => save.mutate()}>
          <Check size={16} />{save.isPending ? "Сохраняем…" : "Сохранить решение"}</button>
        : <button className="button button--primary" disabled={!reason.trim() || preview.isPending}
          onClick={() => preview.mutate()}>{preview.isPending ? "Проверяем…" : "Проверить результат"}<ArrowRight size={16} /></button>}</footer>
    </section>
    <details className="warranty-history"><summary>История решений ({resource.value.history.length})</summary>
      {resource.value.history.map((entry) => <article key={entry.id}><strong>{targetLabels[entry.targetCode as CaseAttachDecision["targetCode"]] ?? entry.targetCode}</strong>
        <small>{new Date(entry.createdAt).toLocaleString("ru-RU")} · ревизия {entry.revision}</small>
        <p>{entry.reason}</p></article>)}</details>
  </article>;
}
