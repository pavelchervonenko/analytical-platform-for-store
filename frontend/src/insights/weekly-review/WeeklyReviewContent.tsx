import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type CSSProperties
} from "react";
import { Link, useLocation } from "react-router";
import {
  AlertTriangle,
  ArrowRight,
  ChevronDown,
  Info,
  RefreshCw,
  Sparkles
} from "lucide-react";
import type {
  WeeklyReview,
  WeeklyReviewAction,
  WeeklyReviewEmployee,
  WeeklyReviewMetric,
  WeeklyReviewStructureNode
} from "../../api/weeklyReviewContract";
import {
  actionTargetText,
  formatCalculatedAt,
  formatValue,
  initials,
  metricComparisonText,
  metricStateText,
  metricTone,
  nextWeekLabel,
  reviewStateLabel
} from "../weekly-review-presentation";
import {
  createWeeklyReviewViewModel,
  employeeWorkloadNotice,
  limitationsForBlock,
  limitationsForMetric
} from "./weeklyReviewViewModel";
import {
  ReviewDetailPanel,
  type ReviewDetailContext
} from "./ReviewDetailPanel";

type OpenDetail = (context: ReviewDetailContext, trigger: HTMLElement) => void;

const ActiveDetailKeyContext = createContext<string | null>(null);

function detailContextKey(context: ReviewDetailContext): string {
  switch (context.kind) {
    case "evidence":
      return `evidence:${context.title}:${context.evidenceRefs.join("|")}`;
    case "revenue":
      return "revenue";
    case "limitations":
      return `limitations:${context.title}:${[
        ...context.limitations.map((item) => item.limitationId),
        ...(context.messages ?? [])
      ].join("|")}`;
    case "employee":
      return `employee:${context.employee.employeePublicId}`;
  }
}

export function WeeklyReviewContent({
  review,
  qualityHref
}: {
  review: WeeklyReview;
  qualityHref: string | null;
}) {
  const model = useMemo(() => createWeeklyReviewViewModel(review), [review]);
  const [detail, setDetail] = useState<ReviewDetailContext | null>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);

  const openDetail = useCallback<OpenDetail>((context, trigger) => {
    returnFocusRef.current = trigger;
    setDetail(context);
  }, []);
  const closeDetail = useCallback(() => {
    setDetail(null);
    window.requestAnimationFrame(() => returnFocusRef.current?.focus());
  }, []);

  return (
    <>
      <ActiveDetailKeyContext.Provider value={detail === null ? null : detailContextKey(detail)}>
        <div className="weekly-review-screen" aria-hidden={detail !== null ? true : undefined}>
          <ReviewHeader review={review} />
          {review.reportState === "BLOCKED" && (
            <BlockedReview
              review={review}
              limitations={model.limitations}
              openDetail={openDetail}
            />
          )}
          {review.reportState === "PREPARING" && <PreparingReview review={review} />}
          {(review.reportState === "READY" || review.reportState === "PARTIAL") && (
            <div className="weekly-review-content">
              {review.reportState === "PARTIAL" && (
                <QualitySummary
                  review={review}
                  limitations={model.limitations}
                  localMessages={[
                    ...review.salesStructure.limitations,
                    ...review.team.limitations
                  ]}
                  openDetail={openDetail}
                />
              )}
              <DecisionSection
                review={review}
                outcome={model.outcome}
                primaryAction={model.primaryAction}
                secondaryActions={model.secondaryActions}
                limitations={limitationsForBlock(model.limitations, "summary")}
                openDetail={openDetail}
              />
              <ResultsSection
                review={review}
                limitations={model.limitations}
                openDetail={openDetail}
              />
              <ChangesSection
                factors={model.secondaryFactors}
                hasMaterialFactors={model.hasMaterialFactors}
                openDetail={openDetail}
              />
              <SalesStructureSection
                review={review}
                limitations={model.limitations}
                openDetail={openDetail}
              />
              <TeamExceptionsSection
                review={review}
                employees={model.attentionEmployees}
                openDetail={openDetail}
              />
            </div>
          )}
        </div>
      </ActiveDetailKeyContext.Provider>
      <ReviewDetailPanel
        context={detail}
        review={review}
        evidenceByRef={model.evidenceByRef}
        qualityHref={qualityHref}
        onClose={closeDetail}
      />
    </>
  );
}

