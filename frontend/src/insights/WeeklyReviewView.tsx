import { useQuery } from "@tanstack/react-query";
import { useMemo, useState, type ReactNode } from "react";
import {
  AlertTriangle,
  BarChart3,
  CheckCircle2,
  ChevronDown,
  RefreshCw,
  Target,
  Users
} from "lucide-react";
import { getWeeklyReview, queryKeys } from "../api/queries";
import type {
  WeeklyReview,
  WeeklyReviewAction,
  WeeklyReviewEmployee,
  WeeklyReviewEvidence,
  WeeklyReviewMetric,
  WeeklyReviewObservation,
  WeeklyReviewStructureNode
} from "../api/weeklyReviewContract";
import { PanelSkeleton, QueryError } from "../shared/QueryState";
import {
  actionTargetText,
  formatCalculatedAt,
  formatEvidenceValue,
  formatValue,
  initials,
  metricComparisonText,
  metricStateText,
  metricTone,
  reviewStateLabel,
  sourceLabel,
  type ReviewTone
} from "./weekly-review-presentation";
import "./weekly-review.css";
import "./weekly-review-polish.css";

type EvidenceIndex = ReadonlyMap<string, WeeklyReviewEvidence>;
type BlockState = WeeklyReview["summary"]["state"];

const EMPLOYEE_PREVIEW_LIMIT = 8;

function documentCountText(value: number | null): string {
  if (value == null) return "—";
  const absolute = Math.abs(value);
  const mod10 = absolute % 10;
  const mod100 = absolute % 100;
  const noun = mod10 === 1 && mod100 !== 11
    ? "документ"
    : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)
      ? "документа"
      : "документов";
  return `${formatValue(value, "COUNT")} ${noun}`;
}

function sameReferences(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length
    && left.every((reference) => right.includes(reference));
}

function BlockStateBadge({ state }: { state: BlockState }) {
  if (state === "READY") return null;
  const label = state === "LIMITED"
    ? "Данные ограничены"
    : state === "INSUFFICIENT"
      ? "Недостаточно данных"
      : "Не применяется";
  return (
    <span className={`weekly-review-block-state weekly-review-block-state--${state.toLowerCase()}`}>
      {label}
    </span>
  );
}

function BlockEmpty({ state }: { state: BlockState }) {
  const text = state === "NOT_APPLICABLE"
    ? "Этот раздел не применяется к выбранной неделе."
    : "Для этого раздела недостаточно данных.";
  return <p className="weekly-review-block-empty">{text}</p>;
}

function refetchInterval(review: WeeklyReview | null | undefined): number | false {
  if (!review) return false;
  if (review.reportState === "PREPARING") return 15_000;
  if (review.aiEnhancement.state === "PREPARING"
      || review.aiEnhancement.state === "DELAYED") {
    return 15_000;
  }
  return false;
}

function uniqueEvidence(
  references: readonly string[],
  evidenceByRef: EvidenceIndex
): WeeklyReviewEvidence[] {
  return Array.from(new Set(references))
    .map((reference) => evidenceByRef.get(reference))
    .filter((item): item is WeeklyReviewEvidence => item !== undefined);
}

function EvidenceDisclosure({
  evidenceRefs,
  evidenceByRef
}: {
  evidenceRefs: readonly string[];
  evidenceByRef: EvidenceIndex;
}) {
  const evidence = uniqueEvidence(evidenceRefs, evidenceByRef);
  if (evidence.length === 0) return null;

  return (
    <details className="weekly-review-evidence">
      <summary>
        <span>Основание</span>
        <ChevronDown aria-hidden="true" />
      </summary>
      <div className="weekly-review-evidence__list">
        {evidence.map((item) => (
          <div className="weekly-review-evidence__row" key={item.evidenceRef}>
            <span>{item.label}</span>
            <strong>{formatEvidenceValue(item.currentValue, item.unit)}</strong>
            {item.previousPeriod && (
              <small>
                Было {formatEvidenceValue(item.previousValue, item.unit)}
              </small>
            )}
          </div>
        ))}
      </div>
    </details>
  );
}

