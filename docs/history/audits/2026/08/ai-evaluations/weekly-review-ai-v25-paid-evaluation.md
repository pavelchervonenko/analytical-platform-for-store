---
doc_schema: 1
doc_type: evidence
status: historical
owner: ai
audience:
  - developer
  - operator
snapshot_date: 2026-08-30
verdict: PASS_WITH_LIMITS
verdict_scope: One approved anonymized v25 balanced-strength-risk call and its semantic/blind evaluation.
source_of_truth:
  - scripts/weekly-review-ai-eval/README.md
  - docs/history/canaries/2026/08/weekly-review-v25-rollout.md
required_reviewers:
  - ai
  - product
---

# Платная оценка Weekly Review v25

Network-free plan на дату evidence:

- полный corpus — максимум 12,432800 ₽;
- `balanced-strength-risk` — максимум 3,296800 ₽.

Исторический бюджет составлял 20 ₽. На дату evidence было использовано 16,016 ₽, остаток —
3,984 ₽. Отдельно разрешённый вызов `balanced-strength-risk` стоил 0,876 ₽ и прошёл semantic и
blind gates.

Эти суммы не являются новым разрешением: cap нельзя переносить на следующий вызов, а
`CANDIDATE_ELIGIBLE_FOR_CANARY` не включает publication, flags или deployment.
