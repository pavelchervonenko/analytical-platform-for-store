---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - operator
last_verified: 2026-10-01
requirement_sources:
  - docs/current/product/classification.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/product/service/CatalogProductReviewQueueService.java
  - backend/src/main/java/com/storeanalytics/product/service/CatalogProductReviewDecisionService.java
  - backend/src/main/java/com/storeanalytics/product/web/CatalogProductReviewController.java
  - backend/src/main/java/com/storeanalytics/product/web/CatalogProductReviewDecisionController.java
  - backend/src/main/java/com/storeanalytics/product/web/CatalogReviewCategoriesController.java
  - frontend/src/admin/CatalogProductReviewPanel.tsx
verification_sources:
  - backend/src/test/java/com/storeanalytics/sync/service/CatalogRoleSyncIntegrationTest.java
  - frontend/e2e/visual-local.spec.ts
runtime_evidence: []
required_reviewers:
  - backend
  - product
  - security
review_triggers:
  - category-change
  - payroll-change
  - manager-permission-change
supersedes: []
superseded_by: null
---

# Проверка нового проданного товара

Это локально реализованный, **ещё не выпущенный** поток для ADMIN и MANAGER. Он не заменяет
массовое применение согласованных назначений для карточек, существовавших до даты T.

OpenAPI v14 исправляет описание параметров без изменения runtime endpoint:
`productId` — обязательный path UUID; query-параметр очереди называется `limit`, а не `arg0`.
Предыдущая baseline сохранена. Клиенты должны генерировать типы из текущего контракта.

`GET /api/admin/catalog-product-reviews?limit=100` возвращает первые 1–500 новых
товаров LiveSklad, созданных не раньше зафиксированной даты T и проданных не раньше T,
у которых остались `UNMAPPED`-позиции либо отсутствует явное аналитическое или
зарплатное назначение на первую продажу. Для уже назначенной аналитической категории
ответ также содержит сохранённое состояние товара. Ответ содержит T, `hasMore`, внутренний UUID,
внешний ID, код, имя, тип/группу источника, версию карточки, первую продажу и подсказку
автоклассификатора. Подсказка не является назначением. Список глобальный для каталога
двух магазинов, не выбирается по магазину в верхнем переключателе. Если T ещё не
зафиксирована, список пуст с `activationFrom=null`.

`GET /api/admin/catalog-product-reviews/categories` возвращает активные аналитические
категории без `UNMAPPED`: код, название, зарплатную категорию по умолчанию **для справки**.
Зарплатный выбор пользователь подтверждает отдельно; default не записывается автоматически.

`POST /api/admin/catalog-product-reviews/{productId}/decision` принимает:

```json
{
  "expectedProductVersion": 0,
  "analyticsCategoryCode": "HEADPHONES_APPLE",
  "conditionType": "NOT_APPLICABLE",
  "payrollCategoryCode": "TECH_TIER_2",
  "reason": "Проверены модель и группа источника"
}
```

Это **первичный разбор** новой карточки, не API для исправления уже принятого
решения. Сервер сериализует его по строке товара, проверяет версию, активную категорию,
отсутствие конфликтующей истории назначений и наличие продажи. Если из двух назначений
уже есть одно действующее на первую продажу, его категорию и состояние менять нельзя:
API добавляет только недостающее назначение; несколько исторических интервалов
отправляются на отдельную корректировку. `UNMAPPED` нельзя подтвердить как
зарплатную роль. T обязана быть полуночью `Europe/Kaliningrad`.
Новое аналитическое назначение действует с T, зарплатное — с бизнес-даты T,
а не с первой увиденной продажи. Поэтому позднее загруженная продажа после T
не остаётся без назначения; позиции до T не переопределяются. Если в затронутом магазине есть
утверждённая либо выплаченная зарплатная ведомость за этот или последующий период,
запись отклоняется для отдельной корректировки. В одной транзакции сохраняются оба
назначения и аудит, затем переклассифицируются активные `UNMAPPED`-строки этого товара
в пределах connection. Никакие денежные поля не меняются. Ответ даёт число
переклассифицированных строк (включая связанные возвраты) и `affectedStoreIds`.
Уже классифицированные продажи этот endpoint не пересматривает. Связанные возвраты наследуют исходную категорию;
старые независимые return строки этим endpoint не переписываются. Если за затронутый
месяц уже есть ведомость в статусе `CALCULATED`, её source fingerprint станет
`STALE`; до утверждения потребуется обычный пересчёт ведомости. Методика и ставки
этот endpoint не меняет и ведомость автоматически не пересчитывает.

Для аксессуаров ранее захваченный immutable sale-role snapshot может стать `STALE`
после смены категории; такие продажи выводятся в существующую очередь проверки
attach-rate. Не следует автоматически считать подсказку подтверждением совместимости.

Администратор открывает вкладку `Настройки → Новые товары`; руководитель — отдельный
раздел `Новые товары` в меню управления. Очередь глобальна для общего каталога двух
магазинов: подтверждение руководителя действует на обе точки. Это явное решение владельца
от 2026-10-01. Исключение в security matcher распространяется только на GET очереди,
GET списка категорий и POST решения; остальные `/api/admin/**`
остаются ADMIN-only. Каждый выбор фиксируется с actor ID и основанием.

Локальная проверка: integration test на PostgreSQL и synthetic visual desktop/tablet/mobile.
Проверка на живых данных, полный Gradle gate и выпускная репетиция остаются отдельными воротами.
