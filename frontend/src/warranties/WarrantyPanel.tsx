import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Check, Search, ShieldCheck, X } from "lucide-react";
import { isApiClientError, type EtaggedResource } from "../api/client";
import { InlineQueryError, PanelSkeleton } from "../shared/QueryState";
import { formatDate } from "../shared/date";
import { formatNumber } from "../shared/format";
import {
  getWarrantyDetail, getWarrantyQueue, previewWarrantyDecision, saveWarrantyDecision,
  searchOriginalWarranties, searchWarrantyDevices, warrantyKeys,
  type WarrantyAction, type WarrantyCase, type WarrantyDecision, type WarrantyDetail, type WarrantyDevice
} from "./api";
import { caseKeys, getCaseQueue } from "./caseApi";
import { CaseReview } from "./CaseReview";
import { allocationTotal, warrantyActions, warrantyReasons, warrantyStatus } from "./presentation";
import "./styles.css";

export function WarrantyPanel({ storeId, periodStart, periodEnd }: {
  storeId: string; periodStart: string; periodEnd: string;
}) {
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const summary = useQuery({ queryKey: warrantyKeys.queue(storeId, "OPEN", 0), queryFn: () => getWarrantyQueue(storeId) });
  const cases = useQuery({ queryKey: caseKeys.queue(storeId, "ALL", 0), queryFn: () => getCaseQueue(storeId, "ALL") });
  if (summary.isPending || cases.isPending) return <PanelSkeleton rows={1} />;
  if (summary.isError && !cases.data?.total) return <InlineQueryError error={summary.error} onRetry={() => void summary.refetch()} />;
  if (cases.isError && !summary.data?.enabled) return <InlineQueryError error={cases.error} onRetry={() => void cases.refetch()} />;
  const warrantyEnabled = summary.data?.enabled ?? false;
  if (!warrantyEnabled && !cases.data?.total) return null;
  const unavailable = summary.isError ? "гарантий" : cases.isError ? "аксессуаров" : null;
  return <section className="warranty-banner" aria-label="Разбор спорных продаж">
    <ShieldCheck aria-hidden="true" />
    <div><strong>На проверке: гарантий {summary.isError ? "—" : warrantyEnabled ? summary.data.total : 0}, аксессуаров {cases.isError ? "—" : cases.data.openCount}</strong>
      <p>Подтверждённые решения пересчитывают attach-rate. Соседство чехла и телефона в чеке — лишь предположение.</p>
      {unavailable && <div className="warranty-banner__outage" role="alert">
        <span>Очередь {unavailable} временно недоступна. Другая вкладка работает.</span>
        <button className="button button--ghost" onClick={() => void (summary.isError ? summary.refetch() : cases.refetch())}>Повторить загрузку</button></div>}
    </div>
    <button ref={trigger} className="button button--ghost" onClick={() => setOpen(true)}>
      Разобрать конфликты<ArrowRight size={16} />
    </button>
    {open && <WarrantyWorkspace key={storeId} storeId={storeId} periodStart={periodStart} periodEnd={periodEnd}
      warrantyEnabled={warrantyEnabled} onClose={() => { setOpen(false); window.requestAnimationFrame(() => trigger.current?.focus()); }} />}
  </section>;
}

