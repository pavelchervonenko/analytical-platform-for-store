---
doc_schema: 1
doc_type: runbook
status: draft
owner: frontend
audience:
  - developer
last_verified: 2026-08-31
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - local
operation_type: reversible-write
environments:
  - local
  - test
  - staging
risk_level: medium
source_of_truth:
  - AGENTS.md
  - frontend/package.json
  - frontend/playwright.config.ts
verification_evidence:
  - level: static
    scope: local checks, visual review and credential boundaries extracted from historical acceptance
    verified_at: 2026-08-31
    evidence: docs/FRONTEND_ACCEPTANCE.md
required_reviewers:
  - frontend
  - product
  - security-privacy
review_triggers:
  - frontend-tooling-change
  - visual-check-change
  - e2e-mutation-change
supersedes: []
superseded_by: null
---

# Frontend acceptance

## Цель и границы

Проверить frontend локально на точном commit без обращения к production/staging UI. Процедура не
является production smoke и не разрешает использовать production credentials.

## Stop-условия

- Backend, seed data или test credentials не относятся к изолированному local/test контуру.
- Visual origin не loopback.
- Mutating E2E направлен не в одноразовый непроизводственный контур.
- Credentials могут попасть в command history, screenshots, logs или Git.
- Node version не соответствует `frontend/package.json#engines`.

## Автоматическая проверка

```bash
cd frontend
npm ci
npm run check
```

Сохраняется итог test/build/lint/typecheck без credentials и business payload.

## Визуальная проверка

Для материального UI-изменения выполнить `npm run visual:local` по правилам `AGENTS.md`, указав
только затронутые routes. Вручную просмотреть desktop/tablet/mobile, overflow, browser/query errors
и HTTP 5xx. Ignored `frontend/visual-artifacts/` не коммитится.

## Credentialed E2E

Credentialed или mutating E2E выполняется только после отдельной локальной настройки secret input и
на disposable local/test data. Этот draft намеренно не публикует shell-команду с email/password:
сначала нужен wrapper/file-input, исключающий secret из history и process arguments.

## Результат

Evidence содержит commit, Node/package-lock hashes, команды без secret values, pass/fail totals,
просмотренные routes/viewports и ограничения проверки. Production acceptance выполняется отдельно
по production runbook после закрытия его NO-GO gates.