function ReviewHeader({ review }: { review: WeeklyReview }) {
  const stateClass = review.reportState === "READY"
    ? "weekly-review-state--ready"
    : review.reportState === "PARTIAL"
      ? "weekly-review-state--limited"
      : review.reportState === "PREPARING"
        ? "weekly-review-state--preparing"
        : "weekly-review-state--blocked";

  return (
    <header className="weekly-review-header">
      <div className="weekly-review-header__period">
        <div>
          <span>Завершённая неделя:</span>
          <strong>{review.period.currentLabel}</strong>
        </div>
        <div>
          <span>Сравнение с:</span>
          <span>{review.period.previousLabel}</span>
        </div>
      </div>
      <div className="weekly-review-header__meta">
        <span className={`weekly-review-state ${stateClass}`} role="status">
          {review.reportState === "READY"
            ? <CheckCircle2 aria-hidden="true" />
            : review.reportState === "PREPARING"
              ? <RefreshCw aria-hidden="true" />
              : <AlertTriangle aria-hidden="true" />}
          {reviewStateLabel(review.reportState)}
        </span>
        <small>Обновлено {formatCalculatedAt(review.provenance.calculatedAt)}</small>
      </div>
    </header>
  );
}

function QualityNotice({ review }: { review: WeeklyReview }) {
  if (review.reportState === "READY") return null;
  const message = review.reportState === "PARTIAL"
    && review.qualitySummary.warningCount === 0
    ? "Часть разделов доступна с ограничениями."
    : review.qualitySummary.message;
  const hasDetails = review.limitations.length > 0 || review.sourceCoverage.some(
    (source) => source.state === "PARTIAL" || source.state === "MISSING"
  );
  return (
    <div
      className={`weekly-review-quality weekly-review-quality--${review.reportState.toLowerCase()}`}
      role={review.reportState === "BLOCKED" ? "alert" : "status"}
    >
      <AlertTriangle aria-hidden="true" />
      <div>
        <strong>{message}</strong>
        {review.qualitySummary.affectedBlockCount > 0 && (
          <span>
            Ограничено разделов: {review.qualitySummary.affectedBlockCount}
          </span>
        )}
      </div>
      {hasDetails && (
        <a href="#weekly-review-limitations">Подробнее</a>
      )}
    </div>
  );
}

function SummarySignal({
  label,
  text,
  detail,
  tone,
  evidenceRefs,
  evidenceByRef
}: {
  label: string;
  text: string;
  detail?: string | null;
  tone: ReviewTone;
  evidenceRefs: readonly string[];
  evidenceByRef: EvidenceIndex;
}) {
  return (
    <article className={`weekly-review-signal weekly-review-signal--${tone}`}>
      <span>{label}</span>
      <p>{text}</p>
      {detail && <small className="weekly-review-signal__detail">{detail}</small>}
      <EvidenceDisclosure evidenceRefs={evidenceRefs} evidenceByRef={evidenceByRef} />
    </article>
  );
}

function summaryText(text: string): string {
  return text.trim().replace(/\s+₽/gu, "\u00a0₽");
}

function resultClause(metric: WeeklyReviewMetric | undefined, label: string): string | null {
  if (!metric || metric.current == null || metric.metricState === "UNAVAILABLE") return null;
  if (metric.direction === "UP") return `${label} выросла`;
  if (metric.direction === "DOWN") return `${label} снизилась`;
  if (metric.direction === "FLAT") return `${label} осталась на прежнем уровне`;
  return `${label} рассчитана`;
}

function joinClauses(clauses: readonly string[]): string {
  if (clauses.length <= 1) return clauses[0] ?? "";
  return `${clauses.slice(0, -1).join(", ")}, а ${clauses.at(-1)}`;
}

