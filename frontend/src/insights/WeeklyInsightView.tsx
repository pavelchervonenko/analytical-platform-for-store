import { useQuery } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
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
  analysisStatusLabel,
  analysisStatusTone,
  employeeAnalysisHelp,
  insightKindHelp,
  insightKindTone,
  limitationSummary,
  readableInsightText,
  uniqueInsightSignals,
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
  candidateRef?: string | null;
  theme?: string;
};

type InsightAction =
  ReadyWeeklyInsight["content"]["store"]["recommendedActions"][number];

const MOBILE_EMPLOYEE_PREVIEW_COUNT = 3;

function formatEmployeeCount(count: number): string {
  const lastTwoDigits = count % 100;
  const lastDigit = count % 10;
  const noun = lastTwoDigits >= 11 && lastTwoDigits <= 14
    ? "сотрудников"
    : lastDigit === 1
      ? "сотрудник"
      : lastDigit >= 2 && lastDigit <= 4
        ? "сотрудника"
        : "сотрудников";
  return `${count} ${noun}`;
}

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
        <span>Последняя завершенная неделя</span>
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

function normalizedNarrative(value: string): string {
  return readableInsightText(value)
    .trim()
    .replace(/\s+/g, " ")
    .toLocaleLowerCase("ru-RU");
}

function repeatedEmployeeNarratives(
  employees: WeeklyInsightEmployee[]
): ReadonlySet<string> {
  const counts = new Map<string, number>();
  employees.forEach((employee) => {
    const narratives: Array<Narrative | null> = [
      employee.insight.headline,
      employee.insight.workloadContext,
      employee.insight.performanceSummary,
      employee.insight.dynamicsSummary
    ];
    new Set(
      narratives
        .filter((value): value is Narrative => value !== null)
        .map((value) => normalizedNarrative(value.text))
    ).forEach((value) => {
      counts.set(value, (counts.get(value) ?? 0) + 1);
    });
  });
  return new Set(
    Array.from(counts.entries())
      .filter(([, count]) => count > 1)
      .map(([value]) => value)
  );
}

function employeeSummaryFallback(status: string): string {
  if (status === "INSUFFICIENT") {
    return "Недостаточно данных для персонального анализа";
  }
  if (status === "LIMITED") {
    return "Разбор ограничен доступными показателями";
  }
  return "Показатели сотрудника за завершенную неделю";
}

function hasNonZeroValue(value: string): boolean {
  return /[1-9]/u.test(value);
}

function keyEvidenceComparison(item: WeeklyInsightEvidence): string | null {
  if (!item.previousFormattedValue) return null;
  if (!hasNonZeroValue(item.previousFormattedValue)) {
    return `Предыдущий период: ${readableInsightText(item.previousFormattedValue)}`;
  }
  const delta = item.relativeDeltaFormatted ?? item.absoluteDeltaFormatted;
  if (delta) return `Изменение ${readableInsightText(delta)}`;
  return item.comparisonText ?? null;
}

function employeeOverviewEvidenceRefs(
  employee: WeeklyInsightEmployee
): string[] {
  return Array.from(new Set([
    ...employee.insight.headline.evidenceRefs,
    ...(employee.insight.workloadContext?.evidenceRefs ?? []),
    ...(employee.insight.performanceSummary?.evidenceRefs ?? []),
    ...(employee.insight.dynamicsSummary?.evidenceRefs ?? [])
  ]));
}