function WarrantyWorkspace({ storeId, periodStart, periodEnd, warrantyEnabled, onClose }: {
  storeId: string; periodStart: string; periodEnd: string; warrantyEnabled: boolean; onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [tab, setTab] = useState<"warranties" | "cases">(warrantyEnabled ? "warranties" : "cases");
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
      setNotice("Решение сохранено. Текущие показатели обновлены; опубликованные снимки сохраняют свою версию.");
      if (filter === "OPEN") setSelected(null);
    } catch {
      setNotice("");
      setRefreshFailed(true);
    }
  };
  const [reloadVersion, setReloadVersion] = useState(0);
  const queue = useQuery({ queryKey: warrantyKeys.queue(storeId, filter, offset),
    queryFn: () => getWarrantyQueue(storeId, filter, offset), enabled: warrantyEnabled && tab === "warranties" });
  const detail = useQuery({ queryKey: warrantyKeys.detail(storeId, selected ?? ""),
    queryFn: () => getWarrantyDetail(storeId, selected!), enabled: selected != null, refetchOnWindowFocus: false });
  useEffect(() => { dialog.current?.showModal(); }, []);
  return <dialog className="warranty-workspace" ref={dialog} aria-labelledby="warranty-title" onCancel={(event) => {
    event.preventDefault(); onClose();
  }}>
    <header className="warranty-workspace__header"><div><h2 id="warranty-title">Разбор спорных продаж</h2>
      <p>Подтвердите назначение товара и проверьте результат перед сохранением.</p></div>
      <button className="icon-button" onClick={onClose} aria-label="Закрыть разбор продаж"><X /></button>
    </header>
    <nav className="warranty-tabs" role="tablist" aria-label="Тип проверки">
      {warrantyEnabled && <button role="tab" aria-selected={tab === "warranties"}
        onClick={() => setTab("warranties")}>Гарантии</button>}
      <button role="tab" aria-selected={tab === "cases"}
        onClick={() => setTab("cases")}>Аксессуары</button>
    </nav>
    {tab === "warranties" ? <>
    {notice && <p className="warranty-success" role="status"><Check size={16} />{notice}</p>}
    {refreshFailed && <div role="alert" className="warranty-warning">Решение сохранено, но не все показатели удалось обновить.
      Не сохраняйте его повторно. <button className="button button--ghost" onClick={() => void refreshAfterSave()}>Обновить показатели</button></div>}
    <div className="warranty-workspace__body">
      <aside className="warranty-queue" aria-label="Документы с гарантиями">
        <label className="field"><span>Показать</span><select value={filter} onChange={(e) => {
          setFilter(e.target.value); setOffset(0); setSelected(null); setNotice("");
        }}><option value="OPEN">Требуют проверки</option><option value="RESOLVED">Разобранные и исключённые</option><option value="WARNINGS">Предупреждения</option><option value="ALL">Все гарантии</option></select></label>
        {queue.isPending ? <PanelSkeleton rows={3} /> : queue.isError
          ? <InlineQueryError error={queue.error} onRetry={() => void queue.refetch()} />
          : <><p className="warranty-muted warranty-meta"><span>Гарантий: {queue.data.total}</span><span>Документов: {queue.data.documentCount}</span></p>
            {queue.data.items.length === 0 && <p>Нет гарантий в этом списке.</p>}
            {queue.data.items.map((item) => <button key={item.id} className="warranty-queue__item"
              aria-pressed={selected === item.id} onClick={() => { setSelected(item.id); setNotice(""); }}>
              <span>{item.documentKind === "RETURN" ? "Возврат" : "Продажа"} №{item.documentNumber ?? item.documentExternalId}</span>
              <strong>{item.name}</strong><span>Количество: {formatNumber(item.quantity)} шт.</span>
              <span>Дата: {formatDate(item.businessDate)}</span>
              {item.state !== "CONFLICT" && <small>{warrantyStatus(item)}</small>}
            </button>)}
            <nav className="warranty-pagination" aria-label="Страницы гарантий">
              <button className="button button--ghost" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - 30))}>Назад</button>
              <button className="button button--ghost" disabled={offset + 30 >= queue.data.total} onClick={() => setOffset(offset + 30)}>Далее</button>
            </nav></>}
      </aside>
      <div className="warranty-review">
        {!selected ? <div className="warranty-empty"><ShieldCheck size={36} /><h3>Выберите гарантию из списка</h3>
          <p>Покажем причину проверки, устройства и результат решения до сохранения.</p></div>
          : detail.isPending ? <PanelSkeleton rows={5} />
            : detail.isError ? <InlineQueryError error={detail.error} onRetry={() => void detail.refetch()} />
              : <WarrantyEditor key={`${selected}:${detail.data.etag}:${reloadVersion}`} storeId={storeId} resource={detail.data}
                onReload={() => { void detail.refetch().then(() => setReloadVersion((value) => value + 1)); }} onSaved={refreshAfterSave} onSelectOriginal={setSelected} />}
      </div>
    </div>
    </> : <CaseReview storeId={storeId} periodStart={periodStart} periodEnd={periodEnd} />}
  </dialog>;
}

