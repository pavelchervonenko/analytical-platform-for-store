import type { PlanDailyTarget } from "../api/contracts";
import { formatDateShort } from "../shared/date";
import { formatMoney, formatPercent } from "../shared/format";
import "./DailyPlanTable.css";

const VISIBLE_HISTORY_DAYS = 5;

const compactDateFormatter = new Intl.DateTimeFormat("ru-RU", {
  day: "numeric",
  month: "short",
  timeZone: "UTC"
});

function compactDate(date: string): string {
  return compactDateFormatter.format(new Date(`${date}T00:00:00Z`));
}

function gapClass(value: number | null): string {
  if (value == null) return "daily-plan-day__gap";
  return value >= 0
    ? "daily-plan-day__gap daily-plan-day__gap--positive"
    : "daily-plan-day__gap daily-plan-day__gap--negative";
}

function NearestDirection({
  label,
  direction
}: {
  label: string;
  direction: PlanDailyTarget["accessory"];
}) {
  return (
    <article className="daily-plan-nearest__direction">
      <span>{label}</span>
      <strong>{formatMoney(direction.targetAmount)}</strong>
      <p>
        <b>{formatPercent(direction.targetSharePercent)}</b> от расчётной выручки
      </p>
    </article>
  );
}

function HistoryDirection({
  label,
  direction
}: {
  label: string;
  direction: PlanDailyTarget["accessory"];
}) {
  return (
    <section className="daily-plan-day__direction" aria-label={label}>
      <h4>{label}</h4>
      <dl>
        <div>
          <dt>Факт</dt>
          <dd>
            <strong>{formatMoney(direction.actualAmount)}</strong>
            <span>{formatPercent(direction.actualSharePercent)}</span>
          </dd>
        </div>
        <div>
          <dt>Ориентир дня</dt>
          <dd>
            <strong>{formatMoney(direction.targetAmount)}</strong>
            <span>{formatPercent(direction.targetSharePercent)}</span>
          </dd>
        </div>
        <div>
          <dt>Отклонение с начала месяца</dt>
          <dd className={gapClass(direction.cumulativeGapAmount)}>
            {formatMoney(direction.cumulativeGapAmount)}
          </dd>
        </div>
      </dl>
    </section>
  );
}

function GapPreview({
  label,
  value,
  className
}: {
  label: string;
  value: number | null;
  className: string;
}) {
  return (
    <span className={`daily-plan-day__gap-preview ${className}`}>
      <small>{label}</small>
      <strong className={gapClass(value)}>{formatMoney(value)}</strong>
    </span>
  );
}

function HistoryDay({ target }: { target: PlanDailyTarget }) {
  return (
    <details className="daily-plan-day" data-date={target.date}>
      <summary>
        <span className="daily-plan-day__identity">
          <strong>
          <time dateTime={target.date}>{formatDateShort(target.date)}</time>
          </strong>
          <small>Завершённый день</small>
        </span>
        <span className="daily-plan-day__revenue">
          <small>Выручка</small>
          <strong>{formatMoney(target.revenueBasisAmount)}</strong>
        </span>
        <GapPreview
          label="Отклонение аксессуаров"
          value={target.accessory.cumulativeGapAmount}
          className="daily-plan-day__gap-preview--accessory"
        />
        <GapPreview
          label="Отклонение услуг"
          value={target.service.cumulativeGapAmount}
          className="daily-plan-day__gap-preview--service"
        />
        <span className="daily-plan-day__toggle" aria-hidden="true" />
      </summary>
      <div className="daily-plan-day__details">
        <HistoryDirection label="Аксессуары" direction={target.accessory} />
        <HistoryDirection label="Услуги" direction={target.service} />
      </div>
    </details>
  );
}

function FutureDay({ target }: { target: PlanDailyTarget }) {
  return (
    <article className="daily-plan-future-day" data-date={target.date}>
      <h4>
        <time dateTime={target.date}>{formatDateShort(target.date)}</time>
      </h4>
      <dl>
        <div>
          <dt>Аксессуары</dt>
          <dd>
            <strong>{formatMoney(target.accessory.targetAmount)}</strong>
            <span>{formatPercent(target.accessory.targetSharePercent)}</span>
          </dd>
        </div>
        <div>
          <dt>Услуги</dt>
          <dd>
            <strong>{formatMoney(target.service.targetAmount)}</strong>
            <span>{formatPercent(target.service.targetSharePercent)}</span>
          </dd>
        </div>
        <div>
          <dt>Расчётная выручка дня</dt>
          <dd>
            <strong>{formatMoney(target.revenueBasisAmount)}</strong>
          </dd>
        </div>
      </dl>
    </article>
  );
}

