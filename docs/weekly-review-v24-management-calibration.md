# Weekly review v24/schema4 — управленческая калибровка

Дата: 2026-08-29
Статус: локальный regression gate пройден; paid calibration и production-canary ещё не выполнены

## Причина новой версии

Независимое review показало, что свободное продолжение после корректного префикса могло изменить
управленческий смысл summary. Исправлять immutable paid prompt v23 задним числом нельзя. Поэтому
созданы отдельные `weekly-interpretation-v24` и `weekly-review-ai-input-v3`; публичный response
остаётся schema4, расчёты и deterministic snapshot не меняются.

## Что меняет v24

### Итог магазина

Backend формирует от одного до четырёх полных безопасных `summary.allowedNarratives` с одинаковым
подтверждённым смыслом. Модель выбирает один вариант и копирует его дословно. Любое добавление,
удаление или перестановка отклоняется кодом `SUMMARY_NARRATIVE_CHANGED`.

### Основные изменения

Backend формирует `managementMeaning`. Ответ factor обязан дословно вернуть этот смысл без
завершающей точки и одну точную связку:

- `POSITIVE` — точная связка «это положительный сигнал»;
- `NEGATIVE` — точная связка «это зона внимания».

### Следующие шаги

Backend формирует `Action.title` как проверяемую операцию: «Разобрать рост возвратов»,
«Разобрать снижение „…“» или «Проверить изменение „…“». Заголовок не должен обещать желаемый результат словами «восстановить»,
«повысить», «увеличить», «снизить», «улучшить» или «вернуть». Backend-owned `target`, `check`,
`horizon`, metric и evidence остаются неизменными.

## Неизменяемые границы

V24 сохраняет privacy и ownership-ограничения v23:

- store-only privacy-reduced input без магазина, сотрудников, клиентов и документов;
- не более трёх factors и трёх store actions;
- только allowlisted evidence и числовые литералы;
- запрет новых чисел, неподтверждённой причинности, месячного плана и кадровых оценок;
- summary принимается только при точном совпадении с одним элементом `allowedNarratives`;
- factor, `Action.title` и `Action.check` проверяются как backend-owned формулировки;
- exact ordered IDs, exact evidence refs и дословный `action.check`;
- AI может изменить только `summary.outcome.text` и `Factor.detail`; `Action.title` и `Action.check` копируются дословно и повторно проверяются при применении;
- любая ошибка возвращает детерминированный отчёт без частичного применения текста.

## Совместимость v22, v23 и v24

Worker обрабатывает только jobs активной `v24/schema4`. Read-path выбирает enrichment в порядке
`v24 → v23 → v22`; фактическая версия возвращается в API. Старые jobs, attempts, enrichments и
immutable snapshots не переписываются. V23 и v24 копируют заголовок действия из exact snapshot.
Повреждённый или семантически невалидный enrichment применяется fail-closed: пользователь получает
deterministic report.

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
`WEEKLY_REVIEW_AI_ENABLED`; сохранённые v22/v23/v24 enrichment и audit attempts не удаляются.


## Итог v24 на 2026-08-30

- `weekly-review-ai-eval-v5`: 41 offline-сценарий, PASS;
- targeted contract, validator, compactor и Checkstyle: PASS;
- network-free plan: 4 cases, maximum 14,9472 ₽;
- ранее израсходовано v23: 14,104 ₽ из общего разрешённого лимита 20 ₽;
- выполнен один paid case `positive-growth`: semantic validation — PASS, стоимость 1,036 ₽;
- полный backend regression: 1052 теста, 0 failures/errors/skipped; Checkstyle и OpenAPI compatibility — PASS;
- frontend regression: 41 test file / 175 тестов, ESLint и production build — PASS;
- security/release/supply-chain gates — PASS;
- independent blind review: hard gates — 0, средняя оценка — 4,4/5, но
  `manager usefulness` — 2/5 из-за почти дословного повторения backend input;
- итоговый gate — **REJECT**: v24 не допускается к production-canary; дополнительные paid-вызовы
  остановлены, следующий candidate обязан использовать новую immutable prompt/input/corpus version.