function ReviewHeader({ review }: { review: WeeklyReview }) {
  const aiEnhanced = review.summary.generatedBy === "AI_ENHANCED"
    && review.aiEnhancement.state === "READY";
  return (
    <header className="weekly-review-header">
      <div className="weekly-review-header__period">
        <span>Последняя завершённая неделя</span>
        <strong>{review.period.currentLabel}</strong>
        <small>Сравнение: {review.period.previousLabel}</small>
      </div>
      <div className="weekly-review-header__meta">
        {aiEnhanced && (
          <span className="weekly-review-ai-label">
            <Sparkles aria-hidden="true" /> Дополнено ИИ
          </span>
        )}
        {review.reportState !== "READY" && (
          <span className={"weekly-review-state weekly-review-state--" + review.reportState.toLowerCase()}>
            {reviewStateLabel(review.reportState)}
          </span>
        )}
        <small>Обновлено {formatCalculatedAt(review.provenance.calculatedAt)}</small>
      </div>
    </header>
  );
}

function QualitySummary({
  review,
  limitations,
  localMessages,
  openDetail
}: {
  review: WeeklyReview;
  limitations: WeeklyReview["limitations"];
  localMessages: readonly string[];
  openDetail: OpenDetail;
}) {
  return (
    <aside className="weekly-review-quality-summary" aria-label="Ограничения разбора">
      <AlertTriangle aria-hidden="true" />
      <div>
        <strong>Часть выводов ограничена</strong>
        <p>{review.qualitySummary.message}</p>
      </div>
      {(limitations.length > 0 || localMessages.length > 0) && (
        <DetailButton
          label="Подробнее об ограничениях"
          context={{
            kind: "limitations",
            title: "Ограничения данных",
            limitations,
            messages: localMessages
          }}
          openDetail={openDetail}
        />
      )}
    </aside>
  );
}

function DecisionSection({
  review,
  outcome,
  primaryAction,
  secondaryActions,
  limitations,
  openDetail
}: {
  review: WeeklyReview;
  outcome: WeeklyReview["summary"]["outcome"];
  primaryAction: WeeklyReviewAction | null;
  secondaryActions: WeeklyReviewAction[];
  limitations: WeeklyReview["limitations"];
  openDetail: OpenDetail;
}) {
  return (
    <section className="weekly-review-decision" aria-labelledby="weekly-review-summary-title">
      <article className="weekly-review-summary">
        <span className="weekly-review-kicker">Итог недели</span>
        {outcome ? (
          <>
            <h2 id="weekly-review-summary-title">{outcome.text}</h2>
            <div className="weekly-review-decision-footer weekly-review-decision-footer--summary">
              {limitations.length > 0 && (
                <DetailButton
                  label="Вывод ограничен"
                  context={{
                    kind: "limitations",
                    title: "Ограничение главного вывода",
                    limitations
                  }}
                  openDetail={openDetail}
                  compact
                  tone="warning"
                />
              )}
              <DetailButton
                label="Почему такой вывод"
                context={{
                  kind: "evidence",
                  title: "Основание главного вывода",
                  evidenceRefs: outcome.evidenceRefs
                }}
                openDetail={openDetail}
              />
            </div>
          </>
        ) : (
          <h2 id="weekly-review-summary-title">Главный вывод недоступен</h2>
        )}
      </article>
      <aside
        className={primaryAction || secondaryActions.length > 0
          ? "weekly-review-primary-action"
          : "weekly-review-primary-action weekly-review-primary-action--calm"}
        aria-labelledby="weekly-review-action-title"
      >
        <span className="weekly-review-kicker">Что проверить на этой неделе</span>
        {primaryAction ? (
          <>
            <h2 id="weekly-review-action-title">{primaryAction.title}</h2>
            <dl>
              <div><dt>Ориентир</dt><dd>{actionTargetText(primaryAction)}</dd></div>
              <div><dt>Проверка</dt><dd>{primaryAction.check}</dd></div>
            </dl>
            <small>{nextWeekLabel(review.period.current.end)}</small>
          </>
        ) : (
          <p className="weekly-review-calm-copy">
            {review.reportState === "PARTIAL"
              ? "Приоритетная проверка не сформирована по доступной части данных. Сначала уточните ограничения."
              : "Дополнительная проверка не требуется — сохраните текущую практику."}
          </p>
        )}
        {(primaryAction || secondaryActions.length > 0) && (
          <div className="weekly-review-decision-footer">
            {primaryAction && (
              <DetailButton
                label="Показать основание действия"
                context={{
                  kind: "evidence",
                  title: primaryAction.title,
                  evidenceRefs: primaryAction.evidenceRefs
                }}
                openDetail={openDetail}
              />
            )}
            {secondaryActions.length > 0 && (
              <details className="weekly-review-secondary-actions">
                <summary>
                  Ещё проверки <span>{secondaryActions.length}</span>
                  <ChevronDown aria-hidden="true" />
                </summary>
                <div>
                  {secondaryActions.map((action) => (
                    <ActionSummary action={action} key={action.actionId} />
                  ))}
                </div>
              </details>
            )}
          </div>
        )}
      </aside>
    </section>
  );
}

