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
  WeeklyInsightStore
} from "../api/weeklyInsightContract";
import { formatDate } from "../shared/date";
import { PanelSkeleton, QueryError } from "../shared/QueryState";
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

function InsightBlock({
  label,
  value,
  tone = "neutral"
}: {
  label: string;
  value: { title?: string; summary: string } | null;
  tone?: "neutral" | "positive" | "warning";
}) {
  if (!value) return null;
  return (
    <article className={`weekly-insight-block weekly-insight-block--${tone}`}>
      <span>{label}</span>
      {value.title && <strong>{value.title}</strong>}
      <p>{value.summary}</p>
    </article>
  );
}

function EmployeeInsight({ employee }: { employee: WeeklyInsightEmployee }) {
  const insight = employee.insight;
  const categoryItems = insight.categoryPerformance
    ? [
        ...insight.categoryPerformance.strengths,
        ...insight.categoryPerformance.attentionAreas,
        ...insight.categoryPerformance.dynamics
      ]
    : [];
  const additionalItems = insight.additionalSalesPerformance
    ? [
        ...insight.additionalSalesPerformance.revenueInsights,
        ...insight.additionalSalesPerformance.attachRateInsights,
        ...insight.additionalSalesPerformance.opportunities
      ]
    : [];

  return (
    <details className="weekly-employee-insight">
      <summary>
        <span className="weekly-employee-insight__avatar">
          {employee.displayName.trim().slice(0, 1).toLocaleUpperCase("ru-RU")}
        </span>
        <span>
          <strong>{employee.displayName}</strong>
          <small>{insight.headline.text}</small>
        </span>
      </summary>
      <div className="weekly-employee-insight__body">
        {insight.workloadContext && <p>{insight.workloadContext.text}</p>}
        {insight.performanceSummary && <p>{insight.performanceSummary.text}</p>}
        {insight.dynamicsSummary && <p>{insight.dynamicsSummary.text}</p>}
        <div className="weekly-insight-blocks weekly-insight-blocks--employee">
          <InsightBlock label="Сильная сторона" value={insight.strength} tone="positive" />
          <InsightBlock label="Зона внимания" value={insight.attentionArea} />
          <InsightBlock label="Риск" value={insight.primaryRisk} tone="warning" />
        </div>
        {(insight.categoryPerformance || insight.additionalSalesPerformance) && (
          <div className="weekly-employee-details">
            {insight.categoryPerformance && (
              <article>
                <strong>Категории продаж</strong>
                {insight.categoryPerformance.summary && (
                  <p>{insight.categoryPerformance.summary.text}</p>
                )}
                {categoryItems.map((item) => (
                  <small key={`${item.kind}:${item.title}`}>{item.summary}</small>
                ))}
              </article>
            )}
            {insight.additionalSalesPerformance && (
              <article>
                <strong>Дополнительные продажи</strong>
                {insight.additionalSalesPerformance.summary && (
                  <p>{insight.additionalSalesPerformance.summary.text}</p>
                )}
                {additionalItems.map((item) => (
                  <small key={`${item.kind}:${item.title}`}>{item.summary}</small>
                ))}
              </article>
            )}
          </div>
        )}
        {insight.recommendedActions.length > 0 && (
          <div className="weekly-insight-actions">
            <strong>Возможные действия</strong>
            <ul>{insight.recommendedActions.map((action) => (
              <li key={`${action.type}:${action.title}`}>{action.summary}</li>
            ))}</ul>
          </div>
        )}
        {insight.dataLimitations.length > 0 && (
          <div className="weekly-employee-limitations">
            <strong>Ограничения данных</strong>
            {insight.dataLimitations.map((limitation, index) => (
              <p key={`${employee.employeeId}:limitation:${index}`}>
                {"summary" in Object(limitation)
                  ? String((limitation as { summary: unknown }).summary)
                  : "Часть данных недоступна для уверенного вывода."}
              </p>
            ))}
          </div>
        )}
      </div>
    </details>
  );
}

function TeamExperience({
  insight
}: {
  insight: NonNullable<WeeklyInsight["content"]>["teamInsights"];
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
          </article>
        ))}
        {insight.mostImproved.map((employee, index) => (
          <article key={`improved:${employee.employeeRef}:${index}`}>
            <strong>{employee.displayName ?? "Заметная динамика"}</strong>
            <p>{employee.summary}</p>
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
          </article>
        ))}
      </div>
    </section>
  );
}

