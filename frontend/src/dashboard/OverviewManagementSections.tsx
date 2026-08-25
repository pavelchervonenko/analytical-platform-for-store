import { AlertCircle, ArrowRight, ChevronDown, TrendingUp, Users } from "lucide-react";
import { Link, useLocation } from "react-router";
import type {
  AttachRate,
  CategoryKpi,
  EmployeeAttachRatingEntry,
  EmployeeKpi,
  EmployeeRatingEntry,
  EmployeeRatingResult,
  PlanDirection,
  PlanProgress,
  StoreKpi
} from "../api/contracts";
import { attachRateLabels } from "../employees/rating-ui";
import { formatCompactMoney, formatMoney, formatNumber, formatPercent } from "../shared/format";

const attachMetricOrder = [
  "CASE_APPLE_IPHONE",
  "CHARGER_CABLE",
  "GLASS_IPHONE",
  "GLASS_CAMERA_IPHONE",
  "FILM_PHONE",
  "SETUP_SERVICE",
  "CASE_SAMSUNG",
  "GLASS_SAMSUNG",
  "GLASS_CAMERA_SAMSUNG",
  "ACCESSORY_PODS_WATCH",
  "ACCESSORY_IPAD",
  "WARRANTY_GENERIC_USED",
  "WARRANTY_GENERIC_NEW",
  "PREMIUM_PROTECTION"
] as const;

function findDirection(plan: PlanProgress | null | undefined, code: string): PlanDirection | null {
  return plan?.directions.find((direction) => direction.code === code) ?? null;
}

function findGroup(categories: CategoryKpi | undefined, code: string) {
  return categories?.groups.find((group) => group.groupCode === code);
}

function share(amount: number | undefined, revenue: number | undefined): number | null {
  if (amount == null || revenue == null || revenue === 0) return null;
  return amount * 100 / revenue;
}

function deltaTone(value: number | null | undefined): string {
  if (value == null || value === 0) return "neutral";
  return value > 0 ? "positive" : "negative";
}

function signedPoints(value: number | null | undefined): string {
  if (value == null) return "без плана";
  if (value === 0) return "0 п. п.";
  return `${value > 0 ? "+" : "−"}${formatNumber(Math.abs(value))} п. п.`;
}

function signedMoney(value: number | null | undefined): string {
  if (value == null) return "—";
  if (value === 0) return "0 ₽";
  return `${value > 0 ? "+" : "−"}${formatCompactMoney(Math.abs(value))}`;
}

function CommercialMetric({
  label,
  amount,
  quantity,
  revenue,
  direction
}: {
  label: string;
  amount: number | undefined;
  quantity: number | undefined;
  revenue: number | undefined;
  direction: PlanDirection | null;
}) {
  const actualAmount = amount ?? direction?.actualAmount;
  const actualShare = direction?.actualSharePercent ?? share(actualAmount, revenue);
  const gapPoints = direction?.shareGapPercentagePoints;
  const gapAmount = direction ? direction.actualAmount - direction.targetAmount : null;
  const completion = direction?.criterionCompletionPercent;

  return (
    <article className="overview-summary__metric overview-summary__metric--commercial">
      <span>{label}</span>
      <strong>{formatPercent(actualShare)}</strong>
      <small>{formatMoney(actualAmount)}, {formatNumber(quantity)} ед.</small>
      <span className={`delta delta--${deltaTone(gapPoints)}`}>{signedPoints(gapPoints)}</span>
      <div className="overview-summary__metric-plan">
        <span>{direction ? `План ${formatPercent(direction.targetSharePercent)}` : "План не задан"}</span>
        <strong>{direction ? `${signedMoney(gapAmount)} к плану` : "—"}</strong>
      </div>
      {completion != null && (
        <progress
          className="progress overview-summary__progress"
          value={Math.max(0, Math.min(completion, 100))}
          max={100}
          aria-label={`Выполнение плана: ${label}`}
        />
      )}
    </article>
  );
}