function deterministicSummaryLead(review: WeeklyReview): string {
  const result = (code: string) => review.results.find((metric) => metric.code === code);
  const clauses = [
    resultClause(result("NET_REVENUE"), "чистая выручка"),
    resultClause(result("GROSS_PROFIT"), "валовая прибыль"),
    resultClause(result("MARGIN_PERCENT"), "маржа")
  ].filter((clause): clause is string => clause !== null);
  if (clauses.length === 0) return "Итог рассчитан по доступным данным завершённой недели.";
  const outcome = `По сравнению с предыдущей полной неделей ${joinClauses(clauses)}.`;
  const provenRisk = review.factors.find((factor) => (
    factor.effect === "NEGATIVE"
    && factor.contributionAmount != null
    && factor.contributionAmount !== 0
  ));
  if (!provenRisk) return outcome;
  if (provenRisk.kind === "RETURN_CHANGE") {
    const returnsIncreased = provenRisk.comparison.direction === "UP";
    const movement = returnsIncreased
      ? "Рост возвратов"
      : "Снижение возвратов";
    const impact = provenRisk.contributionAmount! < 0
      ? returnsIncreased ? "уменьшил" : "уменьшило"
      : returnsIncreased ? "увеличил" : "увеличило";
    return `${outcome} ${movement} ${impact} чистую выручку на ${formatValue(
      Math.abs(provenRisk.contributionAmount!),
      "RUB"
    )}.`;
  }
  return outcome;
}

function factorContext(factor: WeeklyReview["factors"][number] | null): string | null {
  if (!factor) return null;
  if (factor.contributionAmount != null && factor.contributionAmount !== 0) {
    const verb = factor.contributionAmount < 0 ? "уменьшило" : "увеличило";
    return `Это изменение напрямую ${verb} чистую выручку.`;
  }
  return factor.effect === "POSITIVE"
    ? "Связь с общим ростом пока не установлена."
    : "Связь с общим снижением пока не установлена.";
}

function SummarySection({
  review,
  evidenceByRef
}: {
  review: WeeklyReview;
  evidenceByRef: EvidenceIndex;
}) {
  const outcome = review.summary.outcome;
  const matchingFactor = (signal: typeof review.summary.positive) => (
    signal === null
      ? null
      : review.factors.find((factor) => (
      factor.effect === signal.effect
      && sameReferences(factor.evidenceRefs, signal.evidenceRefs)
      )) ?? null
  );
  const positive = review.summary.positive;
  const risk = review.summary.risk;
  const positiveFactor = matchingFactor(positive);
  const riskFactor = matchingFactor(risk);
  const primaryAction = review.actions[0] ?? null;
  const deterministicLead = outcome && review.summary.generatedBy === "DETERMINISTIC"
    ? deterministicSummaryLead(review)
    : null;
  return (
    <section className="weekly-review-summary" aria-labelledby="weekly-review-summary-title">
      <div className="weekly-review-summary__frame">
        <div className={`weekly-review-summary__main${
          deterministicLead ? " weekly-review-summary__main--deterministic" : ""
        }`}>
          <div className="weekly-review-summary__kicker">
            <span className="weekly-review-kicker">Главное</span>
            <BlockStateBadge state={review.summary.state} />
          </div>
          {outcome ? (
            <>
              <h2
                aria-label={deterministicLead ?? outcome.text}
                id="weekly-review-summary-title"
              >
                {deterministicLead ?? summaryText(outcome.text)}
              </h2>
              <EvidenceDisclosure
                evidenceRefs={outcome.evidenceRefs}
                evidenceByRef={evidenceByRef}
              />
            </>
          ) : (
            <>
              <h2 id="weekly-review-summary-title">Главный вывод недоступен</h2>
              <BlockEmpty state={review.summary.state} />
            </>
          )}
        </div>
        {(positive || risk || primaryAction) && (
          <div className="weekly-review-summary__signals">
            {positive && (
              <SummarySignal
                label="Что улучшилось"
                text={positiveFactor?.title ?? positive.text}
                detail={factorContext(positiveFactor)}
                tone="positive"
                evidenceRefs={positive.evidenceRefs}
                evidenceByRef={evidenceByRef}
              />
            )}
            {risk && (
              <SummarySignal
                label="Что требует внимания"
                text={riskFactor?.title ?? risk.text}
                detail={factorContext(riskFactor)}
                tone="negative"
                evidenceRefs={risk.evidenceRefs}
                evidenceByRef={evidenceByRef}
              />
            )}
            {primaryAction && (
              <SummarySignal
                label="Что сделать"
                text={primaryAction.title}
                detail="Приоритет на следующую полную неделю"
                tone="neutral"
                evidenceRefs={primaryAction.evidenceRefs}
                evidenceByRef={evidenceByRef}
              />
            )}
          </div>
        )}
      </div>
    </section>
  );
}

