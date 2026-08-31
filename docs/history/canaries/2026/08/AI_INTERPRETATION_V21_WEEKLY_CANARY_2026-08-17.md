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
original_content_sha256: 99e48c1f8d23be4d8edb0eaac9db39046cc12491aef16b5123d441f84e3b2bd8
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/ai/README.md`.

# ИИ-интерпретация v21: exact-week canary и точка продолжения

Дата фиксации: 2026-08-17
Период анализа: 2026-08-10 — 2026-08-16
Период сравнения: 2026-08-03 — 2026-08-09
Статус: два exact-week shadow-ответа v21 получены и прошли production-equivalent
структурную и семантическую валидацию. Production default и production database
не изменялись.

Обновление 2026-08-21: полный semantic gate v21/schema 3 пройден на 26 сценариях;
актуальный статус зафиксирован в scripts/llm-eval/README.md. Канонический итоговый артефакт —
`build/llm-eval/v4-v21-full-20260819/FINAL-v21-schema3-decision.json`; одноимённый Markdown-файл
содержит операторскую сводку.

Этот документ заменяет
[AI_INTERPRETATION_V19_RELEASE_HANDOFF_2026-08-17.md](../../../handoffs/2026/08/AI_INTERPRETATION_V19_RELEASE_HANDOFF_2026-08-17.md)
как текущая точка продолжения ИИ-работ. V19 и v20 остаются immutable-историей
обнаруженных ограничений.

## 1. Итог

Кандидат `weekly-interpretation-v21` / content schema `3` успешно обработал
реальные агрегированные данные обоих магазинов за завершённую неделю:

- `Магазин`: provider success, semantic validation success;
- `МобиСфера`: provider success, semantic validation success;
- moderation/refusal: 0 из 2;
- structural/semantic violations: 0 из 2;
- фактическая стоимость двух финальных вызовов: `6,4776 ₽`;
- Telegram delivery, publication и production rollout не выполнялись.

## 2. Какой дефект обнаружен на real-data canary

V19 и затем moderation-safe v20 успешно проходили синтетическую матрицу, но
exact-week запросы обоих магазинов завершались `content_filter`. Персональные
данные к этому моменту уже отсутствовали: провайдер получал только агрегаты
магазина.

Контролируемые проверки показали:

1. Удаление только человекочитаемых category labels не устраняет блокировку.
2. Сокращённый набор из шести store candidates проходит провайдера, но широкий
   enum допускает дублирование candidate refs в сыром ответе.
3. Два сигнала разных тем, включая attach-rate, проходят провайдера и дают
   однозначно проверяемый ответ.

Причина относится не к конкретному названию категории, а к сложности расширенного
provider input/response schema с 11–12 одновременно разрешёнными сигналами.

## 3. Исправление v21

V21 сохраняет privacy-reduced архитектуру v19/v20 и добавляет отдельный bounded
provider profile:

- провайдер получает не более двух store candidates;
- первый candidate выбирается существующей детерминированной priority policy;
- второй candidate по возможности обязан иметь другую тему;
- primary signal фиксируется schema;
- secondary insights ограничены максимум одним элементом и одним candidate ref;
- employee facts, employee refs и person-level candidates провайдеру не
  передаются;
- backend по-прежнему владеет персональными headlines, relationships,
  limitations, пользовательскими названиями категорий и canonical content;
- v19 и v20 не переписываются задним числом.

Shadow runner теперь не считает HTTP 200 достаточным успехом. Новый ответ и уже
сохранённый response обязательно прогоняются через versioned production
validator против exact provider input и полного immutable snapshot.

## 4. Результат недели

### Магазин

ИИ выбрал главным сигналом риск выполнения плана аксессуаров. На срезе недели:

- фактические аксессуары: `2 319 182 ₽`;
- плановая сумма на фактическую выручку: `2 741 406,82 ₽`;
- прогноз выполнения: `84,6%`;
- отклонение от требуемой суммы: `−422 224,82 ₽`.

Дополнительным сигналом ИИ выбрал положительную динамику валовой прибыли:

- чистая выручка: `21 263 881,50 ₽` против `18 299 062,50 ₽`, рост `16,2%`;
- валовая прибыль: `3 560 349,15 ₽` против `2 926 671,46 ₽`, рост `21,7%`;
- маржинальность: `16,74%` против `15,99%`, рост `0,75 п.п.`.

Контрольный контекст, не вошедший в два выбранных ИИ-сигнала:

- прогноз общего плана выручки: `153,29%`;
- прогноз плана услуг: `104,46%`;
- прогноз плана допов: `92,54%`;
- стекло Samsung: `28,57` на 100 устройств против `17,02`, рост `11,55 п.п.`;
- настройки: `41,94` против `50,49`, снижение `8,55 п.п.`;
- гарантия на новые устройства: рост `9,82 п.п.`;
- гарантия на Б/У устройства: снижение `11,06 п.п.`.

### МобиСфера

ИИ выбрал главным сигналом положительное выполнение плана аксессуаров:

- фактические аксессуары: `512 034 ₽`;
- плановая сумма на фактическую выручку: `410 960,42 ₽`;
- прогноз выполнения: `124,59%`;
- превышение требуемой суммы: `101 073,58 ₽`.

Дополнительным сигналом ИИ выбрал положительную динамику валовой прибыли:

- чистая выручка: `6 171 808 ₽` против `5 174 950 ₽`, рост `19,3%`;
- валовая прибыль: `934 509 ₽` против `762 888 ₽`, рост `22,5%`;
- маржинальность: `15,14%` против `14,74%`, рост `0,40 п.п.`.

Контрольный контекст, не вошедший в два выбранных ИИ-сигнала:

- прогноз общего плана выручки: `120,64%`;
- прогноз плана услуг: `111,11%`;
- прогноз плана допов: `119,20%`;
- аксессуары Pods/Watch: `5,26` на 100 устройств против `0`;
- чехлы Samsung: `11,11` против `44,44`, снижение `33,33 п.п.`;
- настройки: `24,36` против `35,48`, снижение `11,12 п.п.`;
- `TEAM.RATING.ELIGIBLE_COUNT=0`, поэтому сравнение сотрудников корректно
  отмечено как недоступное из-за недостаточной командной базы.

## 5. Проверки и стоимость

Проверено:

- targeted backend contract, compactor, request factory, validator и shadow
  runner tests: успешно;
- Python evaluator tests: `58`, успешно;
- cached exact-week responses повторно проверены versioned production validator:
  `2/2 VALID`, повторная стоимость `0 ₽`;
- backend full `:backend:check`: успешно, `864` теста, `0` failures,
  `0` ignored;
- `git diff --check`: успешно.

Расходы исследовательского цикла v20/v21:

- синтетическая v20-пара: `2,2568 ₽`;
- v20 exact-week moderation failures: `0 ₽`;
- v20 core diagnostic: `3,1440 ₽`;
- v20 two-signal diagnostic: `1,6600 ₽`;
- два финальных v21 exact-week ответа: `6,4776 ₽`;
- итого: `13,5384 ₽`.

Raw inputs, provider responses, receipts и request/evaluation hashes находятся в
ignored-каталогах `build/llm-eval/` и в git не добавляются. API key и другие
секреты в артефакты и документацию не записываются.

## 6. Что ещё не выполнено

- runtime-поддержка v21/schema 3 уже развёрнута, но production default не активирован;
- application/production default остаётся v4/schema 2;
- production env и Compose не менялись;
- результаты не публиковались в production database;
- consumer API/UI и Telegram fanout для v21 не переключались.

## 7. Следующий безопасный шаг

1. Дождаться завершения текущего backfill и подготовить изолированный canary-запуск
   на уже развёрнутом runtime.
2. Выполнить один production canary без Telegram fanout и проверить весь путь:
   snapshot → bounded provider input → response → validation → publication →
   consumer API → отдельный раздел ИИ.
3. Только после приёмки отдельно решить вопрос о смене production default.

Rollback пары prompt/schema атомарный: вернуть `weekly-interpretation-v4` вместе
со schema `2`. Нельзя смешивать v21 со schema 2 или v4 со schema 3.
