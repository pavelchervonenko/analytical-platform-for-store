# Ежедневная утренняя сводка магазина

Статус на 2026-08-03: backend projection, immutable notification event, Telegram fanout,
рендеринг, scheduling guards и метрики реализованы. Функция по умолчанию выключена.

## Бизнес-смысл

В среду руководитель получает сводку за завершённый вторник. Сообщение содержит бизнес-новости:
выручку и динамику, средний чек, дополнительные продажи и показатель на телефон, ведущие
категории, короткий командный блок и предупреждение о качестве данных. Статусы документов,
внутренние job-коды и технические ошибки не отправляются.

Это отдельная backend-проекция, а не новый LLM-анализ. Она использует уже рассчитанные KPI и
детерминированно формирует компактный payload. Недельная LLM-интерпретация по понедельникам остаётся
отдельным потоком.

## Выполнение

Worker каждые пять минут рассматривает только вчерашнюю локальную дату магазина. Событие создаётся,
если одновременно:

- текущее локальное время находится в окне `08:05 <= time < 14:00`;
- успешная синхронизация SALES покрывает весь вчерашний день;
- успешная синхронизация RETURNS покрывает весь вчерашний день;
- для store/date/policy ещё нет такого immutable события.

Ключ дедупликации: `daily-store-pulse:{storeId}:{businessDate}:{policyVersion}`. Повторный запуск
создаёт `existing`, а не второе сообщение. После 14:00 устаревшая сводка не догоняется: утром
следующего дня будет сформирован новый актуальный период.

`notification_events` хранит canonical payload/hash и expiry. Fanout выбирает только активных
подтверждённых получателей с доступом к магазину и включёнными бизнес-уведомлениями. Дальнейшая
доставка использует общий Telegram lease/retry/attempt lifecycle.

## Конфигурация

```dotenv
DAILY_STORE_PULSE_ENABLED=false
DAILY_STORE_PULSE_PLANNER_DELAY=5m
DAILY_STORE_PULSE_SEND_AFTER=08:05
DAILY_STORE_PULSE_EXPIRES_AT=14:00
DAILY_STORE_PULSE_POLICY_VERSION=daily-store-pulse-v1
DAILY_STORE_PULSE_RENDER_VERSION=daily-store-pulse-v2
```

`DAILY_STORE_PULSE_ENABLED=true` допустим только вместе с включёнными Telegram notifications и
fanout; startup readiness отклоняет противоречивую конфигурацию. Изменение бизнес-правил требует
новой policy version, изменение текста без изменения payload — новой render version.

## Наблюдаемость

- `storeanalytics.notification.daily.pulse.plans{outcome=created|existing|failed}`;
- `storeanalytics.notification.daily.pulse.last.event.timestamp`;
- общий `storeanalytics.notification.fanout.total`;
- Telegram delivery state/counters и операторская панель доставок.

Правила: `monitoring/prometheus/daily-store-pulse-alerts.yml`. Alert `NotCreated` подключается только
в окружениях, где функция включена и есть активный магазин. Технический alert идёт разработчику;
руководитель не получает сообщение о внутреннем сбое.

## Проверка перед включением

1. Установить timezone магазина и проверить переход календарной даты.
2. Завершить SALES и RETURNS sync за вчера.
3. В staging временно выставить безопасное окно и включить planner.
4. Убедиться, что создано одно событие и один delivery на получателя.
5. Повторить planner и подтвердить отсутствие дубля.
6. Проверить blocked subscription, quiet hours, expiry и manual resend.
7. Вернуть production-окно, сохранить policy/render versions в release manifest.