function ActionSummary({ action }: { action: WeeklyReviewAction }) {
  return (
    <article>
      <strong>{action.title}</strong>
      <span>Ориентир: {actionTargetText(action)}</span>
      <small>{action.check}</small>
    </article>
  );
}

function ResultsSection({
  review,
  limitations,
  openDetail
}: {
  review: WeeklyReview;
  limitations: WeeklyReview["limitations"];
  openDetail: OpenDetail;
}) {
  return (
    <section className="weekly-review-section" aria-labelledby="weekly-review-results-title">
      <SectionHeading
        id="weekly-review-results-title"
        title="Результаты недели"
        meta={review.period.currentLabel}
      />
      <div className="weekly-review-results-grid">
        {review.results.map((metric) => (
          <MetricCard
            metric={metric}
            limitations={limitationsForMetric(limitations, metric)}
            openDetail={openDetail}
            key={metric.metricId}
          />
        ))}
      </div>
      <DetailButton
        label="Как рассчитана чистая выручка"
        context={{ kind: "revenue", title: "Расчёт чистой выручки" }}
        openDetail={openDetail}
      />
    </section>
  );
}

function MetricCard({
  metric,
  limitations,
  openDetail
}: {
  metric: WeeklyReviewMetric;
  limitations: WeeklyReview["limitations"];
  openDetail: OpenDetail;
}) {
  const stateText = metricStateText(metric);
  return (
    <article className={"weekly-review-metric weekly-review-metric--" + metricTone(metric)}>
      <span>{metric.label}</span>
      <strong>{formatValue(metric.current, metric.unit)}</strong>
      <small>{metricComparisonText(metric)}</small>
      {stateText && <em>{stateText}</em>}
      {limitations.length > 0 && (
        <DetailButton
          label="Почему ограничен"
          ariaLabel={"Почему ограничен показатель «" + metric.label + "»"}
          context={{ kind: "limitations", title: metric.label, limitations }}
          openDetail={openDetail}
          compact
        />
      )}
    </article>
  );
}

