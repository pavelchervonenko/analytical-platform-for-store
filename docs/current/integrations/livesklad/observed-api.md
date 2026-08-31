---
doc_schema: 1
doc_type: current
status: current
owner: integrations
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/archive/legacy-contracts/livesklad-api-docs.md
implementation_sources:
  - scripts/livesklad-discovery
  - backend/src/main/java/com/storeanalytics/integration/livesklad/client
  - backend/src/test/resources/fixtures/livesklad
verification_sources:
  - backend/src/test/java/com/storeanalytics/integration/livesklad/client/HttpLiveSkladClientTest.java
runtime_evidence: []
required_reviewers:
  - integration
  - security-privacy
review_triggers:
  - provider-change
  - livesklad-client-change
  - fixture-change
supersedes:
  - docs/archive/legacy-contracts/livesklad-api-docs.md
superseded_by: null
---

# Наблюдаемый API LiveSklad

## Статус знаний

Это датированный профиль поведения одного доступного account, а не нормативная спецификация
LiveSklad. Fixtures доказывают parser/validation нашего клиента, но не гарантируют, что provider не
изменил shape или semantics. Новое наблюдение фиксирует дату, размер выборки и sanitized field
shape; production payload, credentials и персональные данные в репозиторий не попадают.

## Подтверждённые наблюдения

Read-only discovery в июле 2026 подтвердил:

| Область | Наблюдавшийся контракт | Уверенность |
|---|---|---|
| Auth | `POST /auth`, token без `Bearer`, TTL и request budget в envelope | Высокая для исследованного account |
| Stores | `GET /shops`, stable string IDs, timezone отсутствует | Высокая |
| Employees | IDs company-wide, membership many-to-many, role endpoints не различили роли | Средняя |
| Sales | Date filter, pagination, list/detail split, optional counteragent/node | Высокая |
| Orders | Company list, detail, type/status relations, sparse role-specific employees | Средняя |
| Cash/returns | Cash transactions позволяют найти sale return, detail связывает parent/item | Высокая для исследованной выборки |

Provider request budget наблюдался как общий `remainRequest`/`expireDate`; client обязан
обрабатывать изменение окна и не считать счётчик монотонным. Полные upstream body и access token
не логируются.

## Используемые endpoints

Клиент использует auth, shops/employees, sales, document detail, company orders/order detail,
statuses/type-orders, cash registers/cash transactions и cash items. Browser не обращается к ним
напрямую.

## Неразрешённые предположения

- Полного product/category catalog endpoint не найдено; `nomenclatureId` известен из positions.
- Zero purchase price наблюдается, но сам по себе не доказывает «услугу» или корректную cost.
- Некоторые nested order employee/cash fields профилированы не полностью.
- Collection endpoint всех sale returns не найден; polling discoverability зависит от cash feed,
  overlap и webhook.
- Для реального `ORDER_RETURN` требуется датированное evidence, что scalar `data.id` является
  order ID, пригодным для order-detail API. Код следует этому контракту, но provider guarantee не
  утверждается.
- Retroactive corrections требуют overlap; наличие `dateChange` неполно.

Любое новое поле сначала проходит sanitized discovery и fixture/client test. Наблюдение одного
account не превращается в provider-wide guarantee формулировкой документа.
