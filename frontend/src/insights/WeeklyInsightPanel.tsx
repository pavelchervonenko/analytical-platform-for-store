import { useQuery } from "@tanstack/react-query";
import {
  BrainCircuit,
  CheckCircle2,
  Clock3,
  Lightbulb,
  RefreshCw,
  Sparkles,
  Target,
  TrendingUp,
  TriangleAlert,
  Users
} from "lucide-react";
import { getWeeklyInsight, queryKeys } from "../api/queries";
import type {
  WeeklyInsight,
  WeeklyInsightEmployee,
  WeeklyInsightEvidence,
  WeeklyInsightStore
} from "../api/weeklyInsightContract";
import { formatDate } from "../shared/date";
import { PanelSkeleton, QueryError } from "../shared/QueryState";
import {
  actionHorizonLabel,
  actionTypeLabel,
  analysisStatusLabel,
  analysisStatusTone,
  employeeAnalysisHelp,
  insightKindHelp,
  insightKindLabel,
  insightKindTone,
  limitationSummary,
  uniqueNarratives
} from "./presentation";
import "./weekly-insight.css";

function refetchInterval(insight: WeeklyInsight | undefined): number | false {
  if (!insight) return false;
  if (insight.state === "PREPARING" || insight.state === "DELAYED") return 15_000;
  if (insight.state === "READY" && insight.revisionState === "UPDATING") return 15_000;
  return false;
}

function InsightStatus({ insight }: { insight: WeeklyInsight }) {
  const delayed = insight.state === "DELAYED"
    || insight.revisionState === "UPDATE_DELAYED";
  const className = insight.state === "READY" && !delayed
    ? "weekly-insight-status weekly-insight-status--ready"
    : delayed
      ? "weekly-insight-status weekly-insight-status--warning"
      : "weekly-insight-status";
  return (
    <span className={className}>
      {insight.state === "READY" && !delayed
        ? <CheckCircle2 size={15} />
        : delayed
          ? <TriangleAlert size={15} />
          : <RefreshCw size={15} />}
      {insight.state === "READY"
        ? insight.revisionState === "CURRENT" ? "Готово" : "Обновляется"
        : insight.state === "DELAYED" ? "Есть задержка" : "Готовится"}
    </span>
  );
}

type EvidenceIndex = ReadonlyMap<string, WeeklyInsightEvidence>;

function EvidenceFacts({
  evidenceRefs,
  evidenceByCode,
  caption
}: {
  evidenceRefs?: string[];
  evidenceByCode: EvidenceIndex;
  caption?: string;
}) {
  const evidence = (evidenceRefs ?? [])
    .map((code) => evidenceByCode.get(code))
    .filter((value): value is WeeklyInsightEvidence => value !== undefined);
  if (evidence.length === 0) return null;
  const limited = evidence.some(
    (item) => !item.available
      || item.sufficiency === "LIMITED"
      || item.sufficiency === "INSUFFICIENT"
  );
  const visibleCaption = limited
    ? "Данные с ограничениями"
    : caption ?? "Подтверждено данными";

  return (
    <div className="weekly-evidence" aria-label={visibleCaption}>
      <span className={limited
        ? "weekly-evidence__caption weekly-evidence__caption--limited"
        : "weekly-evidence__caption"}>
        {visibleCaption}
      </span>
      {evidence.map((item) => (
        <div className="weekly-evidence__item" key={item.evidenceCode}>
          <span>{item.label}</span>
          <strong>{item.formattedValue ?? "Значение недоступно"}</strong>
          {item.comparisonText && <small>{item.comparisonText}</small>}
          {item.sufficiency && item.sufficiency !== "SUFFICIENT" && (
            <em>
              {item.sufficiency === "LIMITED"
                ? "Ограниченная выборка"
                : "Недостаточно данных"}
            </em>
          )}
        </div>
      ))}
    </div>
  );
}

