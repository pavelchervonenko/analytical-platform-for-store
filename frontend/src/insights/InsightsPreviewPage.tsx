import { BrainCircuit } from "lucide-react";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { WeeklyInsightView } from "./WeeklyInsightView";

export function InsightsPreviewPage() {
  const { selectedStore } = useWorkspace();
  return (
    <div className="insights-page">
      <header className="page-heading insights-preview-heading">
        <div>
          <p className="eyebrow"><BrainCircuit size={16} /> {selectedStore.name}</p>
          <h1>ИИ-разбор</h1>
          <p>Краткие выводы и действия по итогам последней завершённой недели.</p>
        </div>
      </header>
      <WeeklyInsightView storeId={selectedStore.id} />
    </div>
  );
}