function MetricCard({ metric }: { metric: WeeklyReviewMetric }) {
  const tone = metricTone(metric);
  const stateText = metricStateText(metric);
  return (
    <article className={`weekly-review-metric weekly-review-metric--${tone}`}>
      <span>{metric.label}</span>
      <strong>{formatValue(metric.current, metric.unit)}</strong>
      <div>
        <span className="weekly-review-metric__delta">
          {metricComparisonText(metric)}
        </span>
        <small>Было {formatValue(metric.previous, metric.unit)}</small>
      </div>
      {stateText && <em>{stateText}</em>}
    </article>
  );
}

function RevenueFormula({ review }: { review: WeeklyReview }) {
  const decomposition = review.revenueDecomposition;
  return (
    <details className="weekly-review-formula">
      <summary>
        <span>Как рассчитана чистая выручка</span>
        <ChevronDown aria-hidden="true" />
      </summary>
      <div className="weekly-review-formula__content">
        <div>
          <span>Продажи</span>
          <strong>{formatValue(decomposition.salesRevenue.current, "RUB")}</strong>
          <small>{documentCountText(decomposition.saleDocumentCount.current)}</small>
        </div>
        <b aria-hidden="true">−</b>
        <div>
          <span>Возвраты</span>
          <strong>{formatValue(decomposition.returnRevenue.current, "RUB")}</strong>
          <small>{documentCountText(decomposition.returnDocumentCount.current)}</small>
        </div>
        <b aria-hidden="true">=</b>
        <div className="weekly-review-formula__result">
          <span>Чистая выручка</span>
          <strong>{formatValue(decomposition.netRevenue.current, "RUB")}</strong>
        </div>
      </div>
    </details>
  );
}

function ResultsSection({ review }: { review: WeeklyReview }) {
  return (
    <section className="weekly-review-results" aria-labelledby="weekly-review-results-title">
      <SectionHeading
        id="weekly-review-results-title"
        icon={<BarChart3 />}
        title="Результаты недели"
        meta={review.period.currentLabel}
      />
      <div className="weekly-review-results__grid">
        {review.results.map((metric) => (
          <MetricCard metric={metric} key={metric.metricId} />
        ))}
      </div>
      <RevenueFormula review={review} />
    </section>
  );
}

function SectionHeading({
  id,
  icon,
  title,
  meta
}: {
  id: string;
  icon: ReactNode;
  title: string;
  meta?: string;
}) {
  return (
    <div className="weekly-review-section-heading">
      <span aria-hidden="true">{icon}</span>
      <h2 id={id}>{title}</h2>
      {meta && <small>{meta}</small>}
    </div>
  );
}

function FactorCard({
  factor,
  evidenceByRef
}: {
  factor: WeeklyReview["factors"][number];
  evidenceByRef: EvidenceIndex;
}) {
  const tone = factor.effect === "POSITIVE" ? "positive" : "negative";
  return (
    <article className={`weekly-review-factor weekly-review-factor--${tone}`}>
      <div className="weekly-review-factor__heading">
        <div>
          <span>
            {factor.effect === "POSITIVE"
              ? "Положительная динамика"
              : "Зона внимания"}
          </span>
          <h3>{factor.title}</h3>
        </div>
      </div>
      <p>{factor.detail}</p>
      {factor.contributionAmount != null && (
        <small>
          Влияние на результат: {formatValue(factor.contributionAmount, "RUB")}
        </small>
      )}
      <EvidenceDisclosure
        evidenceRefs={factor.evidenceRefs}
        evidenceByRef={evidenceByRef}
      />
    </article>
  );
}

function ActionCard({ action, order }: { action: WeeklyReviewAction; order: number }) {
  return (
    <article className="weekly-review-action">
      <span className="weekly-review-action__order">
        {String(order).padStart(2, "0")}
      </span>
      <div>
        <h3>{action.title}</h3>
        <p className="weekly-review-action__horizon">На следующую полную неделю</p>
        <dl>
          <div>
            <dt>Цель</dt>
            <dd>{actionTargetText(action)}</dd>
          </div>
          <div>
            <dt>Как проверим</dt>
            <dd>{action.check}</dd>
          </div>
        </dl>
      </div>
    </article>
  );
}