function NarrativeWithEvidence({
  value,
  evidenceByCode,
  className
}: {
  value: { text: string; evidenceRefs?: string[] };
  evidenceByCode: EvidenceIndex;
  className?: string;
}) {
  const classes = className
    ? `weekly-narrative ${className}`
    : "weekly-narrative";
  return (
    <div className={classes}>
      <p>{value.text}</p>
      <EvidenceFacts
        evidenceRefs={value.evidenceRefs}
        evidenceByCode={evidenceByCode}
      />
    </div>
  );
}

function InsightKindBadge({ kind }: { kind?: string }) {
  if (!kind) return null;
  return (
    <em className={
      `weekly-insight-kind weekly-insight-kind--${insightKindTone(kind)}`
    }>
      {insightKindLabel(kind)}
    </em>
  );
}

function HypothesisHelp({ kind }: { kind?: string }) {
  const help = kind ? insightKindHelp(kind) : null;
  return help
    ? <small className="weekly-insight-hypothesis-help">{help}</small>
    : null;
}

function InsightBlock({
  label,
  value,
  tone = "neutral",
  evidenceByCode
}: {
  label: string;
  value: {
    kind: string;
    title?: string;
    summary: string;
    evidenceRefs?: string[];
  } | null;
  tone?: "neutral" | "positive" | "warning";
  evidenceByCode: EvidenceIndex;
}) {
  if (!value) return null;
  return (
    <article className={`weekly-insight-block weekly-insight-block--${tone}`}>
      <span>{label}</span>
      <InsightKindBadge kind={value.kind} />
      {value.title && <strong>{value.title}</strong>}
      <p>{value.summary}</p>
      <HypothesisHelp kind={value.kind} />
      <EvidenceFacts
        evidenceRefs={value.evidenceRefs}
        evidenceByCode={evidenceByCode}
        caption={value.kind === "HYPOTHESIS"
          ? "Основание гипотезы"
          : undefined}
      />
    </article>
  );
}

type InsightListItem = {
  kind: string;
  title: string;
  summary: string;
  evidenceRefs: string[];
};

function InsightItemList({
  label,
  items,
  tone = "neutral",
  evidenceByCode
}: {
  label: string;
  items: InsightListItem[];
  tone?: "neutral" | "category" | "attach" | "team";
  evidenceByCode: EvidenceIndex;
}) {
  const uniqueItems = uniqueNarratives(
    items,
    (item) => `${item.title}\n${item.summary}`
  );
  if (uniqueItems.length === 0) return null;
  return (
    <div className={`weekly-insight-item-group weekly-insight-item-group--${tone}`}>
      <span>{label}</span>
      {uniqueItems.map((item, index) => (
        <div key={`${item.kind}:${item.title}:${index}`}>
          <InsightKindBadge kind={item.kind} />
          <strong>{item.title}</strong>
          <small>{item.summary}</small>
          <HypothesisHelp kind={item.kind} />
          <EvidenceFacts
            evidenceRefs={item.evidenceRefs}
            evidenceByCode={evidenceByCode}
            caption={item.kind === "HYPOTHESIS"
              ? "Основание гипотезы"
              : undefined}
          />
        </div>
      ))}
    </div>
  );
}

