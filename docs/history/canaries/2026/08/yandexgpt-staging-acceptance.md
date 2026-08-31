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
original_content_sha256: 7b919b4db2f50ab6556a7321d0b618e182b2b3c8bc89721b9a584cb21a72b651
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/ai/README.md`.

# Приемка YandexGPT в staging

Этот gate выполняется после получения отдельного staging API-ключа и до включения generation/publication в production. Скрипт отправляет один платный синтетический запрос без данных магазина, проверяет авторизацию, доступ к модели, structured output, точное имя модели и usage. Ключ и тело ответа не печатаются.

## Подготовка

1. Создать отдельный сервисный аккаунт staging в каталоге заказчика.
2. Выдать минимальную роль `ai.languageModels.user`.
3. Создать API-ключ с областью `yc.ai.languageModels.execute` и ограниченным сроком действия.
4. Сохранить ключ в файл с правами `0600`, вне репозитория.
5. Выбрать явный model URI и сначала закрепить его в staging.

```bash
chmod 600 /secure/path/yandex-staging-api-key
export YANDEX_AI_FOLDER_ID='<folder-id>'
export YANDEX_AI_MODEL_URI='gpt://<folder-id>/<versioned-model>'
export YANDEX_AI_API_KEY_FILE='/secure/path/yandex-staging-api-key'
export CONFIRM_YANDEX_LLM_CALL='CALL_STAGING_MODEL'
scripts/yandexgpt-staging-acceptance.sh verify
```

Секрет нельзя передавать аргументом CLI, коммитить в `.env`, помещать в issue/чат или сохранять в CI artifact. После компрометации ключ удаляется, а не переиспользуется.

## Полная приемка приложения

После provider smoke-test:

1. проверить канонический dataset: `python3 scripts/llm-eval/evaluate.py`;
2. повторно проверить сохранённые 52 shadow-ответа v4/v19; различие input v4/v19 ожидаемо из-за
   privacy-reduced store-only payload кандидата;
3. подтвердить полный автоматический gate с `--require-responses` и blinded report по rubric из
   `scripts/llm-eval/README.md`;
4. включить snapshot/generation/publication только в staging;
5. сформировать недельный snapshot и дождаться публикации через `/api/admin/llm/operations`;
6. проверить dashboard, Telegram-текст, токены, стоимость и отсутствие PII в provider payload/logs;
7. выполнить drills: неверный ключ, 429, timeout, невалидный JSON, отмена и ручная регенерация;
8. зафиксировать результаты приемки и только затем повторить конфигурацию production.

## Подтвержденный локальный acceptance — 2026-08-06

С разрешения владельца данных выполнен полный прогон на агрегированных псевдонимизированных недельных метриках из локальной БД:

- модель: `yandexgpt-5.1`, versioned model URI;
- snapshot calculation: `weekly-snapshot-v4`;
- prompt: `weekly-interpretation-v3`;
- provider projection не содержит имен, внутренних UUID сотрудников, raw CRM/переписок и backend-owned limitations;
- response schema динамически ограничивает точное количество сотрудников, допустимые `employeeRef` и evidenceRefs фактов;
- отправка выполняется с `x-data-logging-enabled: false`;
- повторный job после migration V31 завершился `SUCCESS`, результат прошел
  structural/fact/safety validation и был опубликован;
- raw provider response и canonical validated response сохранены отдельно, оба hash воспроизводятся,
  содержимое и hash ожидаемо различаются после backend-owned normalization;
- публикация прочитала только validated response;
- usage подтверждающего прогона: input `4731`, output `4217`, total `8948` токенов,
  стоимость `7,1584 ₽`;
- дополнительный validation retry не потребовался.

Локальный acceptance подтверждает работоспособность интеграции и контракта, но не заменяет инфраструктурную staging-приемку. До production остаются server-side secrets, alerts/budget limits, backup/restore и перечисленные выше failure drills.

## Контрольный canary prompt v4 / schema v2 — 2026-08-07

После исправления тестовых смен, часов, продаж и рейтингов сформирован snapshot revision 6 на `weekly-snapshot-v4`: все 9 сотрудников имеют `SUFFICIENT`. Выполнен один ограниченный вызов YandexGPT без Telegram и без автоматического retry:

- job `977c3dca-7f5d-4ca6-97b1-e028d65ae663`, generation revision 2;
- HTTP 200; input `11435`, output `4364`, total `15799` токенов;
- фактическая стоимость `12,6392 ₽`; provider attempts: ровно один;
- публикации, notification event и Telegram delivery не было;
- результат отклонён structural validation: модель скопировала угловые скобки из `CATEGORY:<code>` и придумала недопустимые competency aliases;
- локальный semantic audit дополнительно выявил отсутствие обязательных `HEADLINE` и `WORKLOAD` для шести сотрудников, отсутствие отдельных category/additional-sales insights, один повтор и технические employee refs в relationship summaries.

По итогам canary prompt теперь задаёт точный формат category competency и запрещает employee refs в тексте; validation retry получает сгруппированные нарушения и точный allowlist из immutable snapshot; provider schema требует минимум `2 + 2 * employee count` summary blocks и ограничивает шумные team relationships. Эти изменения прошли checkstyle, unit и integration tests. Prompt v4 / schema v2 остаётся закрытым для production до следующего canary, который должен пройти initial response и, при необходимости, единственный validation retry.


## Контрольный canary validation retry — 2026-08-07

Следующий ограниченный прогон проверил именно ветку validation retry на том же snapshot revision 6. Telegram, publication planner и прочие фоновые отправители были выключены:

- job `baee3724-a260-4a44-a3bd-0a00e213b609`, generation revision 3;
- initial response: HTTP 200, input `11669`, output `4295`, total `15964` токенов, `12,7712 ₽`;
- validation retry: HTTP 200, input `12059`, output `4552`, total `16611` токенов, `13,2888 ₽`;
- всего две сохранённые provider-попытки на `26,0600 ₽`; внутренний `job.attempt_count=4` считает циклы claim/обработки и не является числом платных HTTP-вызовов;
- initial response сократил предыдущие проблемы до 12 semantic violations: девять отсутствующих `WORKLOAD`, один конфликт action scope и две недопустимые mentor-связи;
- validation retry реально сработал и оставил только один конфликт: у team-wide action модель одновременно указала отдельных участников в `targetEmployeeRefs`;
- публикации, interpretation, notification event и Telegram delivery не было: terminal validation gate корректно остановил результат.

Backend теперь канонизирует широкие действия безопасно: для `STORE` и `TEAM` поле `targetScope` считается определяющим, а `targetEmployeeRefs` очищается. Это не меняет смысл рекомендации и не превращает командное действие в персональное. Сохранённый ответ retry повторно проверен обновлённым validator без нового обращения к модели и стал валидным: 9 сотрудников, 20 summary blocks, 5 insights, 3 actions и 6 team relationships. В ответе есть магазин, категории и допродажи, нет повторяющихся действий и технических employee/category codes в пользовательском тексте.

Этот replay подтверждает исправление последнего известного дефекта, но не считается публикацией задним числом. Для допуска prompt v4 / schema v2 нужен один свежий end-to-end canary на обновлённом backend: результат должен пройти штатный validator, опубликоваться и создать ровно одно недельное событие. Ожидаемая стоимость одной попытки около `13–15 ₽`; аварийный лимит с единственным validation retry — `30 ₽`.

## Fresh end-to-end canary revision 4 — 2026-08-07

Canary выполнен на новом backend-коде через штатную manual regeneration; planners и все Telegram-компоненты были выключены:

- job `b8edf1a0-6d8f-4a86-831c-261c073eebee`, generation revision 4;
- initial response: HTTP 200, input `11669`, output `5009`, total `16678` токенов, `13,3424 ₽`;
- initial validation оставил только два нарушения: отсутствующий `HEADLINE` для E08 и неподтверждённое измерение profitability в одном risk insight;
- validation retry: HTTP 200, input `12020`, output `4073`, total `16093` токенов, `12,8744 ₽`;
- всего две provider-попытки на `26,2168 ₽`;
- retry исправил исходные два нарушения, но добавил цифры и проценты в девять WORKLOAD-текстов, поэтому получил девять `FORBIDDEN_NARRATIVE_LITERAL`;
- interpretation, notification event и Telegram delivery не созданы: validation gate сработал до публикации.

Validator не ослаблялся: непроверенные числовые значения по-прежнему запрещены в narrative. Причина найдена в retry instruction, который передавал модели код нарушения без его значения. Retry prompt теперь явно требует для `FORBIDDEN_NARRATIVE_LITERAL` качественные формулировки без цифр, процентов, валют, точных количеств прописью и самостоятельных вычислений. Исправление покрыто unit-тестом и checkstyle.

Prompt v4 / schema v2 остаётся закрытым для production. Следующий canary разрешён только с новым отдельным бюджетом; критерий не меняется: штатный `SUCCESS`, одна immutable interpretation, ровно одно недельное notification event и ноль Telegram deliveries при выключенном fanout.

## Canary revisions 5–6 и итоговый safety fix — 2026-08-07

Два дополнительных ограниченных прогона проверили усиленную validation-retry ветку. Telegram,
fanout, planners и delivery были выключены; публикационный gate оставался включён для проверки
полного пути, но ни один невалидный ответ его не прошёл.

Revision 5, job 1d929f96-0f35-4cc7-936b-2abdd3296455:

- initial: 11669 input, 4656 output, 13,0600 ₽, 22 semantic violations;
- retry: 12468 input, 4025 output, 13,1944 ₽, 16 semantic violations;
- всего 26,2544 ₽;
- основной остаток — employee refs и цифры в team relationship summaries; попытка модели
  перестроить связи дополнительно создала неподтверждённые пары наставников.

Retry policy после этого требует удалять отклонённый optional team relationship целиком, допускает
пустой массив связей и запрещает придумывать замену.

Revision 6, job a57c4ff3-a2d6-4d97-9062-b9551707963d:

- initial: 11669 input, 4409 output, 12,8624 ₽, 6 structural violations;
- retry: 12042 input, 4055 output, 12,8776 ₽, только 2 semantic violations;
- всего 25,7400 ₽;
- оба остаточных нарушения — цифры в двух summary blocks;
- interpretations, notification events и deliveries: 0.

Причина последнего остатка: initial имел только schema codes, поэтому условное пояснение числового
запрета не добавлялось в retry prompt. Narrative safety invariants теперь повторяются при любом
validation retry независимо от исходного кода: никакие text/title/summary не могут содержать цифры,
проценты, валюту, employee/category/competency/evidence identifiers; допускаются только
качественные формулировки, подтверждённые supplied evidence. Изменение покрыто unit tests и
checkstyle. Новые платные вызовы остановлены; следующий canary допустим только как отдельный
контрольный прогон этой финальной политики.

## Успешный end-to-end canary revision 7 — 2026-08-07

Финальный canary выполнен на universal retry safety invariants. Изолированный worker использовал
prompt v4 / schema v2, publication была включена, а Telegram, fanout и все planners выключены:

- job 93f5a7e1-477b-4c74-af55-f5fe8a413f0e, generation revision 7;
- initial: 11669 input, 3954 output, 12,4984 ₽;
- initial validation выявил 16 нарушений: технические employee refs и цифры в relationship
  summaries, а также неподтверждённые mentor links;
- validation retry: 12486 input, 3870 output, 13,0848 ₽, статус SUCCEEDED, 0 violations;
- всего 25,5832 ₽;
- job завершился SUCCESS в фазе PUBLISH;
- создана одна immutable interpretation 367f6db8-7843-4906-9148-6e96955850fe,
  revision 4 для периода 2026-07-27 — 2026-08-02;
- создано ровно одно MANAGER событие WEEKLY_REPORT_REVISED
  e8440eb0-c119-4ff0-b49c-88bff953b0d6;
- Telegram deliveries: 0 при выключенном fanout.

Содержимое включает всех девять SUFFICIENT сотрудников, 25 summary blocks, 5 material insights,
3 distinct actions, 6 validated team relationships и 2 backend-owned data limitations. У каждого
сотрудника есть отдельные HEADLINE и WORKLOAD. Представлены store result, category mix,
additional-sales/attach-rate opportunity, лидеры и peer learning. В narrative нет цифр,
технических employee/category/evidence refs или дублирующихся действий.

Этот прогон закрывает локальный end-to-end gate prompt v4 / schema v2. Он не заменяет server-side
staging acceptance, production secrets, alerts/budget approval и Telegram webhook/delivery
приёмку.

## Подтвержденная рабочая конфигурация

- `LLM_MAX_OUTPUT_TOKENS=8000`;
- `YANDEX_AI_READ_TIMEOUT=180s`;
- `INTERPRETATION_GENERATION_PROVIDER_CALL_TIMEOUT=180s`;
- `INTERPRETATION_GENERATION_LEASE_DURATION=4m`;
- context window policy: `32768` токенов;
- один initial call и максимум один validation retry.

## Критерий допуска

Допуск запрещён, если schema/evidence validation не проходит, модель возвращает другой model URI, стоимость не наблюдаема, обнаружены персональные данные, задача зависает без alert или fallback dashboard скрывает недоступность интерпретации.

Официальные основания: [API-ключи и scopes](https://yandex.cloud/ru/docs/iam/concepts/authorization/api-key),
[базовый OpenAI-compatible запрос](https://aistudio.yandex.ru/docs/ru/ai-studio/operations/generation/completions-basic.html),
[structured output](https://aistudio.yandex.ru/docs/ru/ai-studio/operations/generation/completions-structured.html),
[модели](https://aistudio.yandex.ru/docs/ru/ai-studio/concepts/generation/models.html) и
[тарифы](https://aistudio.yandex.ru/docs/ru/ai-studio/pricing.html).
