---
doc_schema: 1
doc_type: evidence
status: historical
owner: ai
audience:
  - developer
snapshot_date: 2026-08-21
verdict: PASS_WITH_LIMITS
verdict_scope: Offline and blinded v21/schema3 evaluation; canary eligibility did not change production defaults.
source_of_truth:
  - scripts/llm-eval/README.md
  - docs/history/canaries/2026/08/AI_INTERPRETATION_V21_WEEKLY_CANARY_2026-08-17.md
required_reviewers:
  - ai
  - product
---

# Финальный сохранённый прогон v21/schema3

Результат:

- v21: 26/26 automatic pass, 0 violations;
- v21: 26/26 blinded manual pass, средняя оценка 4,8/5;
- 0 missing, forbidden и critical findings;
- решение: `CANDIDATE_ELIGIBLE_FOR_CANARY`;
- production default на дату evidence оставался v4/schema2;
- v4 сохранил 110 automatic violations и 11/26 manual pass как контрольный baseline.

Локальные ignored-артефакты прогона:

```text
build/llm-eval/v4-v21-full-20260819/responses/
build/llm-eval/v4-v21-full-20260819/automatic-report-final.json
build/llm-eval/v4-v21-full-20260819/review-final/
build/llm-eval/v4-v21-full-20260819/FINAL-v21-schema3-decision.json
build/llm-eval/v4-v21-full-20260819/FINAL-v21-schema3-decision.md
```

`blinded-decision-final.*` относился к более ранней неуспешной ручной оценке и не являлся
источником финального решения.
