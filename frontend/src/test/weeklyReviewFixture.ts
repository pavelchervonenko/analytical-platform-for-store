import { weeklyReviewSchema, type WeeklyReview } from "../api/weeklyReviewContract";
import weeklyReviewGolden from "./fixtures/weekly-review-v2-ready.json" with { type: "json" };

export function makeWeeklyReview(): WeeklyReview {
  return weeklyReviewSchema.parse(structuredClone(weeklyReviewGolden));
}
