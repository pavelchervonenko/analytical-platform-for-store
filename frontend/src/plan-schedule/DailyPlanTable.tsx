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
      {projected && <i>с учётом темпа</i>}
    </td>
  );
}

export function DailyPlanTable({ targets }: { targets: PlanDailyTarget[] }) {
  if (targets.length === 0) return null;

  return (
    <section className="panel daily-plan-panel" aria-labelledby="daily-plan-title">
      <div className="panel__heading">
        <div>
          <p className="eyebrow">План по дням месяца</p>
          <h2 id="daily-plan-title">Аксессуары и услуги</h2>
          <p>Будущие проценты автоматически учитывают текущий темп, отставание или опережение.</p>
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
              <th>План</th>
              <th>Факт</th>
              <th>План</th>
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
                  <small>{target.completed ? "завершён" : "в плане"}</small>
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
    </section>
  );
}