function WarrantyEditor({ storeId, resource, onSaved, onReload, onSelectOriginal }: {
  storeId: string; resource: EtaggedResource<WarrantyDetail>; onSaved: () => Promise<void>; onReload: () => void;
  onSelectOriginal: (id: string) => void;
}) {
  const { warranty: source, candidates, history, warnings } = resource.value;
  const [action, setAction] = useState<WarrantyAction>("ALLOCATE");
  const [alternativesOpen, setAlternativesOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [devices, setDevices] = useState<WarrantyDevice[]>(candidates);
  const [quantities, setQuantities] = useState<Record<string, string>>(() => Object.fromEntries(
    resource.value.allocations.filter((a) => a.deviceItemId != null).map((a) => [a.deviceItemId!, String(a.quantity)])
  ));
  const [original, setOriginal] = useState(source.originalWarrantyItemId);
  const [originalLabel, setOriginalLabel] = useState("");
  const [search, setSearch] = useState("");
  const [originalSearch, setOriginalSearch] = useState("");
  const [savedRequest, setSavedRequest] = useState<WarrantyDecision | null>(null);
  const deviceSearch = useMutation({ mutationFn: () => searchWarrantyDevices(storeId, search.trim()), onSuccess: (found) => {
    setDevices((current) => [...new Map([...current, ...found].map((device) => [device.id, device])).values()]);
  } });
  const originalQuery = useMutation({ mutationFn: () => searchOriginalWarranties(storeId, originalSearch.trim()) });
  const originalDevices = useMutation({ mutationFn: (id: string) => getWarrantyDetail(storeId, id), onSuccess: ({ value }) => {
    setDevices(value.candidates); setQuantities({});
  } });
  const preview = useMutation({ mutationFn: (request: WarrantyDecision) => previewWarrantyDecision(storeId, source.id, request),
    onSuccess: (_data, request) => setSavedRequest(request) });
  const save = useMutation({ mutationFn: () => saveWarrantyDecision(storeId, source.id, resource.etag, savedRequest!),
    onSuccess: onSaved });
  const total = allocationTotal(quantities);
  const valid = (action === "ALLOCATE" || reason.trim().length > 0) && (action !== "ALLOCATE" || (
    total === source.quantity && devices.some((d) => Number(quantities[d.id]) > 0)
    && Object.values(quantities).every((value) => value === "" || (Number.isFinite(Number(value)) && Number(value) >= 0))
    && (source.documentKind !== "RETURN" || original != null)
  ));
  const busy = save.isPending || save.isSuccess || preview.isPending;
  const resetPreview = () => { setSavedRequest(null); preview.reset(); save.reset(); };
  const chooseAction = (next: WarrantyAction) => {
    setAction(next);
    setReason("");
    resetPreview();
  };
  const request: WarrantyDecision = { action,
    reason: action === "ALLOCATE" && !reason.trim()
      ? "Связь подтверждена выбором устройств" : reason.trim(), originalWarrantyItemId: original,
    allocations: action === "ALLOCATE" ? devices.filter((d) => Number(quantities[d.id]) > 0)
      .map((d) => ({ deviceItemId: d.id, quantity: Number(quantities[d.id]), fingerprint: d.fingerprint })) : [] };
  const failure = [save, preview, deviceSearch, originalQuery, originalDevices].find((m) => m.isError)?.error;
  const stale = isApiClientError(failure) && failure.status === 412;
  return <article>
    <header><span className="warranty-muted warranty-meta"><span>{source.documentKind === "RETURN" ? "Возврат" : "Продажа"} №{source.documentNumber ?? source.documentExternalId}</span><span>Дата: {formatDate(source.businessDate)}</span></span>
      <h3>{source.name} <span className="warranty-title-quantity">{formatNumber(source.quantity)} шт.</span></h3>
      <p className="warranty-muted">Продавец: {source.financialEmployeeName ?? "Не определён"}</p>
    </header>
    <section className="warranty-step" aria-labelledby="warranty-cause-title">
      <h4 id="warranty-cause-title"><span className="warranty-step__number">1</span> Почему нужна проверка</h4>
      <p className="warranty-reason">{warrantyReasons[source.conflictCode ?? ""] ?? warrantyStatus(source)}</p>
    </section>
    <section className="warranty-step" aria-labelledby="warranty-choice-title">
      <h4 id="warranty-choice-title"><span className="warranty-step__number">2</span>{action === "ALLOCATE"
        ? source.documentKind === "RETURN" ? " Укажите возвращённую гарантию" : " Укажите устройство и количество"
        : action === "DEFER" ? " Что нужно уточнить" : " Почему гарантию нужно исключить"}</h4>
    <fieldset disabled={busy} className="warranty-editor-fields">
      {source.documentKind === "RETURN" && action === "ALLOCATE" && <section className="warranty-original">
        <h4>Исходная гарантия</h4>
        {original && <p>{originalLabel || "Есть связь с исходной гарантией"} <button className="button button--ghost"
          onClick={() => onSelectOriginal(original)}>Открыть исходную гарантию</button></p>}
        <div className="warranty-search"><label className="field"><span>Номер продажи или название гарантии</span><input value={originalSearch} maxLength={150}
          onChange={(e) => setOriginalSearch(e.target.value)} /></label><button className="button button--ghost"
          disabled={originalSearch.trim().length < 2 || originalQuery.isPending}
          onClick={() => originalQuery.mutate()}><Search size={16} />Найти гарантию</button></div>
        {originalQuery.data?.length === 0 && <p>Исходная гарантия не найдена. Уточните номер продажи.</p>}
        {originalQuery.data?.map((item: WarrantyCase) => <button className="warranty-original-choice" key={item.id} onClick={() => {
          setOriginal(item.id); setOriginalLabel(`${item.name}, продажа №${item.documentNumber ?? item.documentExternalId}`);
          resetPreview(); originalDevices.mutate(item.id);
        }}><strong>{item.name}</strong><span>Продажа №{item.documentNumber ?? item.documentExternalId} от {formatDate(item.businessDate)}</span><span>Количество: {formatNumber(item.quantity)} шт.</span>{item.state !== "CONFLICT" && <span>{warrantyStatus(item)}</span>}</button>)}
      </section>}
      {action === "ALLOCATE" && <>
        <p className="warranty-muted">Укажите количество напротив устройств. Название товара само по себе не подтверждает связь.</p>
        <div className="warranty-devices">{devices.map((device) => <label className="warranty-device" key={device.id}>
          <div><strong>{device.name}</strong><span>{device.deviceType === "USED" ? "Б/У" : "Новое"}, устройств: {formatNumber(device.quantity)}</span>
            <small>Продажа №{device.documentNumber ?? device.documentExternalId} от {formatDate(device.businessDate)}</small>
            <small>Продавец: {device.employeeName ?? "Не определён"}</small>
            <small>Гарантий: {formatNumber(device.allocatedQuantity)}</small>
            {device.returnedQuantity > 0 && <small>Возвращено устройств: {formatNumber(device.returnedQuantity)}</small>}</div>
          <span className="warranty-device__quantity">Гарантий<input type="number" min="0" step="0.001" inputMode="decimal"
            aria-label={`Количество гарантий: ${device.name}, продажа ${device.documentNumber ?? device.documentExternalId}`}
            value={quantities[device.id] ?? ""} placeholder="0" onChange={(e) => {
              setQuantities({ ...quantities, [device.id]: e.target.value }); resetPreview();
            }} /></span>
        </label>)}</div>
        {source.quantity === 1 && devices.length > 0 && <div className="warranty-quick-choices">{devices.map((device) =>
          <button key={device.id} className="button button--ghost" onClick={() => {
            setQuantities({ [device.id]: "1" }); resetPreview();
          }}>Выбрать {device.deviceType === "USED" ? "Б/У" : "новое"}: {device.name}</button>)}</div>}
        <p className="warranty-total" aria-live="polite">Указано гарантий: {formatNumber(total)} из {formatNumber(source.quantity)}
          {total !== source.quantity && <span> (осталось {formatNumber(source.quantity - total)})</span>}</p>
        <div className="warranty-search"><label className="field"><span>Не видите нужное устройство? Номер продажи или название</span>
          <input value={search} maxLength={150} onChange={(e) => setSearch(e.target.value)} /></label>
          <button className="button button--ghost" disabled={search.trim().length < 2 || deviceSearch.isPending}
            onClick={() => deviceSearch.mutate()}><Search size={16} />Найти устройство</button></div>
        {deviceSearch.data?.length === 0 && <p>Подходящие устройства не найдены. Проверьте номер или отложите решение.</p>}
        {(deviceSearch.data?.length ?? 0) >= 50 && <p>Показаны первые 50 результатов. Уточните номер продажи.</p>}
      </>}
      {action === "ALLOCATE" ? <details className="warranty-comment"><summary>Добавить комментарий (необязательно)</summary>
        <label className="field"><span>Комментарий к решению</span><textarea value={reason} maxLength={1000} rows={2}
          onChange={(e) => { setReason(e.target.value); resetPreview(); }} /></label>
      </details> : <label className="field"><span>{action === "EXCLUDE" ? "Причина исключения (обязательно)"
        : "Что нужно уточнить (обязательно)"}</span>
        <textarea value={reason} required maxLength={1000} rows={2} onChange={(e) => { setReason(e.target.value); resetPreview(); }} /></label>}
    </fieldset>
    <div className="warranty-alternatives">
      <button className="warranty-alternatives__toggle" type="button" aria-expanded={alternativesOpen}
        onClick={() => setAlternativesOpen((value) => !value)}>Не можете подтвердить связь? Другие действия</button>
      {alternativesOpen && <div className="warranty-alternatives__choices" role="group" aria-label="Другие действия с гарантией">
        {action !== "ALLOCATE" && <button className="button button--ghost" type="button" onClick={() => chooseAction("ALLOCATE")}>Вернуться к выбору устройства</button>}
        <button className="button button--ghost" type="button" aria-pressed={action === "DEFER"}
          onClick={() => chooseAction("DEFER")}>Отложить до уточнения</button>
        <button className="button button--ghost" type="button" aria-pressed={action === "EXCLUDE"}
          onClick={() => chooseAction("EXCLUDE")}>Исключить из показателя</button>
        <p className="warranty-muted">Отложенная гарантия остаётся на проверке. Исключайте только с подтверждённой причиной; выручка и прибыль сохранятся.</p>
      </div>}
    </div>
    </section>
    {warnings.length > 0 && <ul className="warranty-warning">{warnings.map((text) => <li key={text}>{text}</li>)}</ul>}
    <section className="warranty-step" aria-labelledby="warranty-result-title">
      <h4 id="warranty-result-title"><span className="warranty-step__number">3</span> Проверьте результат и сохраните</h4>
      {!savedRequest && <p className="warranty-muted">Сначала проверьте, как решение изменит гарантийный показатель. Финансовые суммы останутся в документе LiveSklad.</p>}
    {preview.data && savedRequest && <section className="warranty-preview" aria-label="Результат решения">
      <h5>После сохранения</h5>
      {preview.data.allocations.map((allocation) => {
        const device = devices.find((d) => d.id === allocation.deviceItemId);
        return <p className="warranty-preview__allocation" key={allocation.deviceItemId}><span>{allocation.deviceType === "USED" ? "Гарантия Б/У" : "Гарантия новые"}: {source.documentKind === "RETURN" ? "−" : "+"}{formatNumber(allocation.quantity)}</span><small>Дата продажи: {formatDate(allocation.businessDate)}</small><small>Продавец: {device?.employeeName ?? "Не определён"}</small></p>;
      })}
      {preview.data.action === "EXCLUDE" && <p>Гарантия будет исключена из гарантийного показателя с указанной причиной.</p>}
      {preview.data.action === "DEFER" && <p>Гарантия останется в очереди. Затронутые показатели останутся предварительными.</p>}
      {preview.data.affectedDates.length > 0 && <p>Пересчёт периодов: {preview.data.affectedDates.map(formatDate).join(", ")}.</p>}
      {preview.data.warnings.map((text) => <p key={text} className="warranty-warning">{text}</p>)}
      <p className="warranty-muted">Выручка и прибыль остаются в фактических документах LiveSklad.</p>
    </section>}
    {failure && <div role="alert" className="form-error">{stale
      ? "Данные изменились. Обновите карточку и проверьте решение заново."
      : isApiClientError(failure) ? failure.message : "Не удалось выполнить действие. Повторите попытку."}
      {stale && <button className="button button--ghost" onClick={onReload}>Обновить карточку</button>}</div>}
    </section>
    <footer className="warranty-editor-actions">
      {savedRequest ? <button className="button button--primary" disabled={busy || stale} onClick={() => save.mutate()}>
        <Check size={16} />{save.isPending ? "Сохраняем…" : "Сохранить решение"}</button>
        : <button className="button button--primary" disabled={!valid || busy} onClick={() => preview.mutate(request)}>
          {preview.isPending ? "Проверяем…" : "Проверить результат"}<ArrowRight size={16} /></button>}
    </footer>
    <details className="warranty-history"><summary>История решений ({history.length})</summary>
      {history.length === 0 ? <p>Ручных решений ещё не было.</p> : history.map((entry) => <article key={entry.id}>
        <strong>{warrantyActions[entry.action as WarrantyAction] ?? entry.action}</strong>
        <small>Версия решения: {entry.revision}</small>
        <p>{entry.reason}</p><small>Решил: {entry.actorName}</small><small>Дата решения: {new Date(entry.createdAt).toLocaleString("ru-RU")}</small>
        {entry.allocations.map((a) => <p key={a.deviceItemId}>{a.deviceType === "USED" ? "Б/У" : "Новые"}: {formatNumber(a.quantity)}, дата продажи: {formatDate(a.businessDate)}</p>)}
      </article>)}
    </details>
  </article>;
}