function ChangesAndActions({
  review,
  evidenceByRef
}: {
  review: WeeklyReview;
  evidenceByRef: EvidenceIndex;
}) {
  return (
    <div className="weekly-review-decisions">
      <section aria-labelledby="weekly-review-factors-title">
        <SectionHeading
          id="weekly-review-factors-title"
          icon={<BarChart3 />}
          title="Основные изменения"
        />
        {review.factors.length > 0 ? (
          <div className="weekly-review-factor-list">
            {review.factors.map((factor) => (
              <FactorCard
                factor={factor}
                evidenceByRef={evidenceByRef}
                key={factor.factorId}
              />
            ))}
          </div>
        ) : (
          <p className="weekly-review-section-empty">Существенных изменений нет.</p>
        )}
      </section>
      <section aria-labelledby="weekly-review-actions-title">
        <SectionHeading
          id="weekly-review-actions-title"
          icon={<Target />}
          title="Следующие шаги"
        />
        {review.actions.length > 0 ? (
          <div className="weekly-review-action-list">
            {review.actions.map((action, index) => (
              <ActionCard action={action} order={index + 1} key={action.actionId} />
            ))}
          </div>
        ) : (
          <p className="weekly-review-section-empty">Дополнительные действия не требуются.</p>
        )}
      </section>
    </div>
  );
}

function StructureRow({
  node,
  depth = 0
}: {
  node: WeeklyReviewStructureNode;
  depth?: number;
}) {
  return (
    <>
      <div
        className={`weekly-review-structure-row weekly-review-structure-row--depth-${Math.min(depth, 2)}`}
      >
        <span>{node.label}</span>
        <strong>{formatValue(node.comparison.current, node.comparison.unit)}</strong>
        <small>{formatValue(node.shareComparison.current, "PERCENT")}</small>
        <em className={`weekly-review-tone weekly-review-tone--${metricTone(node.comparison)}`}>
          {metricComparisonText(node.comparison)}
        </em>
      </div>
      {node.children.map((child) => (
        <StructureRow node={child} depth={depth + 1} key={child.nodeId} />
      ))}
    </>
  );
}

function SalesStructure({ review }: { review: WeeklyReview }) {
  const block = review.salesStructure;
  const unavailable = block.state === "INSUFFICIENT" || block.state === "NOT_APPLICABLE";
  return (
    <details className="weekly-review-secondary">
      <summary>
        <div>
          <span>Структура продаж</span>
          <strong>{unavailable ? "—" : formatValue(block.root.comparison.current, "RUB")}</strong>
        </div>
        <BlockStateBadge state={block.state} />
        <ChevronDown aria-hidden="true" />
      </summary>
      <div className="weekly-review-secondary__body">
        {unavailable ? (
          <BlockEmpty state={block.state} />
        ) : <div className="weekly-review-structure">
          <div className="weekly-review-structure__head" aria-hidden="true">
            <span>Направление</span>
            <span>Выручка</span>
            <span>Доля</span>
            <span>Динамика</span>
          </div>
          {block.root.children.map((node) => (
            <StructureRow node={node} key={node.nodeId} />
          ))}
        </div>}
        {!unavailable && block.attachMetrics.length > 0 && (
          <div className="weekly-review-attach">
            <h3>Допродажи</h3>
            <div>
              {block.attachMetrics.map((item) => (
                <article key={item.metricId}>
                  <span>{item.label}</span>
                  <strong>
                    {formatValue(item.comparison.current, item.comparison.unit)}
                  </strong>
                  <small>{metricComparisonText(item.comparison)}</small>
                </article>
              ))}
            </div>
          </div>
        )}
        {block.limitations.map((limitation) => (
          <p className="weekly-review-inline-limitation" key={limitation}>
            {limitation}
          </p>
        ))}
      </div>
    </details>
  );
}

function ObservationCard({
  observation,
  evidenceByRef
}: {
  observation: WeeklyReviewObservation;
  evidenceByRef: EvidenceIndex;
}) {
  return (
    <article className={`weekly-review-observation weekly-review-observation--${observation.effect.toLowerCase()}`}>
      <h3>{observation.title}</h3>
      <p>{observation.detail}</p>
      <EvidenceDisclosure
        evidenceRefs={observation.evidenceRefs}
        evidenceByRef={evidenceByRef}
      />
    </article>
  );
}