export function ManagementSummary({
  kpi,
  categories,
  plan
}: {
  kpi: StoreKpi | undefined;
  categories: CategoryKpi | undefined;
  plan: PlanProgress | null | undefined;
}) {
  const revenueDirection = findDirection(plan, "REVENUE");
  const accessoryDirection = findDirection(plan, "ACCESSORY");
  const serviceDirection = findDirection(plan, "SERVICE");
  const additionalDirection = findDirection(plan, "ADDITIONAL");
  const accessory = findGroup(categories, "ACCESSORY");
  const service = findGroup(categories, "SERVICE");
  const additional = findGroup(categories, "ADDITIONAL_REVENUE");
  const revenueCompletion = revenueDirection?.criterionCompletionPercent;

  return (
    <section className="overview-summary" aria-label="Главные показатели">
      <article className="overview-summary__primary">
        <span>Чистая выручка</span>
        <strong>{formatMoney(kpi?.netRevenue)}</strong>
        <div>
          <span>{formatNumber(kpi?.netQuantity)} ед.</span>
          <span>{revenueDirection ? `План месяца ${formatCompactMoney(revenueDirection.targetAmount)}` : "План месяца не задан"}</span>
        </div>
        {revenueCompletion != null && (
          <div className="overview-summary__revenue-plan">
            <progress
              className="progress"
              value={Math.max(0, Math.min(revenueCompletion, 100))}
              max={100}
              aria-label="Выполнение плана выручки"
            />
            <strong>{formatPercent(revenueCompletion)}</strong>
          </div>
        )}
      </article>
      <div className="overview-summary__metrics">
        <article className="overview-summary__metric">
          <span>Валовая прибыль</span>
          <strong>{formatMoney(kpi?.grossProfit)}</strong>
          <small>Маржа {formatPercent(kpi?.marginPercent)}</small>
          {!kpi?.dataQuality.completeCostData && <span className="quality-warning"><AlertCircle size={14} />Данные неполные</span>}
        </article>
        <CommercialMetric
          label="Допы"
          amount={additional?.metrics.netRevenue}
          quantity={additional?.metrics.netQuantity}
          revenue={kpi?.netRevenue}
          direction={additionalDirection}
        />
        <CommercialMetric
          label="Аксессуары"
          amount={accessory?.metrics.netRevenue}
          quantity={accessory?.metrics.netQuantity}
          revenue={kpi?.netRevenue}
          direction={accessoryDirection}
        />
        <CommercialMetric
          label="Услуги"
          amount={service?.metrics.netRevenue}
          quantity={service?.metrics.netQuantity}
          revenue={kpi?.netRevenue}
          direction={serviceDirection}
        />
      </div>
    </section>
  );
}

interface EmployeePerformanceRow {
  employee: EmployeeRatingEntry;
  grossProfit: number | null;
  completeCostData: boolean;
}

function visibleEmployees(rating: EmployeeRatingResult): EmployeeRatingEntry[] {
  return rating.employees
    .filter((employee) => employee.employeeActive && employee.assignmentActive && employee.participatesInRanking)
    .sort((left, right) => right.netRevenue - left.netRevenue || left.displayName.localeCompare(right.displayName, "ru-RU"));
}

function employeeRows(rating: EmployeeRatingResult, employeeKpi: EmployeeKpi): EmployeePerformanceRow[] {
  const kpiByEmployee = new Map(
    employeeKpi.employees
      .filter((employee) => employee.employeeId != null)
      .map((employee) => [employee.employeeId, employee])
  );
  return visibleEmployees(rating).map((employee) => {
    const kpi = kpiByEmployee.get(employee.employeeId);
    return {
      employee,
      grossProfit: kpi?.grossProfit ?? null,
      completeCostData: kpi?.dataQuality.completeCostData ?? false
    };
  });
}

function byAdditionalShare(rows: EmployeePerformanceRow[], direction: "highest" | "lowest"): EmployeePerformanceRow | null {
  const eligible = rows.filter(({ employee }) => employee.netRevenue > 0 && employee.additionalSharePercent != null);
  if (eligible.length === 0) return null;
  return eligible.reduce((selected, row) => {
    const selectedValue = selected.employee.additionalSharePercent ?? 0;
    const rowValue = row.employee.additionalSharePercent ?? 0;
    return direction === "highest"
      ? rowValue > selectedValue ? row : selected
      : rowValue < selectedValue ? row : selected;
  });
}

function TeamSummaryCard({ label, value, note, featured = false }: { label: string; value: string; note: string; featured?: boolean }) {
  return (
    <article className={`overview-team-summary__card ${featured ? "overview-team-summary__card--featured" : ""}`}>
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{note}</small>
    </article>
  );
}

