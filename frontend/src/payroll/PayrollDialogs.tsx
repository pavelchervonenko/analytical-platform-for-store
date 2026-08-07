import { AlertTriangle, MinusCircle, X } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import type { PayrollAdjustmentInput, PayrollAdjustmentType } from "../api/contracts";
import { adjustmentTypeLabel, parsePayrollAmount, validateReason } from "./payroll-ui";

function ModalFrame({ title, description, busy, onClose, children }: { title: string; description: string; busy: boolean; onClose: () => void; children: ReactNode }) {
  const closeButton = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    closeButton.current?.focus();
  }, []);
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busy) onClose();
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [busy, onClose]);

  return <div className="payroll-modal-overlay" onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) onClose(); }}><section className="payroll-modal" role="dialog" aria-modal="true" aria-labelledby="payroll-dialog-title"><header><div><h2 id="payroll-dialog-title">{title}</h2><p>{description}</p></div><button ref={closeButton} className="icon-button" type="button" disabled={busy} onClick={onClose} aria-label="Закрыть"><X /></button></header>{children}</section></div>;
}

export function ReasonDialog({ title, description, confirmLabel, busy, error, danger = false, onClose, onConfirm }: { title: string; description: string; confirmLabel: string; busy: boolean; error?: string; danger?: boolean; onClose: () => void; onConfirm: (reason: string) => void }) {
  const [reason, setReason] = useState("");
  const [fieldError, setFieldError] = useState<string | null>(null);
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const validation = validateReason(reason);
    setFieldError(validation);
    if (!validation) onConfirm(reason.trim());
  };
  return <ModalFrame title={title} description={description} busy={busy} onClose={onClose}><form onSubmit={submit}><label className="payroll-field"><span>Причина</span><textarea autoFocus value={reason} maxLength={500} onChange={(event) => { setReason(event.target.value); setFieldError(null); }} aria-invalid={Boolean(fieldError)} placeholder="Коротко опишите причину изменения" /><small>{reason.length}/500</small>{fieldError && <em role="alert">{fieldError}</em>}</label>{error && <div className="form-alert" role="alert">{error}</div>}<footer><button className="button button--ghost" type="button" disabled={busy} onClick={onClose}>Отмена</button><button className={`button ${danger ? "payroll-button--danger" : "button--primary"}`} type="submit" disabled={busy}>{busy ? "Сохраняем…" : confirmLabel}</button></footer></form></ModalFrame>;
}

export function AdjustmentDialog({ employees, runVersion, busy, error, onClose, onConfirm }: { employees: { employeeId: string; employeeName: string }[]; runVersion: number; busy: boolean; error?: string; onClose: () => void; onConfirm: (input: PayrollAdjustmentInput) => void }) {
  const [employeeId, setEmployeeId] = useState(employees[0]?.employeeId ?? "");
  const [type, setType] = useState<PayrollAdjustmentType>("PENALTY");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const parsedAmount = parsePayrollAmount(amount);
    const reasonError = validateReason(reason);
    const nextErrors: Record<string, string> = {};
    if (!employeeId) nextErrors.employeeId = "Выберите сотрудника.";
    if (parsedAmount == null) nextErrors.amount = "Введите сумму от 0,01 ₽, не более двух знаков после запятой.";
    if (reasonError) nextErrors.reason = reasonError;
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length === 0 && parsedAmount != null) onConfirm({ employeeId, type, amount: parsedAmount, reason: reason.trim(), runVersion });
  };
  return <ModalFrame title="Добавить удержание" description="Удержание уменьшит выплату и сразу пересчитает текущую черновую версию." busy={busy} onClose={onClose}><form onSubmit={submit}><div className="payroll-form-grid"><label className="payroll-field payroll-field--wide"><span>Сотрудник</span><select value={employeeId} onChange={(event) => { setEmployeeId(event.target.value); setErrors((current) => ({ ...current, employeeId: "" })); }} aria-invalid={Boolean(errors.employeeId)}>{employees.length === 0 && <option value="">Нет доступных сотрудников</option>}{employees.map((employee) => <option key={employee.employeeId} value={employee.employeeId}>{employee.employeeName}</option>)}</select>{errors.employeeId && <em role="alert">{errors.employeeId}</em>}</label><label className="payroll-field"><span>Тип удержания</span><select value={type} onChange={(event) => setType(event.target.value as PayrollAdjustmentType)}>{(["PENALTY", "INVENTORY", "TAX"] as const).map((value) => <option key={value} value={value}>{adjustmentTypeLabel(value)}</option>)}</select></label><label className="payroll-field"><span>Сумма</span><div className="payroll-input-suffix"><input inputMode="decimal" value={amount} onChange={(event) => { setAmount(event.target.value); setErrors((current) => ({ ...current, amount: "" })); }} aria-invalid={Boolean(errors.amount)} placeholder="0,00" /><i>₽</i></div>{errors.amount && <em role="alert">{errors.amount}</em>}</label><label className="payroll-field payroll-field--wide"><span>Причина</span><textarea value={reason} maxLength={500} onChange={(event) => { setReason(event.target.value); setErrors((current) => ({ ...current, reason: "" })); }} aria-invalid={Boolean(errors.reason)} placeholder="Основание для удержания" />{errors.reason && <em role="alert">{errors.reason}</em>}</label></div><aside className="payroll-dialog-note"><MinusCircle /><span>Доступны только вычеты: штраф, инвентаризация и налог. Добавление ручных премий сейчас недоступно.</span></aside>{error && <div className="form-alert" role="alert">{error}</div>}<footer><button className="button button--ghost" type="button" disabled={busy} onClick={onClose}>Отмена</button><button className="button button--primary" type="submit" disabled={busy || employees.length === 0}>{busy ? "Пересчитываем…" : "Добавить удержание"}</button></footer></form></ModalFrame>;
}

export function ConfirmPayrollDialog({ title, description, confirmLabel, busy, error, danger = false, onClose, onConfirm }: { title: string; description: string; confirmLabel: string; busy: boolean; error?: string; danger?: boolean; onClose: () => void; onConfirm: () => void }) {
  return <ModalFrame title={title} description={description} busy={busy} onClose={onClose}><div className={`payroll-confirm-note ${danger ? "payroll-confirm-note--danger" : ""}`}><AlertTriangle /><p>{danger ? "Действие фиксируется в истории и не имеет обратного перехода." : "Перед выполнением система повторно проверит версию и актуальность исходных данных."}</p></div>{error && <div className="form-alert" role="alert">{error}</div>}<footer><button className="button button--ghost" type="button" disabled={busy} onClick={onClose}>Отмена</button><button className={`button ${danger ? "payroll-button--danger" : "button--primary"}`} type="button" disabled={busy} onClick={onConfirm}>{busy ? "Выполняем…" : confirmLabel}</button></footer></ModalFrame>;
}
