import { useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import type { ReactNode } from "react";
import { getWeeklyReview, queryKeys } from "../api/queries";
import type { WeeklyReview } from "../api/weeklyReviewContract";
import { PanelSkeleton, QueryError, StaleDataNote } from "../shared/QueryState";
import { WeeklyReviewContent } from "./weekly-review/WeeklyReviewContent";
import "./weekly-review/weekly-review.css";

function refetchInterval(review: WeeklyReview | null | undefined): number | false {
  if (!review) return false;
  if (review.reportState === "PREPARING") return 15_000;
  if (review.aiEnhancement.state === "PREPARING"
      || review.aiEnhancement.state === "DELAYED") {
    return 15_000;
  }
  return false;
}

function EmptyReview({ onRetry }: { onRetry: () => void }) {
  return (
    <section className="weekly-review weekly-review-empty" aria-live="polite">
      <RefreshCw aria-hidden="true" />
      <h2>Разбор ещё не сформирован</h2>
      <p>Он появится после расчёта последней завершённой недели.</p>
      <button type="button" onClick={onRetry}>Проверить снова</button>
    </section>
  );
}

function LegacyReviewFallback({ children }: { children: ReactNode }) {
  return (
    <section
      className="weekly-review weekly-review-legacy"
      aria-label="Предыдущий формат недельного разбора"
    >
      <p className="weekly-review-legacy__note" role="status">
        Показан предыдущий формат: новый недельный разбор ещё не сформирован.
      </p>
      {children}
    </section>
  );
}

export function WeeklyReviewView({
  storeId,
  qualityHref = null,
  fallback
}: {
  storeId: string;
  qualityHref?: string | null;
  fallback?: ReactNode;
}) {
  const query = useQuery({
    queryKey: queryKeys.weeklyReview(storeId),
    queryFn: () => getWeeklyReview(storeId),
    refetchInterval: ({ state }) => refetchInterval(state.data)
  });

  if (query.isPending) {
    return (
      <section className="weekly-review weekly-review--loading">
        <PanelSkeleton rows={7} />
      </section>
    );
  }
  if (query.isError && !query.data) {
    return (
      <section className="weekly-review weekly-review--error">
        <QueryError error={query.error} onRetry={() => void query.refetch()} compact />
      </section>
    );
  }
  if (!query.data) {
    return fallback
      ? <LegacyReviewFallback>{fallback}</LegacyReviewFallback>
      : <EmptyReview onRetry={() => void query.refetch()} />;
  }

  return (
    <article className="weekly-review" aria-label="Разбор завершённой недели">
      {query.isError && (
        <StaleDataNote error={query.error} onRetry={() => void query.refetch()} />
      )}
      <WeeklyReviewContent review={query.data} qualityHref={qualityHref} />
    </article>
  );
}
