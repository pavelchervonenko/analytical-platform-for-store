import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  Clock3,
  Lightbulb,
  RefreshCw
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
  readableInsightText,
  uniqueNarratives
} from "./presentation";
import "./weekly-insight-redesign.css";

type ReadyWeeklyInsight = WeeklyInsight & {
  content: NonNullable<WeeklyInsight["content"]>;
};

type EvidenceIndex = ReadonlyMap<string, WeeklyInsightEvidence>;

type Narrative = {
  text: string;
  evidenceRefs?: string[];
};

type InsightItem = {
  kind: string;
  title: string;
  summary: string;
  evidenceRefs: string[];
};

type InsightAction =
  ReadyWeeklyInsight["content"]["store"]["recommendedActions"][number];

function refetchInterval(insight: WeeklyInsight | undefined): number | false {
  if (!insight) return false;
  if (insight.state === "PREPARING" || insight.state === "DELAYED") return 15_000;
  if (insight.state === "READY" && insight.revisionState === "UPDATING") return 15_000;
  return false;
}

function InsightStatus({ insight }: { insight: WeeklyInsight }) {
  const delayed = insight.state === "DELAYED"
    || insight.revisionState === "UPDATE_DELAYED";
  const ready = insight.state === "READY" && !delayed;
  const className = ready
    ? "insight-status insight-status--ready"
    : delayed
      ? "insight-status insight-status--warning"
      : "insight-status";

  return (
    <span className={className} role="status">
      {ready
        ? <CheckCircle2 aria-hidden="true" />
        : delayed
          ? <AlertTriangle aria-hidden="true" />
          : <RefreshCw aria-hidden="true" />}
      {insight.state === "READY"
        ? insight.revisionState === "CURRENT" ? "Актуально" : "Обновляется"
        : insight.state === "DELAYED" ? "Есть задержка" : "Готовится"}
    </span>
  );
}

function InsightMeta({ insight }: { insight: WeeklyInsight }) {
  return (
    <div className="insight-meta" aria-label="Период и состояние разбора">
      <div>
        <strong>
          {formatDate(insight.period.periodStart)} — {formatDate(insight.period.periodEnd)}
        </strong>
        <span>Последняя завершённая неделя</span>
      </div>
      <div className="insight-meta__details">
        {insight.publishedAt && (
          <span>Опубликовано {formatDate(insight.publishedAt.slice(0, 10))}</span>
        )}
        {insight.revision && <span>Ревизия {insight.revision}</span>}
        <InsightStatus insight={insight} />
      </div>
    </div>
  );
}

function evidenceFor(
  evidenceRefs: string[] | undefined,
  evidenceByCode: EvidenceIndex
): WeeklyInsightEvidence[] {
  return Array.from(new Set(evidenceRefs ?? []))
    .map((code) => evidenceByCode.get(code))
    .filter((value): value is WeeklyInsightEvidence => value !== undefined);
}