function EmployeeInsight({
  employee,
  evidenceByCode
}: {
  employee: WeeklyInsightEmployee;
  evidenceByCode: EvidenceIndex;
}) {
  const insight = employee.insight;
  const status = employee.analysisStatus || insight.analysisStatus;
  const tone = analysisStatusTone(status);
  const limitations = uniqueNarratives(
    insight.dataLimitations,
    (item) => item.summary
  );

  return (
    <details className="weekly-employee-insight">
      <summary>
        <span className="weekly-employee-insight__avatar">
          {employee.displayName.trim().slice(0, 1).toLocaleUpperCase("ru-RU")}
        </span>
        <span>
          <strong>{employee.displayName}</strong>
          <small>{insight.headline.text}</small>
          <em className={`weekly-employee-insight__status weekly-employee-insight__status--${tone}`}>
            {analysisStatusLabel(status)}
          </em>
        </span>
      </summary>
      <div className="weekly-employee-insight__body">
        {status === "INSUFFICIENT" && (
          <div className="weekly-employee-insight__notice">
            <TriangleAlert size={17} />
            <div>
              <strong>Почему подробного разбора пока нет</strong>
              <p>{employeeAnalysisHelp(status)}</p>
            </div>
          </div>
        )}
        <EvidenceFacts
          evidenceRefs={insight.headline.evidenceRefs}
          evidenceByCode={evidenceByCode}
        />
        {insight.workloadContext && (
          <NarrativeWithEvidence
            value={insight.workloadContext}
            evidenceByCode={evidenceByCode}
          />
        )}
        {insight.performanceSummary && (
          <NarrativeWithEvidence
            value={insight.performanceSummary}
            evidenceByCode={evidenceByCode}
          />
        )}
        {insight.dynamicsSummary && (
          <NarrativeWithEvidence
            value={insight.dynamicsSummary}
            evidenceByCode={evidenceByCode}
          />
        )}
        <div className="weekly-insight-blocks weekly-insight-blocks--employee">
          <InsightBlock
            label="Сильная сторона"
            value={insight.strength}
            tone="positive"
            evidenceByCode={evidenceByCode}
          />
          <InsightBlock
            label="Зона внимания"
            value={insight.attentionArea}
            evidenceByCode={evidenceByCode}
          />
          <InsightBlock
            label="Риск"
            value={insight.primaryRisk}
            tone="warning"
            evidenceByCode={evidenceByCode}
          />
        </div>
        {(insight.categoryPerformance || insight.additionalSalesPerformance) && (
          <div className="weekly-employee-details">
            {insight.categoryPerformance && (
              <article className="weekly-employee-detail weekly-employee-detail--category">
                <strong>Категории продаж</strong>
                {insight.categoryPerformance.summary && (
                  <NarrativeWithEvidence
                    value={insight.categoryPerformance.summary}
                    evidenceByCode={evidenceByCode}
                  />
                )}
                <InsightItemList
                  label="Сильные категории"
                  items={insight.categoryPerformance.strengths}
                  tone="category"
                  evidenceByCode={evidenceByCode}
                />
                <InsightItemList
                  label="Зоны внимания"
                  items={insight.categoryPerformance.attentionAreas}
                  tone="category"
                  evidenceByCode={evidenceByCode}
                />
                <InsightItemList
                  label="Динамика"
                  items={insight.categoryPerformance.dynamics}
                  tone="category"
                  evidenceByCode={evidenceByCode}
                />
              </article>
            )}
            {insight.additionalSalesPerformance && (
              <article className="weekly-employee-detail weekly-employee-detail--attach">
                <strong>Дополнительные продажи</strong>
                {insight.additionalSalesPerformance.summary && (
                  <NarrativeWithEvidence
                    value={insight.additionalSalesPerformance.summary}
                    evidenceByCode={evidenceByCode}
                  />
                )}
                <InsightItemList
                  label="Выручка"
                  items={insight.additionalSalesPerformance.revenueInsights}
                  evidenceByCode={evidenceByCode}
                />
                <InsightItemList
                  label="Attach-rate · допы на 100 единиц техники"
                  items={insight.additionalSalesPerformance.attachRateInsights}
                  tone="attach"
                  evidenceByCode={evidenceByCode}
                />
                <InsightItemList
                  label="Возможности роста"
                  items={insight.additionalSalesPerformance.opportunities}
                  evidenceByCode={evidenceByCode}
                />
              </article>
            )}
          </div>
        )}
        {insight.recommendedActions.length > 0 && (
          <div className="weekly-insight-actions">
            <strong>Рекомендованные действия</strong>
            <ul>{uniqueNarratives(
              insight.recommendedActions,
              (action) => `${action.title}\n${action.summary}`
            ).map((action) => (
              <li key={`${action.type}:${action.title}`}>
                <span>{actionTypeLabel(action.type)} · {actionHorizonLabel(action.horizon)}</span>
                <strong>{action.title}</strong>
                <p>{action.summary}</p>
                <EvidenceFacts
                  evidenceRefs={action.evidenceRefs}
                  evidenceByCode={evidenceByCode}
                  caption="Основание рекомендации"
                />
              </li>
            ))}</ul>
          </div>
        )}
        {limitations.length > 0 && (
          <div className="weekly-employee-limitations">
            <strong>Ограничения данных</strong>
            {limitations.map((limitation) => (
              <div key={`${employee.employeeId}:limitation:${limitation.code}`}>
                <p>{limitationSummary(limitation)}</p>
                <EvidenceFacts
                  evidenceRefs={limitation.evidenceRefs}
                  evidenceByCode={evidenceByCode}
                />
              </div>
            ))}
          </div>
        )}
      </div>
    </details>
  );
}