function TeamSection({
  review,
  evidenceByRef
}: {
  review: WeeklyReview;
  evidenceByRef: EvidenceIndex;
}) {
  const team = review.team;
  const unavailable = team.state === "INSUFFICIENT" || team.state === "NOT_APPLICABLE";
  return (
    <section className="weekly-review-team" aria-labelledby="weekly-review-team-title">
      <SectionHeading
        id="weekly-review-team-title"
        icon={<Users />}
        title="Команда"
        meta={team.state === "READY" ? "Общая картина" : undefined}
      />
      <div className="weekly-review-team__body">
        <BlockStateBadge state={team.state} />
        {unavailable ? (
          <BlockEmpty state={team.state} />
        ) : <>
          <div className="weekly-review-team__stats">
          <article>
            <strong>{team.roster.activeAssignedWithActivity}</strong>
            <span>С продажами</span>
          </article>
          <article>
            <strong>{team.roster.participatesInBenchmark}</strong>
            <span>В сравнении</span>
          </article>
          <article>
            <strong>{team.attentionEmployeeCount}</strong>
            <span>Нужно внимание</span>
          </article>
        </div>
        {team.observations.length > 0 ? (
          <div className="weekly-review-team__observations">
            {team.observations.map((observation) => (
              <ObservationCard
                observation={observation}
                evidenceByRef={evidenceByRef}
                key={observation.observationId}
              />
            ))}
          </div>
        ) : (
          <p className="weekly-review-section-empty">Значимых изменений нет.</p>
        )}
        <small className="weekly-review-team__benchmark">
          Сравнение сотрудников: {team.benchmarkPolicy.label.toLocaleLowerCase("ru-RU")}
        </small>
        </>}
        {team.limitations.map((limitation) => (
          <p className="weekly-review-inline-limitation" key={limitation}>
            {limitation}
          </p>
        ))}
      </div>
    </section>
  );
}

function EmployeeMetric({
  metric,
  prominent = false
}: {
  metric: WeeklyReviewMetric;
  prominent?: boolean;
}) {
  return (
    <article className={prominent ? "weekly-review-employee-metric--prominent" : undefined}>
      <span>{metric.label}</span>
      <strong>{formatValue(metric.current, metric.unit)}</strong>
      <small className={`weekly-review-tone weekly-review-tone--${metricTone(metric)}`}>
        {metricComparisonText(metric)}
      </small>
    </article>
  );
}

function EmployeeAction({ action }: { action: WeeklyReviewAction }) {
  return (
    <section className="weekly-review-employee-action">
      <span>Следующий шаг</span>
      <h4>{action.title}</h4>
      <div>
        <small>Ориентир</small>
        <strong>{actionTargetText(action)}</strong>
      </div>
      <p>{action.check}</p>
    </section>
  );
}

function EmployeeDetails({
  employee,
  evidenceByRef
}: {
  employee: WeeklyReviewEmployee;
  evidenceByRef: EvidenceIndex;
}) {
  const metrics = employee.metrics;
  const ownObservationIds = new Set(employee.ownDynamics.map((item) => item.observationId));
  const strength = employee.strength
    && !ownObservationIds.has(employee.strength.observationId)
    ? employee.strength
    : null;
  const attention = employee.attention
    && !ownObservationIds.has(employee.attention.observationId)
    ? employee.attention
    : null;
  return (
    <div className="weekly-review-employee__body">
      <div className="weekly-review-employee__metrics">
        <EmployeeMetric metric={metrics.completedSales} />
        <EmployeeMetric metric={metrics.netRevenue} prominent />
        <EmployeeMetric metric={metrics.additionalShare} />
        <EmployeeMetric metric={metrics.revenuePerHour} />
      </div>

      <section className="weekly-review-employee__section">
        <h4>Динамика</h4>
        {employee.ownDynamics.length > 0 ? (
          <div className="weekly-review-employee__observations">
            {employee.ownDynamics.map((observation) => (
              <ObservationCard
                observation={observation}
                evidenceByRef={evidenceByRef}
                key={observation.observationId}
              />
            ))}
          </div>
        ) : (
          <p className="weekly-review-section-empty">Значимых изменений нет.</p>
        )}
      </section>

      {employee.peerComparison && (
        <section className="weekly-review-peer">
          <div>
            <span>Чистая выручка сотрудника</span>
            <strong>
              {formatValue(
                employee.peerComparison.employeeValue,
                metrics.netRevenue.unit
              )}
            </strong>
          </div>
          <div>
            <span>Медиана магазина</span>
            <strong>
              {formatValue(
                employee.peerComparison.benchmarkValue,
                metrics.netRevenue.unit
              )}
            </strong>
          </div>
          <small>
            В сравнении: {employee.peerComparison.eligibleCount} сотрудников
          </small>
        </section>
      )}

      {(strength || attention) && (
        <div className="weekly-review-employee__observations">
          {strength && (
            <ObservationCard
              observation={strength}
              evidenceByRef={evidenceByRef}
            />
          )}
          {attention && (
            <ObservationCard
              observation={attention}
              evidenceByRef={evidenceByRef}
            />
          )}
        </div>
      )}

      {employee.action && <EmployeeAction action={employee.action} />}

      {employee.limitations.length > 0 && (
        <div className="weekly-review-employee__limitations">
          {employee.limitations.map((limitation) => (
            <p key={limitation}>{limitation}</p>
          ))}
        </div>
      )}
    </div>
  );
}

