# Production-эксплуатация LLM-контура

Статус на 2026-08-07: инфраструктурный контур реализован. Prompt v4 / schema v2 прошёл свежий
локальный end-to-end canary revision 7 через initial call, validation retry, immutable publication
и единственное MANAGER notification event. Retry завершился с нулём violations; Telegram fanout
был выключен и deliveries не создавались. Validator не ослаблен, universal narrative safety
invariants подтверждены реальным provider response. Локальный LLM publication gate закрыт.
Оставшиеся gates: server-side staging, failure drills, offline quality evaluation,
alerts/budget controls, Telegram webhook/delivery приёмка и production approval.

## Что уже входит в контур

```text
LiveSklad sync -> backend KPI -> immutable weekly snapshot
-> durable LLM job -> YandexGPT -> schema/evidence/semantic validation
-> immutable interpretation -> dashboard read projection
-> weekly notification event -> Telegram delivery

backend daily KPI projection -> daily notification event -> Telegram delivery
```

LLM не вычисляет показатели и не управляет магазином. Backend фиксирует факты, качество данных,
периоды, планы и допустимость результата; модель выбирает значимые факты, связывает их и формирует
объяснения и рекомендации. Ежедневная утренняя сводка детерминирована и не создаёт ежедневных
платных LLM-вызовов.

## Пользовательские и операторские API

- `GET /api/stores/{storeId}/insights/weekly/current` возвращает `READY`, `PREPARING`, `DELAYED`
  либо `UNAVAILABLE`, безопасный fallback и только опубликованный immutable result.
- `GET /api/admin/llm/operations?incidentLimit=50` возвращает конфигурационную готовность, очередь,
  расходы/токены за 30 дней и очищенные от prompt/response инциденты.
- `POST /api/admin/llm/snapshots/{snapshotId}/regenerate` создаёт новую generation revision.
- `POST /api/admin/llm/jobs/{jobId}/cancel` запрашивает безопасную кооперативную отмену.

Операторские POST требуют ADMIN, `Idempotency-Key` и непустую причину. Действия записываются в
business audit. API не возвращает API key, folder ID, prompt, provider response или персональные
идентификаторы провайдера.

## Порядок включения в staging

Все флаги по умолчанию `false`. На каждом шаге сначала проверяются миграции, readiness, метрики и
операторская панель; следующий шаг не включается при открытом инциденте.

1. На сервере повторить `scripts/yandexgpt-staging-acceptance.sh verify` со staging key и
   синтетическим payload. Локальный provider/application acceptance уже пройден.
2. Проверить канонический обезличенный dataset и его автоматический gate:
   `python3 scripts/llm-eval/evaluate.py` и
   `python3 -m unittest scripts/llm-eval/test_evaluate.py -v`.
3. С заранее утверждённым бюджетом получить полную shadow-матрицу v4/v5 и проверить её:
   `python3 scripts/llm-eval/evaluate.py --responses-dir build/llm-eval/responses
   --require-responses --report build/llm-eval/report.json`. Затем выполнить ручную оценку по
   rubric из dataset. Полный протокол описан в `scripts/llm-eval/README.md`.
4. Включить создание snapshots: `INTERPRETATION_SNAPSHOT_ENABLED`, snapshot planner и worker.
5. Убедиться, что snapshot стабилен и не содержит запрещённых данных.
6. Настроить YandexGPT secret/model/cost limits и включить generation planner/worker.
7. Проверить tokens, known cost, retries, timeout, 401/403, 429, 5xx и invalid JSON.
8. Включить `INTERPRETATION_PUBLICATION_ENABLED`; проверить dashboard и revision update window.
9. Отдельно принять Telegram linking/webhook/delivery и только затем включить fanout.
10. Включить `DAILY_STORE_PULSE_ENABLED` после проверки покрытия SALES и RETURNS за вчера.
11. Зафиксировать результаты staging, модель, prompt/schema versions и лимиты в release evidence.

В production применяется тот же порядок с новыми customer-owned credentials и ручным approval.
Не переносить staging key в production.

## Stop/rollback

- Остановить новые платные вызовы: выключить generation planner; running job завершится в рамках
  bounded deadline.
- Полностью остановить generation: дополнительно выключить generation worker.
- Остановить публикацию новых текстов: выключить publication; уже опубликованные immutable версии
  остаются читаемыми.
- Остановить сообщения: выключить fanout/planner, затем delivery. Уже зафиксированные delivery
  нельзя удалять для имитации отмены; их судьба разбирается оператором.
- Core KPI, отчёты и deterministic fallback продолжают работать при любой остановке LLM.

Rollback приложения разрешён только при совместимой схеме БД. Миграции и опубликованные
интерпретации не откатываются удалением данных.

## Ежедневный контроль

- ADMIN проверяет вкладку LLM: critical incidents, overdue lease, очередь и стоимость.
- Разработчик получает Prometheus alerts из `llm-analysis-alerts.yml`,
  `telegram-delivery-alerts.yml` и `daily-store-pulse-alerts.yml`.
- Руководителям не отправляются технические статусы, коды заданий или сбои провайдеров.
- Не копировать prompt, response, Telegram chat ID, имена сотрудников или значения KPI в
  технический канал инцидента.

## Gate после получения данных от заказчика

Для завершения внешней приёмки нужны:

1. перенести API key на сервер в secret file/storage с правами `0600`;
2. закрепить folder ID, model URI `yandexgpt-5.1`, прошедшую canary версию prompt/schema и
   snapshot calculation `weekly-snapshot-v6` в release configuration; связка
   `weekly-interpretation-v4` / schema `2` прошла успешный end-to-end canary revision 7 и должна
   развёртываться без замены на плавающие или legacy-версии;
3. канонический dataset из 26 обезличенных сценариев уже подготовлен локально; до активации нужно
   получить и сохранить 52 shadow-ответа v4/v5, пройти автоматический gate и ручную rubric без
   critical errors;
4. утвердить лимит бюджета и получателей billing/technical alerts.

Категории данных для текущего weekly payload уже подтверждены: агрегированные
псевдонимизированные метрики без имён, UUID, телефонов, переписок и credentials.

Ключ не требуется добавлять в код. После приёмки он хранится в secret storage окружения,
ротируется по регламенту заказчика и удаляется при подозрении на компрометацию.