function ChangesSection({
  factors,
  hasMaterialFactors,
  openDetail
}: {
  factors: WeeklyReview["factors"];
  hasMaterialFactors: boolean;
  openDetail: OpenDetail;
}) {
  const [showAllFactors, setShowAllFactors] = useState(false);
  if (hasMaterialFactors && factors.length === 0) return null;

  return (
    <section className="weekly-review-section" aria-labelledby="weekly-review-changes-title">
      <SectionHeading id="weekly-review-changes-title" title="Что изменилось" />
      {factors.length === 0 ? (
        <p className="weekly-review-calm-copy">Существенных изменений по доступным данным нет.</p>
      ) : (
        <>
          <div
            className={`weekly-review-factor-list weekly-review-progressive-list${
              showAllFactors ? " weekly-review-progressive-list--expanded" : ""
            }`}
            id="weekly-review-changes-list"
          >
            {factors.map((factor, index) => (
              <article
                className={`weekly-review-factor weekly-review-factor--${
                  factor.effect.toLowerCase()
                }${index > 0 ? " weekly-review-progressive-item--additional" : ""}`}
                key={factor.factorId}
              >
                <span>
                  {factor.effect === "NEGATIVE" ? "Зона внимания" : "Положительная динамика"}
                </span>
                <h3>{factor.title}</h3>
                <p>{factor.detail}</p>
                {factor.contributionAmount !== null && (
                  <small>Влияние: {formatValue(factor.contributionAmount, "RUB")}</small>
                )}
                <DetailButton
                  label="Показать расчёт"
                  ariaLabel={"Показать расчёт: " + factor.title}
                  context={{
                    kind: "evidence",
                    title: factor.title,
                    evidenceRefs: factor.evidenceRefs
                  }}
                  openDetail={openDetail}
                  compact
                />
              </article>
            ))}
          </div>
          {factors.length > 1 && (
            <ProgressiveListToggle
              controls="weekly-review-changes-list"
              expanded={showAllFactors}
              collapsedLabel={`Ещё ${russianCount(factors.length - 1, [
                "изменение",
                "изменения",
                "изменений"
              ])}`}
              expandedLabel="Скрыть дополнительные изменения"
              onToggle={() => setShowAllFactors((current) => !current)}
            />
          )}
        </>
      )}
    </section>
  );
}

function SalesStructureSection({
  review,
  limitations,
  openDetail
}: {
  review: WeeklyReview;
  limitations: WeeklyReview["limitations"];
  openDetail: OpenDetail;
}) {
  const block = review.salesStructure;
  const blockLimitations = limitationsForBlock(limitations, block.blockId);
  const unavailable = block.state === "INSUFFICIENT" || block.state === "NOT_APPLICABLE";
  return (
    <details className="weekly-review-structure-section">
      <summary>
        <div>
          <span>Структура продаж</span>
          <small>Категории и допродажи</small>
        </div>
        {block.state === "LIMITED" && <em>Ограничено</em>}
        <ChevronDown aria-hidden="true" />
      </summary>
      <div className="weekly-review-structure-section__body">
        {unavailable ? (
          <p className="weekly-review-calm-copy">Для структуры продаж недостаточно данных.</p>
        ) : (
          <div className="weekly-review-structure-table" role="table" aria-label="Структура продаж">
            <div className="weekly-review-structure-table__head" role="row">
              <span role="columnheader">Направление</span>
              <span role="columnheader">Текущая неделя</span>
              <span role="columnheader">Прошлая неделя</span>
              <span role="columnheader">Доля</span>
              <span role="columnheader">Динамика</span>
            </div>
            {block.root.children.map((node) => <StructureRow node={node} key={node.nodeId} />)}
          </div>
        )}
        {!unavailable && block.attachMetrics.length > 0 && (
          <div className="weekly-review-attach-grid">
            {block.attachMetrics.map((item) => (
              <article key={item.metricId}>
                <span>{item.label}</span>
                <strong>{formatValue(item.comparison.current, item.comparison.unit)}</strong>
                <small>{metricComparisonText(item.comparison)}</small>
              </article>
            ))}
          </div>
        )}
        {(blockLimitations.length > 0 || block.limitations.length > 0) && (
          <DetailButton
            label="Подробнее об ограничениях структуры"
            context={{
              kind: "limitations",
              title: "Структура продаж",
              limitations: blockLimitations,
              messages: block.limitations
            }}
            openDetail={openDetail}
          />
        )}
      </div>
    </details>
  );
}

