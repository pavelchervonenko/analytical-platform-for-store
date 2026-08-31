---
doc_schema: 1
doc_type: evidence
status: historical
owner: ai
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS_WITH_LIMITS
verdict_scope: "Preserved legacy evidence; commands and runtime claims require current verification."
source_of_truth:
  - "docs/current/ai/README.md"
original_content_sha256: 5fe721e5c56c4afc2212cf58a733f77a055bc55008a1e45abe8cfa8d2acd57ff
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/ai/README.md`.

# Weekly review v23/schema4 — управленческая калибровка

Дата: 2026-08-29
Статус: историческая immutable v23-калибровка; superseded: v24 отклонён, активный локальный candidate — v25; production-canary не выполнялся

## Причина новой версии

Первый production-canary `weekly-interpretation-v22/schema4` для магазина «МАГАЗИН» прошёл
технически успешно: одна попытка, HTTP 200, semantic validation без нарушений, опубликованное
enrichment не изменило ни одного backend-owned поля. При этом тексты оказались недостаточно
полезными руководителю:

- итог недели перечислил изменение выручки и прибыли, но почти не дал целостной оценки недели;
- факторы повторили сравнение текущего и предыдущего значения без управленческого смысла;
- действия описали желаемый результат («восстановить»), а не проверяемую операцию руководителя.

Содержимое prompt нельзя менять под прежним immutable identifier. Поэтому активный prompt получает
новый идентификатор `weekly-interpretation-v23`. Provider input переходит с immutable `input-v1` на
`input-v2`: в нём добавлены backend-owned `summary.outcomeEffect` и `factor.managementMeaning`, а также закреплён новый prompt ID.
Provider response и публичный API остаются `schema4`; расчёты не меняются.

## Что меняет v23

### Итог магазина

Текст сначала классифицирует картину недели относительно периода сравнения по
backend-owned `summary.outcomeEffect`: сильнее, слабее, стабильная или неоднозначная. Затем
он называет ключевые показатели и поясняет, требуют ли они внимания. Простая цепочка значений без
итоговой оценки запрещена prompt-контрактом.

### Основные изменения

Каждый factor по-прежнему содержит только переданное наблюдение и не превращается в доказанную
причину. Backend дополнительно формирует `managementMeaning`: безопасное предметное объяснение
показателя — например, attach-rate переводится в частоту дополнения базовых продаж. Модель обязана
использовать этот смысл вместо повтора чисел и перевести `effect` в понятный руководителю сигнал:

- `POSITIVE` — положительный сигнал или улучшение;
- `NEGATIVE` — зона внимания или риск.

### Следующие шаги

Backend формирует `Action.title` как проверяемую операцию: «Разобрать рост возвратов»,
«Разобрать снижение „…“» или «Проверить изменение „…“». Заголовок не должен обещать желаемый результат словами «восстановить»,
«повысить», «увеличить», «снизить», «улучшить» или «вернуть». Backend-owned `target`, `check`,
`horizon`, metric и evidence остаются неизменными.

## Неизменяемые границы

V23 сохраняет все ограничения v22:

- store-only privacy-reduced input без магазина, сотрудников, клиентов и документов;
- не более трёх factors и трёх store actions;
- только allowlisted evidence и числовые литералы;
- запрет новых чисел, неподтверждённой причинности, месячного плана и кадровых оценок;
- отрицания, сомнения и оговорки в управленческой оценке блокируются fail-closed; исключение —
  одна из двух точных нейтральных конструкций: «существенных изменений нет» или «существенных изменений относительно предыдущей недели нет»;
- exact ordered IDs, exact evidence refs и дословный `action.check`;
- AI может изменить только `summary.outcome.text` и `Factor.detail`; `Action.title` и `Action.check` копируются дословно и повторно проверяются при применении;
- любая ошибка возвращает детерминированный отчёт без частичного применения текста.

## Совместимость v22 и v23

Worker выбирает, восстанавливает и учитывает в operational-метриках только jobs активной версии
`v23/schema4`. Явное несовпадение версии прекращает выполнение до подготовки provider request.
Read-path сначала ищет опубликованный v23 enrichment и, пока его нет, применяет immutable
`v22/schema4` fallback с фактической версией в API. Поэтому canary «МобиСферы» не скрывает
проверенный v22-результат другого магазина. Legacy jobs, enrichments и attempts не переписываются.

V23 input не пересчитывает `Action.title`, а копирует его из exact immutable snapshot. Поэтому уже
созданные snapshots со старой безопасной формулировкой действия совместимы с новым prompt; enricher
сверяет и применяет тот же заголовок без изменения snapshot payload.

## Проверки перед production

1. Prompt/resource contract и полный backend test/checkstyle прогон.
2. Network-free `weeklyReviewAiShadow` plan и offline semantic corpus.
3. Ограниченный paid shadow-run на versioned YandexGPT model.
4. Независимое blind/read-only ревью полезности, фактической точности и рисков формулировок.
5. Immutable release и deploy с выключенными snapshot/AI planners.
6. Один ручной canary для последней завершённой недели «МобиСферы».
7. Проверка неизменности backend contract, стоимости, validation и production health/logs.

Автоматические planners нельзя включать, пока canary не подтвердит одновременно техническую
валидность и управленческую полезность. Откат выполняется выключением
`WEEKLY_REVIEW_AI_ENABLED`; сохранённые v22/v23 enrichment и audit attempts не удаляются.


## Результат локальной калибровки 2026-08-29

- offline semantic corpus: 41 сценарий, все прошли;
- целевые contract/validator/compactor tests и Checkstyle: PASS;
- network-free plan: 4 сценария, maximum 16,3848 ₽;
- фактический расход всех последовательных калибровочных вызовов: 14,104 ₽ из разрешённых 20 ₽;
- финальный positive delta: semantic VALID, стоимость 1,2216 ₽;
- независимый blind review финального ответа: PASS, 4,8/5, hard gates — 0;
- неподтверждённая формулировка «предыдущая полная неделя» в factor теперь блокируется валидатором;
- полный regression gate: backend `1045 tests, 0 failures, 0 errors`; Checkstyle и OpenAPI — PASS;
- frontend: `41 files, 175 tests`; contracts, ESLint и production build — PASS;
- security/release-safety, supply-chain (`449 components`, `840 artifacts`) и `git diff --check` — PASS;
- редкий откат системных часов между claim и terminal transition закрыт монотонным `finished_at` и регрессионными тестами;
- production-canary ещё не выполнялся.
