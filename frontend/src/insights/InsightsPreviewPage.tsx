import { BrainCircuit, Sparkles } from "lucide-react";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { WeeklyInsightPanel } from "./WeeklyInsightPanel";
import "./styles.css";

export function InsightsPreviewPage() {
  const { selectedStore } = useWorkspace();
  return (
    <div className="insights-preview-page">
      <header className="page-heading insights-preview-heading">
        <div>
          <p className="eyebrow"><BrainCircuit size={16} /> {selectedStore.name}</p>
          <h1>ИИ-разбор</h1>
          <p>Интерпретация результатов магазина и сотрудников за последнюю завершённую неделю.</p>
        </div>
      </header>

      <section className="insights-page-intro" aria-label="Назначение ИИ-разбора">
        <Sparkles size={18} />
        <p>Выводы сформированы по рассчитанным backend-метрикам. Рекомендации помогают выбрать
          направление проверки, а окончательное решение остаётся за руководителем.</p>
      </section>

      <WeeklyInsightPanel storeId={selectedStore.id} />
    </div>
  );
}
