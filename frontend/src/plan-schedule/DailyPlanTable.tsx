import type { PlanDailyTarget } from "../api/contracts";
import { formatDateShort } from "../shared/date";
import { formatMoney, formatPercent } from "../shared/format";

interface DailyDirection {
  actualAmount: number | null;
  actualSharePercent: number | null;
  targetAmount: number;
  targetSharePercent: number | null;
  cumulativeGapAmount: number | null;
}

const compactDateFormatter = new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "short", timeZone: "UTC" });
function ActualCell({ direction, completed }: {
  direction: DailyDirection;
  completed: boolean;
}) {
  if (!completed) return <td className="daily-plan-table__empty">—</td>;
  return (
    <td>
      <strong>{formatPercent(direction.actualSharePercent)}</strong>
      <small>{formatMoney(direction.actualAmount)}</small>
      <i className={(direction.cumulativeGapAmount ?? 0) >= 0 ? "text-success" : "text-warning"}>
        итог {formatMoney(direction.cumulativeGapAmount)}
      </i>
    </td>
  );
}

function TargetCell({ direction, projected }: {
  direction: DailyDirection;
  projected: boolean;
}) {
  return (
    <td className={projected ? "daily-plan-table__target" : undefined}>
      <strong>{formatPercent(direction.targetSharePercent)}</strong>
      <small>{formatMoney(direction.targetAmount)}</small>
      <i>{projected ? "цель с учётом прогноза" : "ориентир от факта выручки"}</i>
    </td>
  );
}

function CompactDirection({ label, direction, completed, projected }: {
  label: string;
  direction: DailyDirection;
  completed: boolean;
  projected: boolean;
}) {
  return (
    <article className="daily-plan-compact__direction">
      <h3>{label}</h3>
      <dl>
        {completed && <div><dt>Факт</dt><dd><strong>{formatPercent(direction.actualSharePercent)}</strong><small>{formatMoney(direction.actualAmount)}</small></dd></div>}
        <div><dt>{projected ? "Цель будущего дня" : "Ориентир завершённого дня"}</dt><dd><strong>{formatPercent(direction.targetSharePercent)}</strong><small>{formatMoney(direction.targetAmount)}</small><i>{projected ? "с учётом прогноза" : "от фактической выручки"}</i></dd></div>
        {completed && <div><dt>Итог</dt><dd className={(direction.cumulativeGapAmount ?? 0) >= 0 ? "text-success" : "text-warning"}>{formatMoney(direction.cumulativeGapAmount)}</dd></div>}
      </dl>
    </article>
  );
}

function CompactDay({ target }: { target: PlanDailyTarget }) {
  return (
    <details className={`daily-plan-compact__day ${target.completed ? "" : "daily-plan-compact__future"}`}>
      <summary>
        <span><strong>{compactDateFormatter.format(new Date(`${target.date}T00:00:00Z`))}</strong><small>{target.completed ? "Завершён" : "Будущий день"}</small></span>
        <span><small>{target.revenueBasisProjected ? "Прогноз выручки" : "Выручка"}</small><strong>{formatMoney(target.revenueBasisAmount)}</strong></span>
      </summary>
      <div className="daily-plan-compact__details">
        <CompactDirection label="Аксессуары" direction={target.accessory} completed={target.completed} projected={!target.completed} />
        <CompactDirection label="Услуги" direction={target.service} completed={target.completed} projected={!target.completed} />
      </div>
    </details>
  );
}

export function DailyPlanTable({ targets }: { targets: PlanDailyTarget[] }) {
  if (targets.length === 0) return null;

  const completedTargets = targets.filter((target) => target.completed);
  const earlierCompletedTargets = completedTargets.slice(0, -3);
  const recentCompletedTargets = completedTargets.slice(-3);
  const futureTargets = targets.filter((target) => !target.completed);
  const nearFutureTargets = futureTargets.slice(0, 3);
  const laterFutureTargets = futureTargets.slice(3);
  return (
    <section className="panel daily-plan-panel" aria-labelledby="daily-plan-title">
      <div className="panel__heading">
        <div>
          <p className="eyebrow">План по дням месяца</p>
          <h2 id="daily-plan-title">Аксессуары и услуги</h2>
          <p>Завершённые дни показывают факт и ориентир от фактической выручки; будущие — пересчитанную цель.</p>
        </div>
        <span>{targets.length} дн.</span>
      </div>
      <div className="table-scroll">
        <table className="daily-plan-table">
          <thead>
            <tr>
              <th rowSpan={2}>День</th>
              <th rowSpan={2}>Выручка</th>
              <th colSpan={2}>Аксессуары</th>
              <th colSpan={2}>Услуги</th>
            </tr>
            <tr>
              <th>Факт</th>
              <th>Ориентир дня</th>
              <th>Факт</th>
              <th>Ориентир дня</th>
            </tr>
          </thead>
          <tbody>
            {targets.map((target) => (
              <tr
                className={target.completed ? undefined : "daily-plan-table__future"}
                key={target.date}
              >
                <th scope="row">
                  <strong>{formatDateShort(target.date)}</strong>
                  <small>{target.completed ? "завершён" : "будущий день"}</small>
                </th>
                <td>
                  <strong>{formatMoney(target.revenueBasisAmount)}</strong>
                  <small>{target.revenueBasisProjected ? "прогноз" : "факт"}</small>
                </td>
                <ActualCell direction={target.accessory} completed={target.completed} />
                <TargetCell direction={target.accessory} projected={!target.completed} />
                <ActualCell direction={target.service} completed={target.completed} />
                <TargetCell direction={target.service} projected={!target.completed} />
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="daily-plan-compact" aria-label="План по дням месяца">
        <p>Последние 3 завершённые и ближайшие 3 плановые даты показаны сразу. Остальные дни сгруппированы.</p>
        {earlierCompletedTargets.length > 0 && <details className="daily-plan-compact__group">
          <summary><span><strong>Ранее в месяце</strong><small>Завершённые дни</small></span><span><small>Дней</small><strong>{earlierCompletedTargets.length}</strong></span></summary>
          <div className="daily-plan-compact__group-content">{earlierCompletedTargets.map((target) => <CompactDay target={target} key={target.date} />)}</div>
        </details>}
        {recentCompletedTargets.map((target) => <CompactDay target={target} key={target.date} />)}
        {nearFutureTargets.map((target) => <CompactDay target={target} key={target.date} />)}
        {laterFutureTargets.length > 0 && <details className="daily-plan-compact__group daily-plan-compact__group--future">
          <summary><span><strong>Остаток месяца</strong><small>Пересчитанные цели будущих дней</small></span><span><small>Дней</small><strong>{laterFutureTargets.length}</strong></span></summary>
          <div className="daily-plan-compact__group-content">{laterFutureTargets.map((target) => <CompactDay target={target} key={target.date} />)}</div>
        </details>}
      </div>
    </section>
  );
}