export function EmployeePerformanceSection({
  rating,
  employeeKpi
}: {
  rating: EmployeeRatingResult;
  employeeKpi: EmployeeKpi;
}) {
  const location = useLocation();
  const rows = employeeRows(rating, employeeKpi);
  const leader = byAdditionalShare(rows, "highest");
  const focus = byAdditionalShare(rows, "lowest");
  const totalRevenue = rows.reduce((sum, row) => sum + row.employee.netRevenue, 0);
  const completeGrossProfit = rows.every((row) => row.completeCostData && row.grossProfit != null);
  const totalGrossProfit = completeGrossProfit
    ? rows.reduce((sum, row) => sum + (row.grossProfit ?? 0), 0)
    : null;

  return (
    <section className="panel overview-team-panel" aria-labelledby="overview-team-title">
      <div className="panel__heading overview-section-heading">
        <div>
          <p className="eyebrow">Команда</p>
          <h2 id="overview-team-title">Показатели по продавцам</h2>
          <p>Выручка, валовая прибыль и структура допродаж за выбранный период.</p>
        </div>
        <Link to={{ pathname: "/employees", search: location.search }}>Открыть рейтинг <ArrowRight size={15} /></Link>
      </div>

      {rows.length === 0 ? (
        <div className="panel-empty"><Users size={24} /><strong>Нет продавцов для отображения</strong><p>На главной показываются активные сотрудники, включённые в рейтинг.</p></div>
      ) : (
        <>
          <div className="overview-team-summary">
            <TeamSummaryCard label="Выручка продавцов" value={formatMoney(totalRevenue)} note={`${rows.length} сотрудников`} featured />
            <TeamSummaryCard
              label="Валовая прибыль"
              value={formatMoney(totalGrossProfit)}
              note={completeGrossProfit ? "По отображаемым продавцам" : "Нужна проверка себестоимости"}
            />
            <TeamSummaryCard
              label="Лидер по допам"
              value={leader?.employee.displayName ?? "—"}
              note={leader ? `${formatPercent(leader.employee.additionalSharePercent)}, ${formatCompactMoney(leader.employee.additionalRevenue)}` : "Недостаточно данных"}
            />
            <TeamSummaryCard
              label="Зона внимания"
              value={focus?.employee.displayName ?? "—"}
              note={focus ? `${formatPercent(focus.employee.additionalSharePercent)} допов` : "Недостаточно данных"}
            />
          </div>

          <div className="table-scroll overview-team-table-wrap">
            <table className="overview-team-table">
              <thead>
                <tr>
                  <th>Сотрудник</th>
                  <th>Выручка</th>
                  <th>Валовая прибыль</th>
                  <th>Аксессуары</th>
                  <th>% аксессуаров</th>
                  <th>Услуги</th>
                  <th>% услуг</th>
                  <th>Допы</th>
                  <th>% допов</th>
                </tr>
              </thead>
              <tbody>
                {rows.map(({ employee, grossProfit, completeCostData }) => (
                  <tr key={employee.employeeId}>
                    <td>
                      <Link to={{ pathname: `/employees/${employee.employeeId}`, search: location.search }}>{employee.displayName}</Link>
                      <small>{employee.shiftCount} смен, {formatNumber(employee.workedHours)} ч</small>
                    </td>
                    <td>{formatMoney(employee.netRevenue)}</td>
                    <td className={!completeCostData ? "overview-team-table__warning" : ""}>{formatMoney(grossProfit)}</td>
                    <td>{formatMoney(employee.accessoryRevenue)}</td>
                    <td>{formatPercent(employee.accessorySharePercent)}</td>
                    <td>{formatMoney(employee.serviceRevenue)}</td>
                    <td>{formatPercent(employee.serviceSharePercent)}</td>
                    <td>{formatMoney(employee.additionalRevenue)}</td>
                    <td><strong>{formatPercent(employee.additionalSharePercent)}</strong></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="overview-team-compact" aria-label="Краткие показатели по продавцам">
            {rows.map(({ employee, grossProfit, completeCostData }) => (
              <Link
                key={employee.employeeId}
                className="overview-team-compact__row"
                to={{ pathname: `/employees/${employee.employeeId}`, search: location.search }}
              >
                <span className="overview-team-compact__identity">
                  <strong>{employee.displayName}</strong>
                  <small>{employee.shiftCount} смен, {formatNumber(employee.workedHours)} ч</small>
                </span>
                <span className="overview-team-compact__metric">
                  <small>Выручка</small>
                  <strong>{formatCompactMoney(employee.netRevenue)}</strong>
                </span>
                <span className={`overview-team-compact__metric ${!completeCostData ? "overview-team-table__warning" : ""}`}>
                  <small>Валовая прибыль</small>
                  <strong>{formatCompactMoney(grossProfit)}</strong>
                </span>
                <span className="overview-team-compact__metric overview-team-compact__metric--accent">
                  <small>Допы</small>
                  <strong>{formatPercent(employee.additionalSharePercent)}</strong>
                </span>
                <ArrowRight size={16} aria-hidden="true" />
              </Link>
            ))}
          </div>
          <p className="overview-team-footnote">Показаны активные сотрудники, включённые в рейтинг. Проценты рассчитаны от полной чистой выручки каждого продавца.</p>
        </>
      )}
    </section>
  );
}

interface AttachCellValue {
  numerator: number;
  denominator: number;
  rate: number | null;
  includedInScore?: boolean;
}

type AttachCellKind = "benchmark" | "employee" | "context";
type AttachCellTone = "benchmark" | "context" | "empty" | "insufficient" | "below" | "at-level" | "above";

function storeAttachCell(attach: AttachRate, metricCode: string): AttachCellValue | null {
  const rate = attach.rates.find((entry) => entry.metricCode === metricCode);
  if (!rate) return null;
  return {
    numerator: rate.numeratorQuantity ?? rate.numeratorReceiptCount,
    denominator: rate.denominatorQuantity ?? rate.denominatorReceiptCount,
    rate: rate.ratePerHundred
  };
}

function employeeAttachCell(employee: EmployeeRatingEntry, metricCode: string): AttachCellValue | null {
  const rate: EmployeeAttachRatingEntry | undefined = employee.attachRates.find((entry) => entry.metricCode === metricCode);
  if (!rate) return null;
  return {
    numerator: rate.numeratorQuantity ?? rate.numeratorReceiptCount,
    denominator: rate.denominatorQuantity ?? rate.denominatorReceiptCount,
    rate: rate.ratePercent,
    includedInScore: rate.includedInScore
  };
}

function outsideRatingAttachCell(
  store: AttachCellValue | null,
  employees: EmployeeRatingEntry[],
  metricCode: string
): AttachCellValue | null {
  if (!store) return null;
  const visible = employees
    .map((employee) => employeeAttachCell(employee, metricCode))
    .filter((value): value is AttachCellValue => value != null);
  const numerator = store.numerator - visible.reduce((sum, value) => sum + value.numerator, 0);
  const denominator = store.denominator - visible.reduce((sum, value) => sum + value.denominator, 0);
  const normalizedNumerator = Math.abs(numerator) < 0.000001 ? 0 : numerator;
  const normalizedDenominator = Math.abs(denominator) < 0.000001 ? 0 : denominator;
  return {
    numerator: normalizedNumerator,
    denominator: normalizedDenominator,
    rate: normalizedDenominator <= 0
      ? null
      : Math.max(normalizedNumerator, 0) * 100 / normalizedDenominator
  };
}

function comparisonTone(
  value: AttachCellValue | null,
  benchmarkRate: number | null,
  kind: AttachCellKind
): AttachCellTone {
  if (value == null || value.denominator <= 0 || value.rate == null) return "empty";
  if (kind === "benchmark") return "benchmark";
  if (kind === "context") return "context";
  if (benchmarkRate == null || benchmarkRate <= 0 || value.includedInScore === false) {
    return "insufficient";
  }
  const ratio = value.rate / benchmarkRate;
  if (ratio < 0.9) return "below";
  if (ratio <= 1.1) return "at-level";
  return "above";
}

function AttachCell({
  value,
  owner,
  metric,
  benchmarkRate,
  kind = "employee"
}: {
  value: AttachCellValue | null;
  owner: string;
  metric: string;
  benchmarkRate: number | null;
  kind?: AttachCellKind;
}) {
  const noBase = value == null || value.denominator <= 0 || value.rate == null;
  const tone = comparisonTone(value, benchmarkRate, kind);
  const comparison = !noBase && kind === "employee" && benchmarkRate != null && benchmarkRate > 0
    ? formatPercent(value!.rate! * 100 / benchmarkRate) + " от среднего по магазину"
    : null;
  const suffix = kind === "benchmark"
    ? "; средний показатель по всем документам магазина"
    : kind === "context"
      ? "; остаток между магазином и участниками рейтинга"
      : tone === "insufficient"
        ? benchmarkRate == null || benchmarkRate <= 0
          ? "; средний показатель по магазину недоступен"
          : "; недостаточно продаж для рейтинга"
        : comparison == null ? "" : "; " + comparison;
  const title = noBase
    ? owner + ": нет релевантных продаж техники"
    : owner + ": " + formatNumber(value!.numerator) + " / " + formatNumber(value!.denominator)
      + " = " + formatPercent(value!.rate) + suffix;
  const detail = noBase
    ? "нет продаж для расчёта"
    : tone === "insufficient"
      ? benchmarkRate == null || benchmarkRate <= 0 ? "нет среднего по магазину" : "недостаточно продаж"
      : formatNumber(value!.numerator) + " / " + formatNumber(value!.denominator);
  return (
    <td className="attach-map__cell" data-tone={tone} title={title}>
      <strong>{noBase ? "—" : formatPercent(value!.rate)}</strong>
      <small>{detail}</small>
      <span className="sr-only">{metric}, {title}</span>
    </td>
  );
}

export function AttachRateMatrix({
  attach,
  rating,
  storeName
}: {
  attach: AttachRate;
  rating: EmployeeRatingResult;
  storeName: string;
}) {
  const location = useLocation();
  const employees = visibleEmployees(rating);
  const showOutsideRating = attachMetricOrder.some((metricCode) => {
    const outside = outsideRatingAttachCell(
      storeAttachCell(attach, metricCode),
      employees,
      metricCode
    );
    return outside != null
      && (Math.abs(outside.numerator) > 0.000001 || Math.abs(outside.denominator) > 0.000001);
  });
  const qualityIssueCount = attach.dataQuality.unmatchedNumeratorItemCount
    + attach.dataQuality.ambiguousWarrantyItemCount
    + attach.dataQuality.unknownDeviceConditionItemCount;

  return (
    <details className="panel attach-map-panel" aria-labelledby="attach-map-title">
      <summary className="panel__heading overview-section-heading attach-map__summary">
        <div>
          <p className="eyebrow">Допродажи</p>
          <h2 id="attach-map-title">Карта допродаж</h2>
          <p>Сравнение attach-rate магазина и продавцов по каждому показателю.</p>
        </div>
        <span className="attach-map__summary-meta"><span>{employees.length} продавцов, {attachMetricOrder.length} показателей</span><ChevronDown size={18} /></span>
      </summary>
      <div className="attach-map__content">
        {qualityIssueCount > 0 && (
          <div className="attach-map__quality" role="status"><AlertCircle size={16} />В расчёте есть {qualityIssueCount} позиций, требующих проверки классификации.</div>
        )}
        <p className="attach-map__scroll-hint">Прокрутите таблицу по горизонтали, чтобы увидеть всех продавцов.</p>
        <div className="table-scroll attach-map-wrap">
          <table className="attach-map">
            <thead>
              <tr>
                <th>Показатель</th>
                <th
                  className="attach-map__store-heading"
                  title={"Средний показатель " + storeName + " включает все документы магазина, в том числе продажи вне рейтинга и без сотрудника"}
                >
                  <TrendingUp size={14} /><span>{storeName}</span><small>Все продажи</small>
                </th>
                {showOutsideRating && (
                  <th
                    className="attach-map__outside-heading"
                    title="Разница между итогом магазина и показанными участниками рейтинга"
                  >
                    <span>Вне рейтинга</span><small>и без сотрудника</small>
                  </th>
                )}
                {employees.map((employee) => (
                  <th key={employee.employeeId}>
                    <Link to={{ pathname: "/employees/" + employee.employeeId, search: location.search }}>{employee.displayName}</Link>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {attachMetricOrder.map((metricCode) => {
                const metricLabel = attachRateLabels[metricCode] ?? metricCode;
                const storeValue = storeAttachCell(attach, metricCode);
                const outsideValue = outsideRatingAttachCell(storeValue, employees, metricCode);
                const benchmarkRate = storeValue?.rate ?? null;
                return (
                  <tr key={metricCode}>
                    <th scope="row">{metricLabel}</th>
                    <AttachCell
                      value={storeValue}
                      owner={storeName}
                      metric={metricLabel}
                      benchmarkRate={benchmarkRate}
                      kind="benchmark"
                    />
                    {showOutsideRating && (
                      <AttachCell
                        value={outsideValue}
                        owner="Вне рейтинга / без сотрудника"
                        metric={metricLabel}
                        benchmarkRate={benchmarkRate}
                        kind="context"
                      />
                    )}
                    {employees.map((employee) => (
                      <AttachCell
                        key={employee.employeeId}
                        value={employeeAttachCell(employee, metricCode)}
                        owner={employee.displayName}
                        metric={metricLabel}
                        benchmarkRate={benchmarkRate}
                      />
                    ))}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <footer className="attach-map__legend">
          <span><i data-tone="empty" />Нет или недостаточно продаж</span>
          <span><i data-tone="below" />Ниже магазина</span>
          <span><i data-tone="at-level" />На уровне магазина</span>
          <span><i data-tone="above" />Выше магазина</span>
          <small>Средний показатель магазина рассчитан по всем документам. Отклонение до 10% считается уровнем магазина.</small>
        </footer>
      </div>
    </details>
  );
}