function StructureRow({ node, depth = 0 }: { node: WeeklyReviewStructureNode; depth?: number }) {
  return (
    <>
      <div
        className="weekly-review-structure-row"
        role="row"
        style={{ "--review-depth": depth } as CSSProperties}
      >
        <span role="cell">{node.label}</span>
        <strong role="cell">{formatValue(node.comparison.current, node.comparison.unit)}</strong>
        <small role="cell">
          <span className="weekly-review-structure-row__mobile-label">Прошлая неделя: </span>
          {formatValue(node.comparison.previous, node.comparison.unit)}
        </small>
        <small role="cell">
          <span className="weekly-review-structure-row__mobile-label">Доля: </span>
          {formatValue(node.shareComparison.current, "PERCENT")}
        </small>
        <em role="cell">
          <span className="weekly-review-structure-row__mobile-label">Динамика: </span>
          {metricComparisonText(node.comparison)}
        </em>
      </div>
      {node.children.map((child) => (
        <StructureRow node={child} depth={depth + 1} key={child.nodeId} />
      ))}
    </>
  );
}

function TeamExceptionsSection({
  review,
  employees,
  openDetail
}: {
  review: WeeklyReview;
  employees: WeeklyReviewEmployee[];
  openDetail: OpenDetail;
}) {
  const location = useLocation();
  const [showAllEmployees, setShowAllEmployees] = useState(false);
  const attentionCount = review.team.attentionEmployeeCount;
  const employeeCount = review.team.roster.activeAssignedWithActivity;
  const attentionMeta = `${attentionCount} из ${employeeCount} ${
    attentionCount === 1 ? "требует" : "требуют"
  } проверки`;
  return (
    <section className="weekly-review-section weekly-review-team" aria-labelledby="weekly-review-team-title">
      <SectionHeading
        id="weekly-review-team-title"
        title="Команда"
        meta={attentionMeta}
      />
      {attentionCount === 0 && (
        <p className="weekly-review-calm-copy">
          Значимых отрицательных изменений на достаточной базе не обнаружено.
        </p>
      )}
      {employees.length > 0 && (
        <div
          className={`weekly-review-exception-list weekly-review-progressive-list${
            showAllEmployees ? " weekly-review-progressive-list--expanded" : ""
          }`}
          id="weekly-review-employees-list"
        >
          {employees.map((employee, index) => (
            <EmployeeException
              employee={employee}
              search={location.search}
              openDetail={openDetail}
              key={employee.employeePublicId}
              additional={index > 0}
            />
          ))}
        </div>
      )}
      {employees.length > 1 && (
        <ProgressiveListToggle
          controls="weekly-review-employees-list"
          expanded={showAllEmployees}
          collapsedLabel={`Ещё ${russianCount(employees.length - 1, [
            "сотрудник",
            "сотрудника",
            "сотрудников"
          ])}`}
          expandedLabel="Скрыть дополнительных сотрудников"
          onToggle={() => setShowAllEmployees((current) => !current)}
        />
      )}
      <div className="weekly-review-section-actions">
        {review.team.limitations.length > 0 && (
          <DetailButton
            label="Почему команда ограничена"
            context={{
              kind: "limitations",
              title: "Ограничения команды",
              limitations: [],
              messages: review.team.limitations
            }}
            openDetail={openDetail}
            compact
            tone="warning"
          />
        )}
        <Link className="weekly-review-section-link" to={{ pathname: "/employees", search: location.search }}>
          Все сотрудники <ArrowRight aria-hidden="true" />
        </Link>
      </div>
    </section>
  );
}