function EvidenceDisclosure({
  evidenceRefs,
  evidenceByCode,
  caption = "Почему такой вывод"
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
        <span>{limited ? `${caption} — ограничены` : caption}</span>
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

function KeyEvidence({
  evidenceRefs,
  evidenceByCode
}: {
  evidenceRefs?: string[];
  evidenceByCode: EvidenceIndex;
}) {
  const evidence = evidenceFor(evidenceRefs, evidenceByCode)
    .filter((item) => item.available && item.formattedValue)
    .slice(0, 3);
  if (evidence.length === 0) {
    return (
      <EvidenceDisclosure
        evidenceRefs={evidenceRefs}
        evidenceByCode={evidenceByCode}
      />
    );
  }

  return (
    <div className="insight-key-evidence" aria-label="Ключевые показатели">
      {evidence.map((item) => (
        <article key={item.evidenceCode}>
          <span>{readableInsightText(item.label)}</span>
          <strong>{readableInsightText(item.formattedValue ?? "")}</strong>
          {keyEvidenceComparison(item) && (
            <small>
              {readableInsightText(keyEvidenceComparison(item) ?? "")}
            </small>
          )}
        </article>
      ))}
    </div>
  );
}

function NarrativeWithEvidence({
  value,
  evidenceByCode,
  className,
  evidenceCaption
}: {
  value: Narrative;
  evidenceByCode: EvidenceIndex;
  className?: string;
  evidenceCaption?: string;
}) {
  return (
    <div className={"insight-narrative" + (className ? " " + className : "")}>
      <p>{readableInsightText(value.text)}</p>
      <EvidenceDisclosure
        evidenceRefs={value.evidenceRefs}
        evidenceByCode={evidenceByCode}
        caption={evidenceCaption}
      />
    </div>
  );
}

function HypothesisHelp({ kind }: { kind?: string }) {
  const help = kind ? insightKindHelp(kind) : null;
  return help ? <small className="insight-hypothesis-help">{help}</small> : null;
}

function InsightSignal({
  label,
  values,
  tone = "neutral",
  evidenceByCode
}: {
  label: string;
  values: InsightItem[];
  tone?: "neutral" | "positive" | "warning";
  evidenceByCode: EvidenceIndex;
}) {
  if (values.length === 0) return null;

  const genericTitles = new Set([
    "сильные стороны",
    "что работает",
    "зона внимания",
    "что требует внимания",
    "главный риск"
  ]);

  return (
    <article className={"insight-signal insight-signal--" + tone}>
      <span className="insight-signal__label">{label}</span>
      <div>
        {values.map((value) => (
          <div
            className="insight-signal__item"
            key={(value.candidateRef ?? value.kind) + ":" + value.title}
          >
            {!genericTitles.has(normalizedNarrative(value.title)) && (
              <h3>{readableInsightText(value.title)}</h3>
            )}
            <p>{readableInsightText(value.summary)}</p>
            <HypothesisHelp kind={value.kind} />
            <EvidenceDisclosure
              evidenceRefs={value.evidenceRefs}
              evidenceByCode={evidenceByCode}
              caption={value.kind === "HYPOTHESIS" ? "Основание гипотезы" : undefined}
            />
          </div>
        ))}
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
        <article
          className={"insight-item insight-item--" + insightKindTone(item.kind)}
          key={item.kind + ":" + item.title + ":" + index}
        >
          <strong>{readableInsightText(item.title)}</strong>
          <p>{readableInsightText(item.summary)}</p>
          <HypothesisHelp kind={item.kind} />
          <EvidenceDisclosure
            evidenceRefs={item.evidenceRefs}
            evidenceByCode={evidenceByCode}
            caption={item.kind === "HYPOTHESIS" ? "Основание гипотезы" : undefined}
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
  eyebrow?: string;
  title: string;
  icon?: ReactNode;
  count?: ReactNode;
}) {
  return (
    <div className="insight-section-heading">
      {icon && <span className="insight-section-heading__icon">{icon}</span>}
      <div>
        {eyebrow && <span>{eyebrow}</span>}
        <h2>{title}</h2>
      </div>
      {count !== undefined && <small>{count}</small>}
    </div>
  );
}

function ActionList({
  actions,
  evidenceByCode,
  heading
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

  const horizons = Array.from(new Set(uniqueActions.map((action) => action.horizon)));
  const resolvedHeading = heading ?? (horizons.length === 1
    ? `Что сделать ${actionHorizonLabel(horizons[0] ?? "").toLocaleLowerCase("ru-RU")}`
    : "Что сделать в ближайший период");

  return (
    <section className="insight-actions" aria-label={resolvedHeading}>
      <SectionHeading
        title={resolvedHeading}
        icon={<Lightbulb aria-hidden="true" />}
      />
      <ol>
        {uniqueActions.map((action) => (
          <li key={action.type + ":" + action.title}>
            <h3>{readableInsightText(action.title)}</h3>
            <p>{readableInsightText(action.summary)}</p>
            <EvidenceDisclosure
              evidenceRefs={action.evidenceRefs}
              evidenceByCode={evidenceByCode}
              caption="Почему это действие"
            />
          </li>
        ))}
      </ol>
    </section>
  );
}

function StoreThemeSections({
  store,
  evidenceByCode
}: {
  store: WeeklyInsightStore;
  evidenceByCode: EvidenceIndex;
}) {
  if (!store.categoryPerformance && !store.additionalSalesPerformance) return null;

  return (
    <details className="insight-themes">
      <summary className="insight-themes__summary">
        <span>
          <strong>Категории и допродажи</strong>
          <small>Структура выручки, динамика и точки роста</small>
        </span>
        <ChevronDown aria-hidden="true" />
      </summary>
      <div className="insight-themes__body">
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
      </div>
    </details>
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

  const summaryEvidence = evidenceFor(insight.summary.evidenceRefs, evidenceByCode);
  const summaryIsOnlyLimitation = entries.length === 0
    && summaryEvidence.length > 0
    && summaryEvidence.every((item) => (
      !item.available
      || item.sufficiency === "LIMITED"
      || item.sufficiency === "INSUFFICIENT"
    ));
  if (summaryIsOnlyLimitation) return null;

  return (
    <section className="insight-team-experience" aria-label="Лидеры и обмен опытом">
      <SectionHeading title="Командные результаты" />
      <NarrativeWithEvidence
        value={insight.summary}
        evidenceByCode={evidenceByCode}
        className="insight-team-experience__summary"
      />
      {entries.length > 0 && (
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
      )}
    </section>
  );
}

function EmployeeInsight({
  employee,
  evidenceByCode,
  repeatedNarratives,
  showAnalysisStatus
}: {
  employee: WeeklyInsightEmployee;
  evidenceByCode: EvidenceIndex;
  repeatedNarratives: ReadonlySet<string>;
  showAnalysisStatus: boolean;
}) {
  const insight = employee.insight;
  const status = employee.analysisStatus || insight.analysisStatus;
  const tone = analysisStatusTone(status);
  const headlineRepeated = repeatedNarratives.has(
    normalizedNarrative(insight.headline.text)
  );
  const overviewEvidenceRefs = employeeOverviewEvidenceRefs(employee);
  const limitations = uniqueNarratives(
    insight.dataLimitations,
    (item) => item.summary
  );
  const focusSignals = uniqueInsightSignals([
    insight.primaryRisk,
    insight.attentionArea
  ]).slice(0, 2);
  const limitationEvidenceRefs = Array.from(new Set([
    ...overviewEvidenceRefs,
    ...limitations.flatMap((item) => item.evidenceRefs)
  ]));
  const limitationText = limitations[0]
    ? limitationSummary(limitations[0])
    : employeeAnalysisHelp(status);
  const insufficient = status === "INSUFFICIENT";

  return (
    <details className="insight-employee">
      <summary>
        <span className="insight-employee__avatar" aria-hidden="true">
          {employee.displayName.trim().slice(0, 1).toLocaleUpperCase("ru-RU")}
        </span>
        <span className="insight-employee__summary">
          <strong>{readableInsightText(employee.displayName)}</strong>
          <small>{status !== "SUFFICIENT"
            ? employeeSummaryFallback(status)
            : headlineRepeated
              ? employeeSummaryFallback(status)
              : readableInsightText(insight.headline.text)}</small>
        </span>
        {showAnalysisStatus && status !== "SUFFICIENT" && (
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
              <strong>Почему разбор ограничен</strong>
              <p>{readableInsightText(limitationText)}</p>
              <EvidenceDisclosure
                evidenceRefs={limitationEvidenceRefs}
                evidenceByCode={evidenceByCode}
                caption="Проверить данные"
              />
            </div>
          </div>
        )}

        {!insufficient && (
          <EvidenceDisclosure
            evidenceRefs={overviewEvidenceRefs}
            evidenceByCode={evidenceByCode}
          />
        )}

        {!insufficient && <div className="insight-employee__narratives">
          {insight.workloadContext
            && !repeatedNarratives.has(
              normalizedNarrative(insight.workloadContext.text)
            ) && (
            <section>
              <h3>Нагрузка</h3>
              <p>{readableInsightText(insight.workloadContext.text)}</p>
            </section>
          )}
          {insight.performanceSummary
            && !repeatedNarratives.has(
              normalizedNarrative(insight.performanceSummary.text)
            ) && (
            <section>
              <h3>Результат</h3>
              <p>{readableInsightText(insight.performanceSummary.text)}</p>
            </section>
          )}
          {insight.dynamicsSummary
            && !repeatedNarratives.has(
              normalizedNarrative(insight.dynamicsSummary.text)
            ) && (
            <section>
              <h3>Динамика</h3>
              <p>{readableInsightText(insight.dynamicsSummary.text)}</p>
            </section>
          )}
        </div>}

        {!insufficient && <div className="insight-employee__signals">
          <InsightSignal
            label="Что работает"
            values={insight.strength ? [insight.strength] : []}
            tone="positive"
            evidenceByCode={evidenceByCode}
          />
          <InsightSignal
            label="Что требует внимания"
            values={focusSignals}
            tone="warning"
            evidenceByCode={evidenceByCode}
          />
        </div>}

        {!insufficient && (insight.categoryPerformance || insight.additionalSalesPerformance) && (
          <div className="insight-employee__details">
            {insight.categoryPerformance && (
              <section>
                <h3>Категории продаж</h3>
                {insight.categoryPerformance.summary && (
                  <NarrativeWithEvidence
                    value={insight.categoryPerformance.summary}
                    evidenceByCode={evidenceByCode}
                    evidenceCaption="Показатели категорий"
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
                    evidenceCaption="Показатели дополнительных продаж"
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

        {!insufficient && (
          <ActionList
            actions={insight.recommendedActions}
            evidenceByCode={evidenceByCode}
          />
        )}

        {status === "SUFFICIENT" && limitations.length > 0 && (
          <section className="insight-employee-limitations">
            <h3>Ограничения данных</h3>
            {limitations.map((limitation) => (
              <div key={employee.employeeId + ":" + limitation.code}>
                <p>{readableInsightText(limitationSummary(limitation))}</p>
                <EvidenceDisclosure
                  evidenceRefs={limitation.evidenceRefs}
                  evidenceByCode={evidenceByCode}
                  caption="Источник ограничения"
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
  const focusSignals = uniqueInsightSignals([
    store.primaryRisk,
    store.attentionArea
  ]).slice(0, 2);

  return (
    <section className="insight-summary" aria-label="Главное за неделю">
      <div className="insight-summary__hero">
        <span className="insight-summary__eyebrow">Итоги недели</span>
        <h2>{readableInsightText(store.headline.text)}</h2>
        <KeyEvidence
          evidenceRefs={store.headline.evidenceRefs}
          evidenceByCode={evidenceByCode}
        />
      </div>

      {summaries.length > 0 && (
        <details className="insight-summary__context-disclosure">
          <summary>
            <span>Контекст недели</span>
            <ChevronDown aria-hidden="true" />
          </summary>
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
        </details>
      )}

      <div className="insight-summary__signals">
        <InsightSignal
          label="Что работает"
          values={store.strength ? [store.strength] : []}
          tone="positive"
          evidenceByCode={evidenceByCode}
        />
        <InsightSignal
          label="Что требует внимания"
          values={focusSignals}
          tone="warning"
          evidenceByCode={evidenceByCode}
        />
      </div>
    </section>
  );
}

function ReadyInsight({ insight }: { insight: ReadyWeeklyInsight }) {
  const store = insight.content.store;
  const employees = insight.content.employees;
  const repeatedNarratives = repeatedEmployeeNarratives(employees);
  const [showAllEmployees, setShowAllEmployees] = useState(false);
  const remainingEmployees = employees.slice(MOBILE_EMPLOYEE_PREVIEW_COUNT);
  const limitedEmployeeCount = employees.filter((employee) => (
    (employee.analysisStatus || employee.insight.analysisStatus) !== "SUFFICIENT"
  )).length;
  const showEmployeeStatuses = limitedEmployeeCount > 0
    && limitedEmployeeCount !== employees.length;
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
        evidenceByCode={evidenceByCode}
      />
      <TeamExperience
        insight={insight.content.teamInsights}
        evidenceByCode={evidenceByCode}
      />

      {employees.length > 0 && (
        <section className="insight-employees" aria-label="Разбор по сотрудникам">
          <SectionHeading
            title="Сотрудники"
            count={formatEmployeeCount(employees.length)}
          />
          <p className="insight-employees__intro">
            Откройте сотрудника, чтобы посмотреть показатели, выводы и персональные действия.
          </p>
          {limitedEmployeeCount > 0 && (
            <p className="insight-employees__status-summary">
              Для {limitedEmployeeCount} из {employees.length} сотрудников пока недостаточно данных для надежного персонального разбора.
            </p>
          )}
          <div className="insight-employees__list">
            {employees.map((employee, index) => (
              <div
                key={employee.employeeId}
                className={
                  "insight-employees__item"
                  + (index >= MOBILE_EMPLOYEE_PREVIEW_COUNT && !showAllEmployees
                    ? " insight-employees__item--mobile-hidden"
                    : "")
                }
              >
                <EmployeeInsight
                  employee={employee}
                  evidenceByCode={evidenceByCode}
                  repeatedNarratives={repeatedNarratives}
                  showAnalysisStatus={showEmployeeStatuses}
                />
              </div>
            ))}
          </div>
          {remainingEmployees.length > 0 && (
            <button
              className={
                "insight-employees__more"
                + (showAllEmployees ? " insight-employees__more--open" : "")
              }
              type="button"
              aria-expanded={showAllEmployees}
              onClick={() => setShowAllEmployees((current) => !current)}
            >
              <span>
                {showAllEmployees
                  ? "Скрыть остальных"
                  : `Еще ${formatEmployeeCount(remainingEmployees.length)}`}
              </span>
              <ChevronDown aria-hidden="true" />
            </button>
          )}
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
