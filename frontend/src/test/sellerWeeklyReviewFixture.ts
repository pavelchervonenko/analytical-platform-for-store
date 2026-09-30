import { sellerWeeklyReviewSchema, type SellerWeeklyReviewView } from "../api/sellerWeeklyReviewContract";
import golden from "./fixtures/weekly-review-v3-seller-partial.json" with { type: "json" };

/** Backend-assembled synthetic fixture; never substitute real store payloads here. */
export function makeSellerWeeklyReview() {
  return sellerWeeklyReviewSchema.parse(structuredClone(golden));
}

export function makeSellerWeeklyReviewView(): SellerWeeklyReviewView {
  return { freshness: "CURRENT", report: makeSellerWeeklyReview() };
}