function EmployeeException({
  employee,
  search,
  openDetail,
  additional
}: {
  employee: WeeklyReviewEmployee;
  search: string;
  openDetail: OpenDetail;
  additional: boolean;
}) {
  const attention = employee.attention!;
  const workloadNotice = employeeWorkloadNotice(employee);
  return (
    <article className={`weekly-review-exception${
      additional ? " weekly-review-progressive-item--additional" : ""
    }`}>
      <header>
        <span>{initials(employee.displayName)}</span>
        <h3>{employee.displayName}</h3>
      </header>
      <strong>{attention.title}</strong>
      {employee.action && <small>Ориентир: {actionTargetText(employee.action)}</small>}
      {workloadNotice && <p className="weekly-review-exception__workload">{workloadNotice}</p>}
      <div>
        <DetailButton
          label="Почему сотрудник в списке"
          ariaLabel={"Почему сотрудник в списке: " + employee.displayName}
          context={{ kind: "employee", title: employee.displayName, employee }}
          openDetail={openDetail}
          compact
        />
        <Link
          aria-label={"Открыть сотрудника: " + employee.displayName}
          to={{ pathname: "/employees/" + employee.employeePublicId, search }}
        >
          Открыть сотрудника <ArrowRight aria-hidden="true" />
        </Link>
      </div>
    </article>
  );
}

function ProgressiveListToggle({
  controls,
  expanded,
  collapsedLabel,
  expandedLabel,
  onToggle
}: {
  controls: string;
  expanded: boolean;
  collapsedLabel: string;
  expandedLabel: string;
  onToggle: () => void;
}) {
  return (
    <button
      className="weekly-review-progressive-toggle"
      type="button"
      aria-controls={controls}
      aria-expanded={expanded}
      onClick={onToggle}
    >
      {expanded ? expandedLabel : collapsedLabel}
      <ChevronDown aria-hidden="true" />
    </button>
  );
}

function russianCount(count: number, forms: readonly [string, string, string]): string {
  const modulo100 = count % 100;
  const modulo10 = count % 10;
  const form = modulo100 >= 11 && modulo100 <= 14
    ? forms[2]
    : modulo10 === 1
      ? forms[0]
      : modulo10 >= 2 && modulo10 <= 4
        ? forms[1]
        : forms[2];
  return `${count} ${form}`;
}

function DetailButton({
  label,
  ariaLabel,
  context,
  openDetail,
  compact = false,
  tone = "default"
}: {
  label: string;
  ariaLabel?: string;
  context: ReviewDetailContext;
  openDetail: OpenDetail;
  compact?: boolean;
  tone?: "default" | "warning";
}) {
  const activeDetailKey = useContext(ActiveDetailKeyContext);
  const classNames = [
    "weekly-review-detail-trigger",
    compact ? "weekly-review-detail-trigger--compact" : "",
    tone === "warning" ? "weekly-review-detail-trigger--warning" : ""
  ].filter(Boolean).join(" ");
  return (
    <button
      className={classNames}
      type="button"
      aria-label={ariaLabel}
      aria-controls="weekly-review-detail-panel"
      aria-expanded={activeDetailKey === detailContextKey(context)}
      aria-haspopup="dialog"
      onClick={(event) => openDetail(context, event.currentTarget)}
    >
      <Info aria-hidden="true" /> {label}
    </button>
  );
}

function SectionHeading({ id, title, meta }: { id: string; title: string; meta?: string }) {
  return (
    <div className="weekly-review-section-heading">
      <h2 id={id}>{title}</h2>
      {meta && <small>{meta}</small>}
    </div>
  );
}

function BlockedReview({
  review,
  limitations,
  openDetail
}: {
  review: WeeklyReview;
  limitations: WeeklyReview["limitations"];
  openDetail: OpenDetail;
}) {
  return (
    <section className="weekly-review-blocked" role="alert">
      <AlertTriangle aria-hidden="true" />
      <h2>Для разбора не хватает данных</h2>
      <p>{review.qualitySummary.message}</p>
      {limitations.length > 0 && (
        <DetailButton
          label="Что нужно исправить"
          context={{ kind: "limitations", title: "Что нужно исправить", limitations }}
          openDetail={openDetail}
        />
      )}
    </section>
  );
}

function PreparingReview({ review }: { review: WeeklyReview }) {
  return (
    <section className="weekly-review-blocked weekly-review-blocked--preparing" aria-live="polite">
      <RefreshCw aria-hidden="true" />
      <h2>Разбор формируется</h2>
      <p>{review.qualitySummary.message}</p>
    </section>
  );
}
