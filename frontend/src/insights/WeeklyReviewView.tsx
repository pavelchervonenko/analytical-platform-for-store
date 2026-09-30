import { useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import type { ReactNode } from "react";
import { getSellerWeeklyReview, getWeeklyReview, queryKeys } from "../api/queries";
import type { SellerWeeklyReviewView } from "../api/sellerWeeklyReviewContract";
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

export function sellerReviewPresentation(view: SellerWeeklyReviewView): WeeklyReview | null {
  const report = view.report;
  if (report === null) return null;
  const stale = view.freshness === "STALE";
  return {
    ...report,
    // Suppress future actions on a stale roster/source without rewriting historical evidence.
    actions: stale ? [] : report.actions,
    employees: report.employees.map(({ card, actionableNow }) => ({
      ...card, action: stale || !actionableNow ? null : card.action
    })),
    sellerContext: {
      freshness: view.freshness,
      membership: report.membership,
      additionalSales: report.additionalSales,
      teamDisplay: report.teamDisplay
    }
  };
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
  const sellerQuery = useQuery({
    queryKey: queryKeys.sellerWeeklyReview(storeId),
    queryFn: () => getSellerWeeklyReview(storeId),
    refetchInterval: ({ state }) => state.data?.freshness === "PREPARING" || state.data?.freshness === "STALE"
      || state.data?.report?.aiEnhancement.state === "PREPARING"
      || state.data?.report?.aiEnhancement.state === "DELAYED"
      ? 15_000 : false
  });
  const query = useQuery({
    queryKey: queryKeys.weeklyReview(storeId),
    queryFn: () => getWeeklyReview(storeId),
    refetchInterval: ({ state }) => refetchInterval(state.data),
    enabled: sellerQuery.isSuccess && sellerQuery.data === null
  });

  if (sellerQuery.isPending) {
    return <section className="weekly-review weekly-review--loading"><PanelSkeleton rows={7} /></section>;
  }
  if (sellerQuery.isError && !sellerQuery.data) {
    return <section className="weekly-review weekly-review--error">
      <QueryError error={sellerQuery.error} onRetry={() => void sellerQuery.refetch()} compact />
    </section>;
  }
  if (sellerQuery.data) {
    const review = sellerReviewPresentation(sellerQuery.isError
      ? { ...sellerQuery.data, freshness: "STALE" } : sellerQuery.data);
    if (review === null) return <EmptyReview onRetry={() => void sellerQuery.refetch()} />;
    return <article className="weekly-review" aria-label="Разбор завершённой недели по продавцам">
      {sellerQuery.isError && <StaleDataNote error={sellerQuery.error} onRetry={() => void sellerQuery.refetch()} />}
      <WeeklyReviewContent review={review} qualityHref={qualityHref} />
    </article>;
  }

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
      <p className="weekly-review-legacy__note" role="status">
        Предыдущий формат: результаты всего магазина, не только продавцов рейтинга.
      </p>
      <WeeklyReviewContent review={query.data} qualityHref={qualityHref} />
    </article>
  );
}