function TeamExperience({
  insight,
  evidenceByCode
}: {
  insight: NonNullable<WeeklyInsight["content"]>["teamInsights"];
  evidenceByCode: EvidenceIndex;
}) {
  const hasItems = insight.competencyLeaders.length > 0
    || insight.mostImproved.length > 0
    || insight.learningOpportunities.length > 0;
  if (!hasItems) return null;

  return (
    <section className="weekly-team-experience">
      <div className="weekly-insight-subheading">
        <div><span>Команда</span><h3>Лидеры и обмен опытом</h3></div>
      </div>
      <div className="weekly-team-experience__grid">
        {insight.competencyLeaders.map((leader, index) => (
          <article key={`leader:${index}`}>
            <strong>{leader.employeeNames.join(", ") || "Лидер команды"}</strong>
            <p>{leader.summary}</p>
            <EvidenceFacts
              evidenceRefs={leader.evidenceRefs}
              evidenceByCode={evidenceByCode}
            />
          </article>
        ))}
        {insight.mostImproved.map((employee, index) => (
          <article key={`improved:${employee.employeeRef}:${index}`}>
            <strong>{employee.displayName ?? "Заметная динамика"}</strong>
            <p>{employee.summary}</p>
            <EvidenceFacts
              evidenceRefs={employee.evidenceRefs}
              evidenceByCode={evidenceByCode}
            />
          </article>
        ))}
        {insight.learningOpportunities.map((opportunity, index) => (
          <article key={`learning:${index}`}>
            <strong>
              {opportunity.mentorNames.join(", ") || "Обмен опытом"}
              {opportunity.targetNames.length > 0
                ? ` → ${opportunity.targetNames.join(", ")}`
                : ""}
            </strong>
            <p>{opportunity.summary}</p>
            <EvidenceFacts
              evidenceRefs={opportunity.evidenceRefs}
              evidenceByCode={evidenceByCode}
            />
          </article>
        ))}
      </div>
    </section>
  );
}

