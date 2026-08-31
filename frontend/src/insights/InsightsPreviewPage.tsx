import { useWorkspace } from "../stores/WorkspaceProvider";
import { WeeklyInsightView } from "./WeeklyInsightView";
import { WeeklyReviewView } from "./WeeklyReviewView";

export function InsightsPreviewPage() {
  const { selectedStore } = useWorkspace();
  return (
    <div className="insights-page">
      <header className="page-heading insights-preview-heading">
        <div>
          <h1>ИИ-разбор</h1>
        </div>
      </header>
      <WeeklyReviewView
        storeId={selectedStore.id}
        fallback={<WeeklyInsightView storeId={selectedStore.id} />}
      />
    </div>
  );
}
