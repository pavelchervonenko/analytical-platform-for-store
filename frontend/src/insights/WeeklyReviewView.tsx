import { useQuery } from "@tanstack/react-query";
import { useMemo, useState, type ReactNode } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  RefreshCw,
  Sparkles
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
  nextWeekLabel,
  reviewStateLabel,
  sourceLabel,
  type ReviewTone
} from "./weekly-review-presentation";
import "./weekly-review.css";

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
  if (state === "READY" || state === "LIMITED") return null;
  const label = state === "INSUFFICIENT"
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
  const aiEnhanced = review.summary.generatedBy === "AI_ENHANCED"
    && review.aiEnhancement.state === "READY";
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
          <span>Завершенная неделя:</span>
          <strong>{review.period.currentLabel}</strong>
        </div>
        <div>
          <span>Сравнение с:</span>
          <strong>{review.period.previousLabel}</strong>
        </div>
      </div>
      <div className="weekly-review-header__meta">
        <div className="weekly-review-header__status-row">
          {aiEnhanced && (
            <span className="weekly-review-source">
              <Sparkles aria-hidden="true" />
              Дополнено ИИ
            </span>
          )}
          <span className={`weekly-review-state ${stateClass}`} role="status">
            {review.reportState === "READY"
              ? <CheckCircle2 aria-hidden="true" />
              : review.reportState === "PREPARING"
                ? <RefreshCw aria-hidden="true" />
                : <AlertTriangle aria-hidden="true" />}
            {reviewStateLabel(review.reportState)}
          </span>
        </div>
        <small>Обновлено {formatCalculatedAt(review.provenance.calculatedAt)}</small>
      </div>
    </header>
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

type ResultMovement = {
  label: string;
  direction: WeeklyReviewMetric["direction"];
};

function resultMovement(
  metric: WeeklyReviewMetric | undefined,
  label: string
): ResultMovement | null {
  if (!metric || metric.current == null || metric.metricState === "UNAVAILABLE") return null;
  return { label, direction: metric.direction };
}

function joinSubjects(subjects: readonly string[]): string {
  if (subjects.length <= 1) return subjects[0] ?? "";
  return `${subjects.slice(0, -1).join(", ")} и ${subjects.at(-1)}`;
}

function movementClause(direction: ResultMovement["direction"], subjects: readonly string[]) {
  const plural = subjects.length > 1;
  const movement = direction === "UP"
    ? plural ? "выросли" : "выросла"
    : direction === "DOWN"
      ? plural ? "снизились" : "снизилась"
      : direction === "FLAT"
        ? plural ? "остались на прежнем уровне" : "осталась на прежнем уровне"
        : plural ? "рассчитаны" : "рассчитана";
  return `${joinSubjects(subjects)} ${movement}`;
}

function joinClauses(clauses: readonly string[]): string {
  if (clauses.length <= 1) return clauses[0] ?? "";
  return `${clauses.slice(0, -1).join(", ")}, а ${clauses.at(-1)}`;
}