function EvidenceDisclosure({
  evidenceRefs,
  evidenceByCode,
  caption = "Показать подтверждающие данные"
}: {
  evidenceRefs?: string[];
  evidenceByCode: EvidenceIndex;
  caption?: string;
}) {
  const evidence = evidenceFor(evidenceRefs, evidenceByCode);
  if (evidence.length === 0) return null;

  const limited = evidence.some(
    (item) => !item.available
      || item.sufficiency === "LIMITED"
      || item.sufficiency === "INSUFFICIENT"
  );

  return (
    <details className={"insight-evidence" + (limited ? " insight-evidence--limited" : "")}>
      <summary>
        <span>{limited ? "Данные с ограничениями" : caption}</span>
        <small>{evidence.length}</small>
        <ChevronDown aria-hidden="true" />
      </summary>
      <div className="insight-evidence__list">
        {evidence.map((item) => (
          <div className="insight-evidence__item" key={item.evidenceCode}>
            <span>{readableInsightText(item.label)}</span>
            <strong>
              {readableInsightText(item.formattedValue ?? "Значение недоступно")}
            </strong>
            {item.comparisonText && (
              <small>{readableInsightText(item.comparisonText)}</small>
            )}
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
    </details>
  );
}

function NarrativeWithEvidence({
  value,
  evidenceByCode,
  className
}: {
  value: Narrative;
  evidenceByCode: EvidenceIndex;
  className?: string;
}) {
  return (
    <div className={"insight-narrative" + (className ? " " + className : "")}>
      <p>{readableInsightText(value.text)}</p>
      <EvidenceDisclosure
        evidenceRefs={value.evidenceRefs}
        evidenceByCode={evidenceByCode}
      />
    </div>
  );
}

function InsightKindBadge({ kind }: { kind?: string }) {
  if (!kind) return null;
  return (
    <span className={"insight-kind insight-kind--" + insightKindTone(kind)}>
      {insightKindLabel(kind)}
    </span>
  );
}

function HypothesisHelp({ kind }: { kind?: string }) {
  const help = kind ? insightKindHelp(kind) : null;
  return help ? <small className="insight-hypothesis-help">{help}</small> : null;
}

function InsightSignal({
  label,
  value,
  tone = "neutral",
  evidenceByCode
}: {
  label: string;
  value: InsightItem | null;
  tone?: "neutral" | "positive" | "warning";
  evidenceByCode: EvidenceIndex;
}) {
  if (!value) return null;

  return (
    <article className={"insight-signal insight-signal--" + tone}>
      <span className="insight-signal__label">{label}</span>
      <div>
        <InsightKindBadge kind={value.kind} />
        <h3>{readableInsightText(value.title)}</h3>
        <p>{readableInsightText(value.summary)}</p>
        <HypothesisHelp kind={value.kind} />
        <EvidenceDisclosure
          evidenceRefs={value.evidenceRefs}
          evidenceByCode={evidenceByCode}
          caption={value.kind === "HYPOTHESIS"
            ? "Показать основание гипотезы"
            : undefined}
        />
      </div>
    </article>
  );
}

function InsightItemList({
  label,
  items,
  evidenceByCode
}: {
  label: string;
  items: InsightItem[];
  evidenceByCode: EvidenceIndex;
}) {
  const uniqueItems = uniqueNarratives(
    items,
    (item) => item.title + "\n" + item.summary
  );
  if (uniqueItems.length === 0) return null;

  return (
    <section className="insight-item-group">
      <h4>{label}</h4>
      {uniqueItems.map((item, index) => (
        <article key={item.kind + ":" + item.title + ":" + index}>
          <InsightKindBadge kind={item.kind} />
          <strong>{readableInsightText(item.title)}</strong>
          <p>{readableInsightText(item.summary)}</p>
          <HypothesisHelp kind={item.kind} />
          <EvidenceDisclosure
            evidenceRefs={item.evidenceRefs}
            evidenceByCode={evidenceByCode}
            caption={item.kind === "HYPOTHESIS"
              ? "Показать основание гипотезы"
              : undefined}
          />
        </article>
      ))}
    </section>
  );
}

function SectionHeading({
  eyebrow,
  title,
  icon,
  count
}: {
  eyebrow: string;
  title: string;
  icon?: ReactNode;
  count?: number;
}) {
  return (
    <div className="insight-section-heading">
      {icon && <span className="insight-section-heading__icon">{icon}</span>}
      <div>
        <span>{eyebrow}</span>
        <h2>{title}</h2>
      </div>
      {count !== undefined && <small>{count}</small>}
    </div>
  );
}

function ActionList({
  actions,
  evidenceByCode,
  heading = "Что сделать на следующей неделе"
}: {
  actions: InsightAction[];
  evidenceByCode: EvidenceIndex;
  heading?: string;
}) {
  const uniqueActions = uniqueNarratives(
    actions,
    (action) => action.title + "\n" + action.summary
  );
  if (uniqueActions.length === 0) return null;

  return (
    <section className="insight-actions" aria-label={heading}>
      <SectionHeading
        eyebrow="Приоритеты руководителя"
        title={heading}
        icon={<Lightbulb aria-hidden="true" />}
      />
      <ol>
        {uniqueActions.map((action) => (
          <li key={action.type + ":" + action.title}>
            <div className="insight-actions__meta">
              <span>{actionTypeLabel(action.type)}</span>
              <span>{actionHorizonLabel(action.horizon)}</span>
            </div>
            <h3>{readableInsightText(action.title)}</h3>
            <p>{readableInsightText(action.summary)}</p>
            <EvidenceDisclosure
              evidenceRefs={action.evidenceRefs}
              evidenceByCode={evidenceByCode}
              caption="Показать основание рекомендации"
            />
          </li>
        ))}
      </ol>
    </section>
  );
}

function StoreThemeSections({
  store,
  teamInsights,
  evidenceByCode
}: {
  store: WeeklyInsightStore;
  teamInsights: ReadyWeeklyInsight["content"]["teamInsights"];
  evidenceByCode: EvidenceIndex;
}) {
  return (
    <section className="insight-themes" aria-label="Разбор результатов магазина">
      <SectionHeading eyebrow="Подробности" title="Разбор результатов магазина" />

      {store.categoryPerformance && (
        <article className="insight-theme">
          <header><span>01</span><h3>Категории продаж</h3></header>
          {store.categoryPerformance.summary && (
            <NarrativeWithEvidence
              value={store.categoryPerformance.summary}
              evidenceByCode={evidenceByCode}
              className="insight-theme__summary"
            />
          )}
          <div className="insight-theme__groups">
            <InsightItemList
              label="Драйверы роста"
              items={store.categoryPerformance.growthDrivers}
              evidenceByCode={evidenceByCode}
            />
            <InsightItemList
              label="Снижение"
              items={store.categoryPerformance.declineDrivers}
              evidenceByCode={evidenceByCode}
            />
            <InsightItemList
              label="Структура продаж"
              items={store.categoryPerformance.mixInsights}
              evidenceByCode={evidenceByCode}
            />
          </div>
        </article>
      )}

      {store.additionalSalesPerformance && (
        <article className="insight-theme">
          <header><span>02</span><h3>Дополнительные продажи</h3></header>
          {store.additionalSalesPerformance.summary && (
            <NarrativeWithEvidence
              value={store.additionalSalesPerformance.summary}
              evidenceByCode={evidenceByCode}
              className="insight-theme__summary"
            />
          )}
          <div className="insight-theme__groups">
            <InsightItemList
              label="Выручка"
              items={store.additionalSalesPerformance.revenueInsights}
              evidenceByCode={evidenceByCode}
            />
            <InsightItemList
              label="Attach-rate, допы на 100 единиц техники"
              items={store.additionalSalesPerformance.attachRateInsights}
              evidenceByCode={evidenceByCode}
            />
            <InsightItemList
              label="Возможности роста"
              items={store.additionalSalesPerformance.opportunities}
              evidenceByCode={evidenceByCode}
            />
          </div>
        </article>
      )}

      <article className="insight-theme">
        <header><span>03</span><h3>Команда</h3></header>
        <NarrativeWithEvidence
          value={teamInsights.summary}
          evidenceByCode={evidenceByCode}
          className="insight-theme__summary"
        />
        <InsightItemList
          label="Главное по команде"
          items={teamInsights.highlights}
          evidenceByCode={evidenceByCode}
        />
      </article>
    </section>
  );
}

function TeamExperience({
  insight,
  evidenceByCode
}: {
  insight: ReadyWeeklyInsight["content"]["teamInsights"];
  evidenceByCode: EvidenceIndex;
}) {
  const entries = [
    ...insight.competencyLeaders.map((item, index) => ({
      key: "leader:" + index,
      label: "Сильная практика",
      title: item.employeeNames.join(", ") || "Лидер команды",
      summary: item.summary,
      evidenceRefs: item.evidenceRefs
    })),
    ...insight.mostImproved.map((item, index) => ({
      key: "improved:" + item.employeeRef + ":" + index,
      label: "Заметная динамика",
      title: item.displayName ?? "Сотрудник команды",
      summary: item.summary,
      evidenceRefs: item.evidenceRefs
    })),
    ...insight.learningOpportunities.map((item, index) => ({
      key: "learning:" + index,
      label: "Обмен опытом",
      title: item.mentorNames.length > 0
        ? item.mentorNames.join(", ") + " → " + item.targetNames.join(", ")
        : "Обмен опытом внутри команды",
      summary: item.summary,
      evidenceRefs: item.evidenceRefs
    }))
  ];
  if (entries.length === 0) return null;

  return (
    <section className="insight-team-experience" aria-label="Лидеры и обмен опытом">
      <SectionHeading eyebrow="Команда" title="Лидеры и обмен опытом" />
      <div className="insight-team-experience__list">
        {entries.map((entry) => (
          <article key={entry.key}>
            <span>{entry.label}</span>
            <div>
              <h3>{readableInsightText(entry.title)}</h3>
              <p>{readableInsightText(entry.summary)}</p>
              <EvidenceDisclosure
                evidenceRefs={entry.evidenceRefs}
                evidenceByCode={evidenceByCode}
              />
            </div>
          </article>
        ))}
      </div>
    </section>
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
    <details className="insight-employee">
      <summary>
        <span className="insight-employee__avatar" aria-hidden="true">
          {employee.displayName.trim().slice(0, 1).toLocaleUpperCase("ru-RU")}
        </span>
        <span className="insight-employee__summary">
          <strong>{readableInsightText(employee.displayName)}</strong>
          <small>{readableInsightText(insight.headline.text)}</small>
        </span>
        {status !== "SUFFICIENT" && (
          <span className={"insight-employee__status insight-employee__status--" + tone}>
            {analysisStatusLabel(status)}
          </span>
        )}
        <ChevronDown className="insight-employee__chevron" aria-hidden="true" />
      </summary>
      <div className="insight-employee__body">
        {status !== "SUFFICIENT" && (
          <div className="insight-employee__notice">
            <AlertTriangle aria-hidden="true" />
            <div>
              <strong>Ограничения персонального разбора</strong>
              <p>{employeeAnalysisHelp(status)}</p>
            </div>
          </div>
        )}

        <EvidenceDisclosure
          evidenceRefs={insight.headline.evidenceRefs}
          evidenceByCode={evidenceByCode}
        />

        <div className="insight-employee__narratives">
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
        </div>

        <div className="insight-employee__signals">
          <InsightSignal
            label="Сильная сторона"
            value={insight.strength}
            tone="positive"
            evidenceByCode={evidenceByCode}
          />
          <InsightSignal
            label="Зона внимания"
            value={insight.attentionArea}
            evidenceByCode={evidenceByCode}
          />
          <InsightSignal
            label="Риск"
            value={insight.primaryRisk}
            tone="warning"
            evidenceByCode={evidenceByCode}
          />
        </div>

        {(insight.categoryPerformance || insight.additionalSalesPerformance) && (
          <div className="insight-employee__details">
            {insight.categoryPerformance && (
              <section>
                <h3>Категории продаж</h3>
                {insight.categoryPerformance.summary && (
                  <NarrativeWithEvidence
                    value={insight.categoryPerformance.summary}
                    evidenceByCode={evidenceByCode}
                  />
                )}
                <InsightItemList
                  label="Сильные категории"
                  items={insight.categoryPerformance.strengths}
                  evidenceByCode={evidenceByCode}
                />
                <InsightItemList
                  label="Зоны внимания"
                  items={insight.categoryPerformance.attentionAreas}
                  evidenceByCode={evidenceByCode}
                />
                <InsightItemList
                  label="Динамика"
                  items={insight.categoryPerformance.dynamics}
                  evidenceByCode={evidenceByCode}
                />
              </section>
            )}
            {insight.additionalSalesPerformance && (
              <section>
                <h3>Дополнительные продажи</h3>
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
                  label="Attach-rate, допы на 100 единиц техники"
                  items={insight.additionalSalesPerformance.attachRateInsights}
                  evidenceByCode={evidenceByCode}
                />
                <InsightItemList
                  label="Возможности роста"
                  items={insight.additionalSalesPerformance.opportunities}
                  evidenceByCode={evidenceByCode}
                />
              </section>
            )}
          </div>
        )}

        <ActionList
          actions={insight.recommendedActions}
          evidenceByCode={evidenceByCode}
          heading="Персональные действия"
        />

        {limitations.length > 0 && (
          <section className="insight-employee-limitations">
            <h3>Ограничения данных</h3>
            {limitations.map((limitation) => (
              <div key={employee.employeeId + ":" + limitation.code}>
                <p>{readableInsightText(limitationSummary(limitation))}</p>
                <EvidenceDisclosure
                  evidenceRefs={limitation.evidenceRefs}
                  evidenceByCode={evidenceByCode}
                />
              </div>
            ))}
          </section>
        )}
      </div>
    </details>
  );
}

function ExecutiveSummary({
  store,
  evidenceByCode
}: {
  store: WeeklyInsightStore;
  evidenceByCode: EvidenceIndex;
}) {
  const summaries = [
    store.resultSummary && { label: "Результат", value: store.resultSummary },
    store.dynamicsSummary && { label: "Динамика", value: store.dynamicsSummary },
    store.planOutlook && { label: "План", value: store.planOutlook }
  ].filter((item): item is NonNullable<typeof item> => Boolean(item));

  return (
    <section className="insight-summary" aria-label="Главное за неделю">
      <span className="insight-summary__eyebrow">Главное за неделю</span>
      <h2>{readableInsightText(store.headline.text)}</h2>
      <EvidenceDisclosure
        evidenceRefs={store.headline.evidenceRefs}
        evidenceByCode={evidenceByCode}
      />

      {summaries.length > 0 && (
        <div className="insight-summary__context">
          {summaries.map((item) => (
            <article key={item.label}>
              <span>{item.label}</span>
              <NarrativeWithEvidence
                value={item.value}
                evidenceByCode={evidenceByCode}
              />
            </article>
          ))}
        </div>
      )}

      <div className="insight-summary__signals">
        <InsightSignal
          label="Сильная сторона"
          value={store.strength}
          tone="positive"
          evidenceByCode={evidenceByCode}
        />
        <InsightSignal
          label="Зона внимания"
          value={store.attentionArea}
          evidenceByCode={evidenceByCode}
        />
        <InsightSignal
          label="Главный риск"
          value={store.primaryRisk}
          tone="warning"
          evidenceByCode={evidenceByCode}
        />
      </div>
    </section>
  );
}

function ReadyInsight({ insight }: { insight: ReadyWeeklyInsight }) {
  const store = insight.content.store;
  const evidenceByCode: EvidenceIndex = new Map(
    insight.content.evidence.map((item) => [item.evidenceCode, item])
  );
  const limitations = uniqueNarratives(
    insight.content.dataLimitations,
    (item) => item.summary
  );

  return (
    <>
      <InsightMeta insight={insight} />
      <ExecutiveSummary store={store} evidenceByCode={evidenceByCode} />
      <ActionList
        actions={store.recommendedActions}
        evidenceByCode={evidenceByCode}
      />
      <StoreThemeSections
        store={store}
        teamInsights={insight.content.teamInsights}
        evidenceByCode={evidenceByCode}
      />
      <TeamExperience
        insight={insight.content.teamInsights}
        evidenceByCode={evidenceByCode}
      />

      {insight.content.employees.length > 0 && (
        <section className="insight-employees" aria-label="Разбор по сотрудникам">
          <SectionHeading
            eyebrow="Команда"
            title="Разбор по сотрудникам"
            count={insight.content.employees.length}
          />
          <p className="insight-employees__intro">
            Откройте сотрудника, чтобы посмотреть показатели, выводы и персональные действия.
          </p>
          <div className="insight-employees__list">
            {insight.content.employees.map((employee) => (
              <EmployeeInsight
                key={employee.employeeId}
                employee={employee}
                evidenceByCode={evidenceByCode}
              />
            ))}
          </div>
        </section>
      )}

      {limitations.length > 0 && (
        <section className="insight-limitations" aria-label="Ограничения данных">
          <AlertTriangle aria-hidden="true" />
          <div>
            <h2>Ограничения данных</h2>
            {limitations.map((item) => (
              <article key={item.code + ":" + item.scope + ":" + (item.employeeRef ?? "store")}>
                <p>{readableInsightText(item.summary)}</p>
                <EvidenceDisclosure
                  evidenceRefs={item.evidenceRefs}
                  evidenceByCode={evidenceByCode}
                />
              </article>
            ))}
          </div>
        </section>
      )}
    </>
  );
}

function AvailabilityInsight({ insight }: { insight: WeeklyInsight }) {
  return (
    <>
      <InsightMeta insight={insight} />
      <div className="insight-availability" aria-live="polite">
        <span>
          {insight.state === "UNAVAILABLE" ? <AlertTriangle /> : <Clock3 />}
        </span>
        <div>
          <h2>
            {readableInsightText(
              insight.fallback?.title ?? "Интерпретация недели"
            )}
          </h2>
          <p>
            {readableInsightText(insight.fallback?.summary ?? insight.message)}
          </p>
          {insight.fallback && insight.fallback.dataLimitationCodes.length > 0 && (
            <small>
              Есть ограничения качества данных. Подробности доступны в разделе качества.
            </small>
          )}
        </div>
      </div>
    </>
  );
}

export function WeeklyInsightView({ storeId }: { storeId: string }) {
  const query = useQuery({
    queryKey: queryKeys.weeklyInsight(storeId),
    queryFn: () => getWeeklyInsight(storeId),
    staleTime: 15_000,
    refetchInterval: (state) => refetchInterval(state.state.data),
    refetchOnWindowFocus: true
  });

  return (
    <section className="insight-view" aria-label="Интерпретация результатов недели">
      {query.isPending && (
        <div className="insight-query-state"><PanelSkeleton rows={5} /></div>
      )}
      {query.isError && (
        <div className="insight-query-state">
          <QueryError
            error={query.error}
            onRetry={() => void query.refetch()}
            compact
          />
        </div>
      )}
      {query.data?.state === "READY" && query.data.content
        ? <ReadyInsight insight={{ ...query.data, content: query.data.content }} />
        : query.data && <AvailabilityInsight insight={query.data} />}
    </section>
  );
}