function ReadyInsight({ insight }: { insight: WeeklyInsight & { content: NonNullable<WeeklyInsight["content"]> } }) {
  const store: WeeklyInsightStore = insight.content.store;
  const categoryItems = store.categoryPerformance
    ? [
        ...store.categoryPerformance.growthDrivers,
        ...store.categoryPerformance.declineDrivers,
        ...store.categoryPerformance.mixInsights
      ]
    : [];
  const additionalItems = store.additionalSalesPerformance
    ? [
        ...store.additionalSalesPerformance.revenueInsights,
        ...store.additionalSalesPerformance.attachRateInsights,
        ...store.additionalSalesPerformance.opportunities
      ]
    : [];
  const hasSummaries = store.resultSummary
    || store.dynamicsSummary
    || store.planOutlook;

  return (
    <>
      <div className="weekly-insight-hero">
        <span><Sparkles size={18} /> Главное за неделю</span>
        <h2>{store.headline.text}</h2>
        {hasSummaries && (
          <div className="weekly-insight-summaries">
            {store.resultSummary && (
              <p><strong>Результат</strong>{store.resultSummary.text}</p>
            )}
            {store.dynamicsSummary && (
              <p><strong>Динамика</strong>{store.dynamicsSummary.text}</p>
            )}
            {store.planOutlook && (
              <p><strong>План</strong>{store.planOutlook.text}</p>
            )}
          </div>
        )}
      </div>

      <div className="weekly-insight-blocks">
        <InsightBlock label="Сильная сторона" value={store.strength} tone="positive" />
        <InsightBlock label="Зона внимания" value={store.attentionArea} />
        <InsightBlock label="Главный риск" value={store.primaryRisk} tone="warning" />
      </div>

      <div className="weekly-insight-columns">
        {store.categoryPerformance && (
          <article>
            <span className="weekly-insight-section-icon"><TrendingUp size={18} /></span>
            <h3>Категории продаж</h3>
            {store.categoryPerformance.summary && (
              <p>{store.categoryPerformance.summary.text}</p>
            )}
            {categoryItems.map((item, index) => (
              <small key={`category:${item.kind}:${item.title}:${index}`}>
                {item.summary}
              </small>
            ))}
          </article>
        )}
        {store.additionalSalesPerformance && (
          <article>
            <span className="weekly-insight-section-icon"><Target size={18} /></span>
            <h3>Дополнительные продажи</h3>
            {store.additionalSalesPerformance.summary && (
              <p>{store.additionalSalesPerformance.summary.text}</p>
            )}
            {additionalItems.map((item, index) => (
              <small key={`additional:${item.kind}:${item.title}:${index}`}>
                {item.summary}
              </small>
            ))}
          </article>
        )}
        <article>
          <span className="weekly-insight-section-icon"><Users size={18} /></span>
          <h3>Команда</h3>
          <p>{insight.content.teamInsights.summary.text}</p>
          {insight.content.teamInsights.highlights.map((item, index) => (
            <small key={`team:${item.kind}:${item.title}:${index}`}>
              {item.summary}
            </small>
          ))}
        </article>
      </div>

      {store.recommendedActions.length > 0 && (
        <section className="weekly-insight-focus weekly-insight-focus--list">
          <Lightbulb size={21} />
          <div>
            <span>Действия на неделю</span>
            <ul>{store.recommendedActions.map((action, index) => (
              <li key={`${action.type}:${action.title}:${index}`}>
                <strong>{action.title}</strong>
                <p>{action.summary}</p>
              </li>
            ))}</ul>
          </div>
        </section>
      )}

      <TeamExperience insight={insight.content.teamInsights} />

      {insight.content.employees.length > 0 && (
        <section className="weekly-insight-employees">
          <div className="weekly-insight-subheading">
            <div><span>Команда</span><h3>Интерпретация по сотрудникам</h3></div>
            <small>{insight.content.employees.length}</small>
          </div>
          {insight.content.employees.map((employee) => (
            <EmployeeInsight key={employee.employeeId} employee={employee} />
          ))}
        </section>
      )}

      {insight.content.dataLimitations.length > 0 && (
        <section className="weekly-insight-limitations">
          <TriangleAlert size={18} />
          <div><strong>Ограничения данных</strong>{insight.content.dataLimitations.map((item, index) => (
            <p key={`${item.code}:${index}`}>{item.summary}</p>
          ))}</div>
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