function ReadyInsight({ insight }: { insight: WeeklyInsight & { content: NonNullable<WeeklyInsight["content"]> } }) {
  const store: WeeklyInsightStore = insight.content.store;
  const hasSummaries = store.resultSummary
    || store.dynamicsSummary
    || store.planOutlook;
  const teamHasDetails = insight.content.teamInsights.highlights.length > 0
    || insight.content.teamInsights.competencyLeaders.length > 0
    || insight.content.teamInsights.mostImproved.length > 0
    || insight.content.teamInsights.learningOpportunities.length > 0;
  const limitations = uniqueNarratives(
    insight.content.dataLimitations,
    (item) => item.summary
  );
  const evidenceByCode: EvidenceIndex = new Map(
    insight.content.evidence.map((item) => [item.evidenceCode, item])
  );

  return (
    <>
      <div className="weekly-insight-hero">
        <span><Sparkles size={18} /> Главное за неделю</span>
        <h2>{store.headline.text}</h2>
        <EvidenceFacts
          evidenceRefs={store.headline.evidenceRefs}
          evidenceByCode={evidenceByCode}
        />
        {hasSummaries && (
          <div className="weekly-insight-summaries">
            {store.resultSummary && (
              <article>
                <strong>Результат</strong>
                <NarrativeWithEvidence
                  value={store.resultSummary}
                  evidenceByCode={evidenceByCode}
                />
              </article>
            )}
            {store.dynamicsSummary && (
              <article>
                <strong>Динамика</strong>
                <NarrativeWithEvidence
                  value={store.dynamicsSummary}
                  evidenceByCode={evidenceByCode}
                />
              </article>
            )}
            {store.planOutlook && (
              <article>
                <strong>План</strong>
                <NarrativeWithEvidence
                  value={store.planOutlook}
                  evidenceByCode={evidenceByCode}
                />
              </article>
            )}
          </div>
        )}
      </div>

      <div className="weekly-insight-blocks">
        <InsightBlock
          label="Сильная сторона"
          value={store.strength}
          tone="positive"
          evidenceByCode={evidenceByCode}
        />
        <InsightBlock
          label="Зона внимания"
          value={store.attentionArea}
          evidenceByCode={evidenceByCode}
        />
        <InsightBlock
          label="Главный риск"
          value={store.primaryRisk}
          tone="warning"
          evidenceByCode={evidenceByCode}
        />
      </div>

      <div className="weekly-insight-columns">
        {store.categoryPerformance && (
          <article className="weekly-insight-column weekly-insight-column--category">
            <span className="weekly-insight-section-icon"><TrendingUp size={18} /></span>
            <h3>Категории продаж</h3>
            {store.categoryPerformance.summary && (
              <NarrativeWithEvidence
                value={store.categoryPerformance.summary}
                evidenceByCode={evidenceByCode}
              />
            )}
            <InsightItemList
              label="Драйверы роста"
              items={store.categoryPerformance.growthDrivers}
              tone="category"
              evidenceByCode={evidenceByCode}
            />
            <InsightItemList
              label="Снижение"
              items={store.categoryPerformance.declineDrivers}
              tone="category"
              evidenceByCode={evidenceByCode}
            />
            <InsightItemList
              label="Структура продаж"
              items={store.categoryPerformance.mixInsights}
              tone="category"
              evidenceByCode={evidenceByCode}
            />
          </article>
        )}
        {store.additionalSalesPerformance && (
          <article className="weekly-insight-column weekly-insight-column--attach">
            <span className="weekly-insight-section-icon"><Target size={18} /></span>
            <h3>Дополнительные продажи</h3>
            {store.additionalSalesPerformance.summary && (
              <NarrativeWithEvidence
                value={store.additionalSalesPerformance.summary}
                evidenceByCode={evidenceByCode}
              />
            )}
            <InsightItemList
              label="Выручка"
              items={store.additionalSalesPerformance.revenueInsights}
              evidenceByCode={evidenceByCode}
            />
            <InsightItemList
              label="Attach-rate · допы на 100 единиц техники"
              items={store.additionalSalesPerformance.attachRateInsights}
              tone="attach"
              evidenceByCode={evidenceByCode}
            />
            <InsightItemList
              label="Возможности роста"
              items={store.additionalSalesPerformance.opportunities}
              evidenceByCode={evidenceByCode}
            />
          </article>
        )}
        <article className="weekly-insight-column weekly-insight-column--team">
          <span className="weekly-insight-section-icon"><Users size={18} /></span>
          <h3>Команда</h3>
          <NarrativeWithEvidence
            value={insight.content.teamInsights.summary}
            evidenceByCode={evidenceByCode}
          />
          <InsightItemList
            label="Главное по команде"
            items={insight.content.teamInsights.highlights}
            tone="team"
            evidenceByCode={evidenceByCode}
          />
          {!teamHasDetails && (
            <div className="weekly-insight-team-note">
              <Users size={16} />
              <span>Сравнительные выводы появятся, когда минимум у трёх сотрудников будет достаточно подтверждённых данных.</span>
            </div>
          )}
        </article>
      </div>

      {store.recommendedActions.length > 0 && (
        <section className="weekly-insight-focus weekly-insight-focus--list">
          <div className="weekly-insight-focus-heading">
            <Lightbulb size={21} />
            <span>Рекомендованные действия</span>
          </div>
          <ul>{uniqueNarratives(
            store.recommendedActions,
            (action) => `${action.title}\n${action.summary}`
          ).map((action, index) => (
            <li key={`${action.type}:${action.title}:${index}`}>
              <span>{actionTypeLabel(action.type)} · {actionHorizonLabel(action.horizon)}</span>
              <strong>{action.title}</strong>
              <p>{action.summary}</p>
              <EvidenceFacts
                evidenceRefs={action.evidenceRefs}
                evidenceByCode={evidenceByCode}
                caption="Основание рекомендации"
              />
            </li>
          ))}</ul>
        </section>
      )}

      <TeamExperience
        insight={insight.content.teamInsights}
        evidenceByCode={evidenceByCode}
      />

      {insight.content.employees.length > 0 && (
        <section className="weekly-insight-employees">
          <div className="weekly-insight-subheading">
            <div><span>Команда</span><h3>Интерпретация по сотрудникам</h3></div>
            <small>{insight.content.employees.length}</small>
          </div>
          {insight.content.employees.map((employee) => (
            <EmployeeInsight
              key={employee.employeeId}
              employee={employee}
              evidenceByCode={evidenceByCode}
            />
          ))}
        </section>
      )}

      {limitations.length > 0 && (
        <section className="weekly-insight-limitations">
          <TriangleAlert size={18} />
          <div>
            <strong>Ограничения данных</strong>
            {limitations.map((item) => (
              <div key={`${item.code}:${item.scope}:${item.employeeRef ?? "store"}`}>
                <p>{item.summary}</p>
                <EvidenceFacts
                  evidenceRefs={item.evidenceRefs}
                  evidenceByCode={evidenceByCode}
                />
              </div>
            ))}
          </div>
        </section>
      )}
    </>
  );
}

