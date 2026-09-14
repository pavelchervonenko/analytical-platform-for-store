import { useAuth } from "../auth/AuthProvider";
import { useWorkspace } from "../stores/WorkspaceProvider";
import { WeeklyInsightView } from "./WeeklyInsightView";
import { WeeklyReviewView } from "./WeeklyReviewView";

export function InsightsPreviewPage() {
  const { user } = useAuth();
  const { selectedStore } = useWorkspace();
  const qualityHref = user?.role === "ADMIN"
    ? `/quality?store=${encodeURIComponent(selectedStore.id)}`
    : null;
  return (
    <div className="insights-page">
      <header className="page-heading insights-preview-heading">
        <div>
          <h1>ИИ-разбор</h1>
        </div>
      </header>
      <WeeklyReviewView
        storeId={selectedStore.id}
        qualityHref={qualityHref}
        fallback={<WeeklyInsightView storeId={selectedStore.id} />}
      />
    </div>
  );
}
