---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - manager
last_verified: 2026-09-22
requirement_sources:
  - docs/archive/legacy-contracts/attach-rate-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/warranty/WarrantyController.java
  - backend/src/main/resources/db/migration/V38__attach_rate_units_methodology.sql
  - backend/src/main/java/com/storeanalytics/metrics/service/AttachRateService.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics/repository/AttachRateIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/AttachRateServiceTest.java
  - backend/src/test/java/com/storeanalytics/metrics/web/AttachRateControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - product-formula
review_triggers:
  - attach-rate-methodology-change
  - attach-rate-category-change
  - return-employee-attribution-change
supersedes:
  - docs/archive/legacy-contracts/attach-rate-api.md
superseded_by: null
---

# Attach-rate API

`GET /api/stores/{storeId}/kpi/attach-rates?periodStart=...&periodEnd=...` возвращает поштучную
методику v3 или v4 согласно `formulaVersion` и настройке атрибуции.

```text
N = sold units add-on - returned units add-on
B = sold units relevant device - returned units relevant device
attachRate = max(0, N) / B × 100%, если B > 0
attachRate = null, если B <= 0
```

Обычные допы и техника учитываются независимо от совместного присутствия в одном чеке.
В v4 обычная гарантия следует связанной продаже устройства. Возвраты уменьшают
числитель/базу по signed facts. Store benchmark использует все facts магазина; employee rows могут
использовать отдельный отображаемый roster, поэтому остаток «вне рейтинга / без сотрудника» должен
быть видим.

Действующие направления и bases:

| Направление | База |
|---|---|
| Чехлы/стекло/камера iPhone | iPhone |
| Зарядки, кабели и плёнка телефона | Все телефоны |
| Настройка | Телефоны + MacBook + PlayStation |
| Чехлы/стекло/камера Samsung | Новые и Б/У Samsung |
| AirPods/Watch accessories — общая сводка | AirPods + Apple Watch, с legacy-остатком |
| ACCESSORY_AIRPODS | AirPods без EarPods; denominatorCode=AIRPODS |
| ACCESSORY_APPLE_WATCH | Apple Watch; denominatorCode=APPLE_WATCH |
| iPad accessories | iPad |
| Гарантия Б/У | Б/У iPhone + Samsung |
| Гарантия новых | Новые/ASIS iPhone + новые Samsung |
| Премиум-протекция | Согласованный набор техники без Dyson |

Premium protection numerator ограничен классифицированными Care-продуктами, зафиксированными
версией methodology. Изменение списка — versioned product/formula change, а не UI-фильтр.

В v4 rates[].preliminary отмечает неразрешённую гарантийную атрибуцию.
Обе catalog-проекции также отмечают preliminary у метрик, затронутых открытой
проверкой роли зарядки/плёнки/AirPods/Watch. Возврат с неизвестным автором
распространяет этот потенциальный риск на SELLERS, не добавляя ему количества.
Подтверждённый возврат и ожидающая проверки роль не смешиваются в quality-count.
В обоих режимах у двух дочерних метрик AirPods/Watch он также отмечает неуточнённые
аксессуары прежней смешанной категории. Дочерние метрики справочные и никогда не
добавляют рейтинг-вес; их исходные N/B доступны и при B <= 0. Общая сводка содержит
legacy-остаток: клиент показывает разность N/B сводки и двух детей, не усредняет проценты.

`dataQuality.unassignedReturnItemCount` — строки остальных возвратов без сотрудника источника.
`ambiguousWarrantyItemCount` остаётся совместимым счётчиком активных конфликтных строк;
количества и документы находятся в отдельной очереди. `EmployeeAttachRatingEntry.attributionIncomplete`
объясняет исключение метрики из оценки, отличая его от малой базы.

## Разбор гарантий

Базовый путь: `/api/stores/{storeId}/attach-rate/warranties`.
Администратор и руководитель своего магазина; отдельные PLAN/SHIFTS/PAYROLL-права не нужны.

| Метод / путь | Назначение |
|---|---|
| GET базовый | Очередь: `state=OPEN/RESOLVED/WARNINGS/ALL`, `offset`, `limit` 1–100 |
| GET `/devices?query=` | Поиск подходящих устройств своего магазина, 2–150 символов, до 50 результатов |
| GET `/originals?query=` | Поиск исходной гарантии для возврата, те же ограничения |
| GET `/{sourceId}` | Источник, кандидаты, распределение, последние 100 ревизий, предупреждения; сильный ETag |
| POST `/{sourceId}/preview` | Проверка решения и затронутых дат без записи |
| POST `/{sourceId}/decisions` | Атомарное сохранение новой ревизии |

