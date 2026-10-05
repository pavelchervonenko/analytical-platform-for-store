---
doc_schema: 1
doc_type: decision
status: accepted
owner: product
audience:
  - developer
  - operations
decision_date: 2026-10-04
implementation_status: partial
decision_sources:
  - docs/maintenance/weekly-ai-production-automation-plan.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiJobStore.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationStore.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationRunner.java
  - backend/src/main/resources/db/migration/V96__add_seller_weekly_preparation_backlog.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiJobStoreIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationStoreIntegrationTest.java
required_reviewers:
  - product
  - backend
  - operations
supersedes: []
superseded_by: null
---

# ADR-0005: Начало истории и необходимые повторы недельного ИИ

## Источник решения

Ответ владельца в чате разработки от 4 октября: история состава начинается с момента включения,
без восстановления задним числом; по словам владельца, состав пока не менялся. Владелец разрешил
необходимые ИИ-повторы, не назначил коммерческий предел в рублях и указал приоритет точности,
качества и полезности. Вопрос относился к настройке будущего автоматического режима, не к
немедленному запуску платного запроса или production deploy.

## Решение

1. Baseline записывается при отдельном согласованном cutover для каждого магазина. Его timestamp
   нельзя подставить задним числом из текущих назначений. Подтверждение неизменного состава
   позволяет первый ручной обзор по действующей current-roster модели, но не превращает её
   в доказанную temporal history.
2. Для исторически точного сравнения baseline должен покрывать обе сравниваемые недели.
   Более ранние исходные продажи связанных возвратов также остаются UNKNOWN, если их membership
   нельзя доказать; ожидание двух недель само по себе не устраняет этот случай.
3. Одна автоматическая задача на store/week остаётся инвариантом ADR-0004. Пункт 5 ADR-0004
   уточняется: необходимый повтор провайдера допустим внутри ограниченного retry policy;
   абсолютный запрет второго вызова больше не является продуктовым требованием.
4. Не создавать неограниченный цикл вызовов. Разделять бесплатную повторную подготовку при
   изменении источника, retryable отказ провайдера, отклонённый semantic validation ответ и
   UNKNOWN outcome. При UNKNOWN сначала сохраняется расход/статус и проводится диагностика,
   а не безусловный автоматический платный повтор. Конкретный retry cap проверяется перед rollout.
5. Отсутствие коммерческого денежного предела не отменяет технические circuit breakers,
   атомарное резервирование и учёт фактических/неизвестных расходов. Действующие защитные лимиты
   не удаляются этим ADR и не считаются изменёнными на production. Перед включением согласовать
   конкретную runtime-конфигурацию, максимальную стоимость одного запроса и наблюдаемость.

## Состояние реализации и проверка

Это согласованный продуктовый контракт, не подтверждение работы автоматического режима.
Локальный candidate сохраняет один automatic job и поддерживает ограниченные attempts по
конфигурации с запретом UNKNOWN retry. Temporal aggregates и dormant preparation backlog
реализованы отдельными внутренними компонентами. Historical identity, opt-in snapshot writer и
бесплатный runner уже соединены; additive public period read и бесплатный exact period planner
реализованы отдельно. Исторический freshness guard не подставляет новую revision вместо одобренной.
Подключение backlog к scheduler/automatic AI и atomic paid publication fence ещё требуются.
Discovery начинается лишь с первой недели, чья предыдущая
полностью покрыта явным baseline, не с даты старого manual canary. Source/history ожидания
бесплатны и отложены; техническая ошибка подготовки terminal, а не бесконечный busy retry.
Полный release gate не завершён. Baseline не объявляется активированным этим документом.
Проверки перечислены в [плане](../maintenance/weekly-ai-production-automation-plan.md).

Уточнение от 5 октября: [ADR-0006](ADR-0006-livesklad-return-employee-analytics.md) меняет
аналитического автора возврата и его membership timestamp. Условие пункта 2 о более ранней
исходной продаже сохраняется для проекций, которым действительно нужен оригинал, например
гарантийных правил; аналитический автор возврата не наследуется из оригинала.