export function DailyPlanTable({ targets }: { targets: PlanDailyTarget[] }) {
  if (targets.length === 0) return null;

  const orderedTargets = [...targets].sort((left, right) => left.date.localeCompare(right.date));
  const completedTargets = orderedTargets.filter((target) => target.completed);
  const recentCompletedTargets = completedTargets.slice(-VISIBLE_HISTORY_DAYS);
  const earlierCompletedTargets = completedTargets.slice(0, -VISIBLE_HISTORY_DAYS);
  const futureTargets = orderedTargets.filter((target) => !target.completed);
  const nearestFutureTarget = futureTargets[0];
  const remainingFutureTargets = futureTargets.slice(1);
  const remainingFutureRange = remainingFutureTargets.length > 0
    ? `${compactDate(remainingFutureTargets.at(0)!.date)} — ${compactDate(remainingFutureTargets.at(-1)!.date)}`
    : null;

  return (
    <section className="panel daily-plan-view" aria-labelledby="daily-plan-title">
      <header className="daily-plan-view__heading">
        <h2 id="daily-plan-title">План по дням месяца</h2>
        <p>Ежедневные ориентиры для аксессуаров и услуг пересчитываются по результатам месяца.</p>
      </header>

      {nearestFutureTarget && (
        <section className="daily-plan-nearest" aria-labelledby="daily-plan-nearest-title">
          <header>
            <p id="daily-plan-nearest-title">Ориентир на ближайший день</p>
            <h3>
              <time dateTime={nearestFutureTarget.date}>{formatDateShort(nearestFutureTarget.date)}</time>
            </h3>
          </header>
          <div className="daily-plan-nearest__directions">
            <NearestDirection label="Аксессуары" direction={nearestFutureTarget.accessory} />
            <NearestDirection label="Услуги" direction={nearestFutureTarget.service} />
          </div>
          <div className="daily-plan-nearest__basis">
            <span>Расчётная выручка дня</span>
            <strong>{formatMoney(nearestFutureTarget.revenueBasisAmount)}</strong>
            <p>Расчёт сделан по результатам месяца и обновится после поступления новых данных.</p>
          </div>
        </section>
      )}

      {remainingFutureTargets.length > 0 && (
        <details className="daily-plan-disclosure daily-plan-disclosure--future">
          <summary>
            <span>
              <strong>Оставшиеся ориентиры</strong>
              <small>{remainingFutureRange}</small>
            </span>
            <span>{remainingFutureTargets.length} дн.</span>
          </summary>
          <div className="daily-plan-future-list">
            <p>Все будущие даты показаны отдельно, потому что сумма может отличаться из-за округления.</p>
            {remainingFutureTargets.map((target) => (
              <FutureDay target={target} key={target.date} />
            ))}
          </div>
        </details>
      )}

      <section className="daily-plan-history" aria-labelledby="daily-plan-history-title">
        <header>
          <div>
            <p>История по дням</p>
            <h3 id="daily-plan-history-title">Последние завершённые дни</h3>
          </div>
          {completedTargets.length > 0 && <span>{completedTargets.length} дн.</span>}
        </header>

        {completedTargets.length === 0 ? (
          <p className="daily-plan-history__empty">Завершённых дней пока нет.</p>
        ) : (
          <>
            <div className="daily-plan-history__list">
              {recentCompletedTargets.map((target) => (
                <HistoryDay target={target} key={target.date} />
              ))}
            </div>
            {earlierCompletedTargets.length > 0 && (
              <details className="daily-plan-disclosure daily-plan-disclosure--history">
                <summary>
                  <span>
                    <strong>Показать более ранние дни</strong>
                    <small>
                      {compactDate(earlierCompletedTargets.at(0)!.date)} — {compactDate(earlierCompletedTargets.at(-1)!.date)}
                    </small>
                  </span>
                  <span>{earlierCompletedTargets.length} дн.</span>
                </summary>
                <div className="daily-plan-history__list">
                  {earlierCompletedTargets.map((target) => (
                    <HistoryDay target={target} key={target.date} />
                  ))}
                </div>
              </details>
            )}
          </>
        )}
      </section>
    </section>
  );
}