function EmployeeCard({
  employee,
  evidenceByRef
}: {
  employee: WeeklyReviewEmployee;
  evidenceByRef: EvidenceIndex;
}) {
  const revenue = employee.metrics.netRevenue;
  const additional = employee.metrics.additionalShare;
  return (
    <details className="weekly-review-employee insight-employee">
      <summary>
        <span className="weekly-review-employee__avatar" aria-hidden="true">
          {initials(employee.displayName)}
        </span>
        <span className="weekly-review-employee__identity">
          <strong>{employee.displayName}</strong>
          <small>
            {employee.participatesInBenchmark
              ? "Участвует в сравнении"
              : "Вне сравнения команды"}
          </small>
        </span>
        <span className="weekly-review-employee__summary-metric">
          <small>Чистая выручка</small>
          <strong>{formatValue(revenue.current, revenue.unit)}</strong>
          <em className={`weekly-review-tone weekly-review-tone--${metricTone(revenue)}`}>
            {metricComparisonText(revenue)}
          </em>
        </span>
        <span className="weekly-review-employee__summary-metric">
          <small>Доля допродаж</small>
          <strong>{formatValue(additional.current, additional.unit)}</strong>
        </span>
        <ChevronDown className="weekly-review-employee__chevron" aria-hidden="true" />
      </summary>
      <EmployeeDetails employee={employee} evidenceByRef={evidenceByRef} />
    </details>
  );
}

function EmployeesSection({
  review,
  evidenceByRef
}: {
  review: WeeklyReview;
  evidenceByRef: EvidenceIndex;
}) {
  const [showAll, setShowAll] = useState(false);
  const visible = showAll
    ? review.employees
    : review.employees.slice(0, EMPLOYEE_PREVIEW_LIMIT);
  return (
    <section className="weekly-review-employees" aria-labelledby="weekly-review-employees-title">
      <SectionHeading
        id="weekly-review-employees-title"
        icon={<Users />}
        title="Сотрудники"
        meta={String(review.employees.length)}
      />
      {visible.length > 0 ? (
        <div className="weekly-review-employee-list">
          {visible.map((employee) => (
          <EmployeeCard
            employee={employee}
            evidenceByRef={evidenceByRef}
            key={employee.employeePublicId}
          />
          ))}
        </div>
      ) : (
        <p className="weekly-review-section-empty">Нет сотрудников с продажами за эту неделю.</p>
      )}
      {review.employees.length > EMPLOYEE_PREVIEW_LIMIT && (
        <button
          className="weekly-review-show-all"
          type="button"
          onClick={() => setShowAll((value) => !value)}
        >
          {showAll ? "Показать меньше" : `Показать всех — ${review.employees.length}`}
        </button>
      )}
    </section>
  );
}