function AvailabilityInsight({ insight }: { insight: WeeklyInsight }) {
  return (
    <div className="weekly-insight-availability" aria-live="polite">
      <span>{insight.state === "UNAVAILABLE" ? <TriangleAlert /> : <Clock3 />}</span>
      <div>
        <h2>{insight.fallback?.title ?? "Интерпретация недели"}</h2>
        <p>{insight.fallback?.summary ?? insight.message}</p>
        {insight.fallback && insight.fallback.dataLimitationCodes.length > 0 && (
          <small>Есть ограничения качества данных — подробности доступны в разделе качества.</small>
        )}
      </div>
    </div>
  );
}

export function WeeklyInsightPanel({ storeId }: { storeId: string }) {
  const query = useQuery({
    queryKey: queryKeys.weeklyInsight(storeId),
    queryFn: () => getWeeklyInsight(storeId),
    staleTime: 15_000,
    refetchInterval: (state) => refetchInterval(state.state.data),
    refetchOnWindowFocus: true
  });

  return (
    <section className="panel weekly-insight" aria-label="Интерпретация результатов недели">
      <div className="weekly-insight-heading">
        <div>
          <span className="eyebrow"><BrainCircuit size={15} /> Аналитическая интерпретация</span>
          <h2>Итоги прошлой недели</h2>
          {query.data && (
            <p>
              {formatDate(query.data.period.periodStart)} — {formatDate(query.data.period.periodEnd)}
              {query.data.revision ? ` · ревизия ${query.data.revision}` : ""}
              {query.data.publishedAt
                ? ` · опубликовано ${formatDate(query.data.publishedAt.slice(0, 10))}`
                : ""}
            </p>
          )}
        </div>
        {query.data && <InsightStatus insight={query.data} />}
      </div>

      {query.isPending && <PanelSkeleton rows={4} />}
      {query.isError && <QueryError error={query.error} onRetry={() => void query.refetch()} compact />}
      {query.data?.state === "READY" && query.data.content
        ? <ReadyInsight insight={{ ...query.data, content: query.data.content }} />
        : query.data && <AvailabilityInsight insight={query.data} />}
    </section>
  );
}