function deterministicSummaryLead(review: WeeklyReview): string {
  const result = (code: string) => review.results.find((metric) => metric.code === code);
  const movements = [
    resultMovement(result("NET_REVENUE"), "чистая выручка"),
    resultMovement(result("GROSS_PROFIT"), "валовая прибыль"),
    resultMovement(result("MARGIN_PERCENT"), "маржа")
  ].filter((movement): movement is ResultMovement => movement !== null);
  if (movements.length === 0) return "Итог рассчитан по доступным данным завершенной недели.";
  const groupedMovements = new Map<ResultMovement["direction"], string[]>();
  movements.forEach(({ label, direction }) => {
    groupedMovements.set(direction, [...(groupedMovements.get(direction) ?? []), label]);
  });
  const clauses = Array.from(groupedMovements, ([direction, subjects]) => (
    movementClause(direction, subjects)
  ));
  const comparison = joinClauses(clauses);
  const outcome = `${comparison.charAt(0).toUpperCase()}${comparison.slice(1)}.`;
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
  const positiveFactor = matchingFactor(positive);
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
        {positive && (
          <div className="weekly-review-summary__signals">
            <SummarySignal
              label="Что улучшилось"
              text={positiveFactor?.title ?? positive.text}
              detail={factorContext(positiveFactor)}
              tone="positive"
              evidenceRefs={positive.evidenceRefs}
              evidenceByRef={evidenceByRef}
            />
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
  title,
  meta
}: {
  id: string;
  title: string;
  meta?: string;
}) {
  return (
    <div className="weekly-review-section-heading">
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

function ActionCard({ action }: { action: WeeklyReviewAction }) {
  return (
    <article className="weekly-review-action">
      <h3>{action.title}</h3>
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
          title="Шаги на следующую неделю"
          meta={nextWeekLabel(review.period.current.end)}
        />
        {review.actions.length > 0 ? (
          <div className="weekly-review-action-list">
            {review.actions.map((action) => (
              <ActionCard action={action} key={action.actionId} />
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
      </div>
    </section>
  );
}

function EmployeeMetric({ metric }: { metric: WeeklyReviewMetric }) {
  return (
    <article>
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
  const observations = [
    ...employee.ownDynamics,
    ...(strength ? [strength] : []),
    ...(attention ? [attention] : [])
  ].filter((item, index, items) => (
    items.findIndex((candidate) => candidate.observationId === item.observationId) === index
  ));
  return (
    <div className="weekly-review-employee__body">
      <div className="weekly-review-employee__metrics">
        <EmployeeMetric metric={metrics.completedSales} />
        <EmployeeMetric metric={metrics.additionalShare} />
        <EmployeeMetric metric={metrics.revenuePerHour} />
      </div>

      <div className="weekly-review-employee__analysis">
        <section className="weekly-review-employee__section">
          <h4>Динамика</h4>
          {observations.length > 0 ? (
            <div className="weekly-review-employee__observations">
              {observations.map((observation) => (
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
          <section className="weekly-review-employee__section weekly-review-employee__section--peer">
            <h4>Сравнение с командой</h4>
            <div className="weekly-review-peer">
              <div>
                <span>Сотрудник</span>
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
            </div>
          </section>
        )}
      </div>

      {employee.action && <EmployeeAction action={employee.action} />}

    </div>
  );
}

function EmployeeSelector({
  employee,
  selected,
  onSelect
}: {
  employee: WeeklyReviewEmployee;
  selected: boolean;
  onSelect: () => void;
}) {
  const revenue = employee.metrics.netRevenue;
  return (
    <button
      aria-pressed={selected}
      className="weekly-review-employee-selector"
      onClick={onSelect}
      type="button"
    >
      <span className="weekly-review-employee__avatar" aria-hidden="true">
        {initials(employee.displayName)}
      </span>
      <span className="weekly-review-employee__identity">
        <strong>{employee.displayName}</strong>
        <small>{formatValue(revenue.current, revenue.unit)}</small>
      </span>
      <em className={`weekly-review-tone weekly-review-tone--${metricTone(revenue)}`}>
        {metricComparisonText(revenue)}
      </em>
    </button>
  );
}

function EmployeeDetail({
  employee,
  evidenceByRef
}: {
  employee: WeeklyReviewEmployee;
  evidenceByRef: EvidenceIndex;
}) {
  const revenue = employee.metrics.netRevenue;
  return (
    <article className="weekly-review-employee-detail insight-employee">
      <header className="weekly-review-employee-detail__header">
        <div>
          <span className="weekly-review-employee__avatar" aria-hidden="true">
            {initials(employee.displayName)}
          </span>
          <span className="weekly-review-employee__identity">
            <h3>{employee.displayName}</h3>
            <small>
              {employee.participatesInBenchmark
                ? "Участвует в сравнении"
                : "Вне сравнения команды"}
            </small>
          </span>
        </div>
        <span className="weekly-review-employee__summary-metric">
          <small>Чистая выручка</small>
          <strong>{formatValue(revenue.current, revenue.unit)}</strong>
          <em className={`weekly-review-tone weekly-review-tone--${metricTone(revenue)}`}>
            {metricComparisonText(revenue)}
          </em>
        </span>
      </header>
      <EmployeeDetails employee={employee} evidenceByRef={evidenceByRef} />
    </article>
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
  const [selectedEmployeeId, setSelectedEmployeeId] = useState(
    review.employees[0]?.employeePublicId ?? null
  );
  const visible = showAll
    ? review.employees
    : review.employees.slice(0, EMPLOYEE_PREVIEW_LIMIT);
  const selectedEmployee = visible.find(
    (employee) => employee.employeePublicId === selectedEmployeeId
  ) ?? visible[0] ?? null;
  return (
    <section className="weekly-review-employees" aria-labelledby="weekly-review-employees-title">
      <SectionHeading
        id="weekly-review-employees-title"
        title="Сотрудники"
        meta={String(review.employees.length)}
      />
      {visible.length > 0 ? (
        <div className="weekly-review-employee-workspace">
          <div className="weekly-review-employee-list" aria-label="Выбор сотрудника">
            {visible.map((employee) => (
              <EmployeeSelector
                employee={employee}
                key={employee.employeePublicId}
                onSelect={() => setSelectedEmployeeId(employee.employeePublicId)}
                selected={employee.employeePublicId === selectedEmployee?.employeePublicId}
              />
            ))}
          </div>
          {selectedEmployee && (
            <EmployeeDetail employee={selectedEmployee} evidenceByRef={evidenceByRef} />
          )}
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
      <h2>Разбор еще не сформирован</h2>
      <p>Он появится после расчета последней завершенной недели.</p>
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
      <SummarySection review={review} evidenceByRef={evidenceByRef} />
      <ResultsSection review={review} />
      <ChangesAndActions review={review} evidenceByRef={evidenceByRef} />
      <SalesStructure review={review} />
      <TeamSection review={review} evidenceByRef={evidenceByRef} />
      <EmployeesSection review={review} evidenceByRef={evidenceByRef} />
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
    <article className="weekly-review" aria-label="Разбор завершенной недели">
      <ReviewHeader review={review} />
      {review.reportState === "BLOCKED" && <BlockedReview review={review} />}
      {review.reportState === "PREPARING" && <PreparingReview review={review} />}
      {(review.reportState === "READY" || review.reportState === "PARTIAL") && (
        <ReadyReview review={review} />
      )}
    </article>
  );
}