Тело preview/decisions: `action` (ALLOCATE/EXCLUDE/DEFER), непустая `reason` до 1000 символов,
`originalWarrantyItemId` для найденного оригинала возврата, `allocations` до 100 строк
`{deviceItemId, quantity, fingerprint}`. Для EXCLUDE/DEFER allocations пусты. Сумма ALLOCATE
совпадает с количеством источника; положительные количества до трёх десятичных знаков.

Запись требует `If-Match` и `Idempotency-Key`; без версии — 428, устаревшая версия источника
или устройства — 412. Повтор того же запроса/ключа возвращает первое решение, другая команда
с тем же ключом — конфликт. Параллельные решения магазина сериализованы; полнота распределения
проверяется также отложенным ограничением БД. История и аудит записываются в одной транзакции.
При выключенной новой методике решения недоступны для записи, queue сообщает `enabled=false`.

Ошибки руководителя имеют стабильные коды WARRANTY_ORIGINAL_UNRESOLVED,
WARRANTY_RETURN_EXCEEDS_ALLOCATION, WARRANTY_DEVICE_DATE_INVALID и WARRANTY_CASE_NOT_FOUND.
Показатели и финансовая атрибуция описаны в [product contract](../product/attach-rate.md).

## Разбор совместимости аксессуаров

Базовый путь: `/api/stores/{storeId}/attach-rate/cases`. Доступ ограничен своим
магазином так же, как у очереди гарантий. Очередь содержит строки продаж
`OTHER_CASE`, `CASE_UNIVERSAL`, `GLASS_PHONE_UNRESOLVED`, требующие проверки плёнки
и CHARGER_CABLE/ACCESSORY_AIRPODS/ACCESSORY_APPLE_WATCH со спорной ролью, а не товарные
карточки целиком. Поле `categoryCode` описывает тип аксессуара; `allowedTargets` содержит
допустимые цели из единой SQL-политики. Сервис проверяет этот список, интерфейс отображает
его, а триггер БД повторно проверяет ту же политику при записи.

| Метод / путь | Назначение |
|---|---|
| GET базовый | Очередь `state=OPEN/RESOLVED/ALL`, `offset`, `limit` 1–100; количество открытых и конфликтов |
| GET `/estimates?periodStart=...&periodEnd=...` | Подтверждённые и предположительные количества/проценты отдельно; период до 366 дней |
| GET `/{sourceId}` | Продажа, подсказка по чеку, ревизии, затронутые даты; сильный ETag |
| POST `/{sourceId}/preview` | Проверка решения, нетто-количества и затронутых дат без записи |
| POST `/{sourceId}/decisions` | Новая неизменяемая ревизия с аудитом |

При переносе продажи в другой магазин прежние цель и причина решения не выдаются
новому магазину. Номер следующей ревизии остаётся глобальным для строки, чтобы
новый магазин мог принять своё решение без конфликта уникальности.
Ответ estimates также содержит `unresolvedReturnCount`: число возвратных строк
поддерживаемых типов очереди за период, для которых не подтверждена точная связь с
исходной продажей своего магазина. Проверяются исходная строка и документ, товар,
подключение, магазин и порядок дат; несовпадение тоже увеличивает предупреждение.
Те же условия применяются к нетто-количеству preview, затронутым датам и estimates.
Предположительные количества и конфликтные счётчики estimates по-прежнему относятся только к чехлам.

Тело preview/decisions: `targetCode` и непустая `reason` до 1000 символов.

- Чехлы: `CASE_APPLE_IPHONE`, `CASE_SAMSUNG`, `CASE_OTHER_DEVICE`.
- Неопределённые экранные стёкла: `GLASS_IPHONE`, `GLASS_SAMSUNG`, `GLASS_OTHER`.
- Нейтральные плёнки: `FILM_PHONE`, `FILM_NON_PHONE`.
- Зарядка со спорной ролью: CHARGER_CABLE, ACCESSORY_APPLE_WATCH, NO_ATTACH.
- Аксессуар AirPods/Watch со спорной ролью: собственный N-код либо NO_ATTACH.
- Любой из этих типов: DEFER оставляет строку на проверке без подтверждённого N.

CURRENT-автоматическая плёнка не требует подтверждения каждой продажи.
Изменение исходных полей делает прежнее per-sale решение неактуальным.

Цель чужого типа отклоняется сервисом и ограничением БД. Решение не меняет денежную категорию.
Известные брендовые/другие стёкла в эту очередь не включаются. `FILM_NON_PHONE` —
цель проверки, не новая аналитическая категория.
Запись требует `If-Match` и `Idempotency-Key`; устаревший ETag даёт 412,
отсутствующий — 428. Решение для одного чека не меняет категорию всей
карточки LiveSklad. Подсказка по соседнему телефону не становится официальным
attach-rate без ручного подтверждения совместимости. Связанные возвраты
наследуют решение исходной строки; несвязанные не угадываются.
После записи live-показатели перечитываются и инвалидируются зависимые
текущие обзоры; опубликованные снимки остаются неизменными.