function LimitationsSection({ review }: { review: WeeklyReview }) {
  const incompleteSources = review.sourceCoverage.filter((source) => {
    if (source.state !== "PARTIAL" && source.state !== "MISSING") return false;
    return !review.limitations.some((limitation) => source.affectedBlockIds.some(
      (blockId) => limitation.affectedBlockIds.includes(blockId)
    ));
  });
  if (review.limitations.length === 0 && incompleteSources.length === 0) {
    return null;
  }
  return (
    <details
      className="weekly-review-limitations"
      id="weekly-review-limitations"
      open={review.reportState === "BLOCKED"}
    >
      <summary>
        <div>
          <AlertTriangle aria-hidden="true" />
          <span>Ограничения данных</span>
          <small>{review.limitations.length + incompleteSources.length}</small>
        </div>
        <ChevronDown aria-hidden="true" />
      </summary>
      <div className="weekly-review-limitations__body">
        {review.limitations.map((limitation) => (
          <article key={limitation.limitationId}>
            <strong>{limitation.summary}</strong>
            {limitation.resolution && <p>{limitation.resolution}</p>}
            <small>Затронуто: {limitation.affectedCount}</small>
          </article>
        ))}
        {incompleteSources.map((source) => (
          <article key={source.sourceCode}>
            <strong>{sourceLabel(source.sourceCode)}</strong>
            {source.message && <p>{source.message}</p>}
          </article>
        ))}
      </div>
    </details>
  );
}

function EmptyReview({ onRetry }: { onRetry: () => void }) {
  return (
    <section className="weekly-review-empty" aria-live="polite">
      <span><RefreshCw aria-hidden="true" /></span>
      <h2>Разбор ещё не сформирован</h2>
      <p>Он появится после расчёта последней завершённой недели.</p>
      <button type="button" onClick={onRetry}>Проверить снова</button>
    </section>
  );
}

function BlockedReview({ review }: { review: WeeklyReview }) {
  return (
    <div className="weekly-review-blocked" role="alert">
      <span><AlertTriangle aria-hidden="true" /></span>
      <h2>Для разбора не хватает данных</h2>
      <p>{review.qualitySummary.message}</p>
      <LimitationsSection review={review} />
    </div>
  );
}

function PreparingReview({ review }: { review: WeeklyReview }) {
  return (
    <div className="weekly-review-blocked weekly-review-blocked--preparing" aria-live="polite">
      <span><RefreshCw aria-hidden="true" /></span>
      <h2>Разбор формируется</h2>
      <p>{review.qualitySummary.message}</p>
    </div>
  );
}

function ReadyReview({ review }: { review: WeeklyReview }) {
  const evidenceByRef = useMemo(
    () => new Map(review.evidence.map((item) => [item.evidenceRef, item])),
    [review.evidence]
  );
  return (
    <>
      <QualityNotice review={review} />
      <SummarySection review={review} evidenceByRef={evidenceByRef} />
      <ResultsSection review={review} />
      <ChangesAndActions review={review} evidenceByRef={evidenceByRef} />
      <SalesStructure review={review} />
      <TeamSection review={review} evidenceByRef={evidenceByRef} />
      <EmployeesSection review={review} evidenceByRef={evidenceByRef} />
      <LimitationsSection review={review} />
    </>
  );
}

export function WeeklyReviewView({
  storeId,
  fallback
}: {
  storeId: string;
  fallback?: ReactNode;
}) {
  const query = useQuery({
    queryKey: queryKeys.weeklyReview(storeId),
    queryFn: () => getWeeklyReview(storeId),
    refetchInterval: ({ state }) => refetchInterval(state.data)
  });

  if (query.isPending) {
    return (
      <section className="weekly-review weekly-review--loading">
        <PanelSkeleton rows={7} />
      </section>
    );
  }
  if (query.isError) {
    if (fallback) return <>{fallback}</>;
    return (
      <section className="weekly-review weekly-review--error">
        <QueryError error={query.error} onRetry={() => void query.refetch()} compact />
      </section>
    );
  }
  if (!query.data) {
    return fallback
      ? <>{fallback}</>
      : <EmptyReview onRetry={() => void query.refetch()} />;
  }

  const review = query.data;
  return (
    <article className="weekly-review" aria-label="Разбор завершённой недели">
      <ReviewHeader review={review} />
      {review.reportState === "BLOCKED" && <BlockedReview review={review} />}
      {review.reportState === "PREPARING" && <PreparingReview review={review} />}
      {(review.reportState === "READY" || review.reportState === "PARTIAL") && (
        <ReadyReview review={review} />
      )}
    </article>
  );
}
