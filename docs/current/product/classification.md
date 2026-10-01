---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-10-01
requirement_sources:
  - docs/archive/discoveries/analytics-business-rules-draft.md
  - docs/history/audits/2026/08/payroll-classification-review.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/common/database/CatalogActivationState.java
  - backend/src/main/resources/db/migration/V89__store_catalog_activation_boundary.sql
  - backend/src/main/java/com/storeanalytics/product/service/CatalogClassificationCutover.java
  - backend/src/main/java/com/storeanalytics/product/service/LegacyProductClassificationRulesV9.java
  - backend/src/main/java/com/storeanalytics/sync/service/SalesSyncPersistence.java
  - backend/src/main/java/com/storeanalytics/product/service/CatalogSaleRoleSnapshotWriter.java
  - backend/src/main/java/com/storeanalytics/product/service/CatalogLegacyCompatibilityEvidence.java
  - backend/src/main/resources/db/migration/V82__add_shadow_catalog_sale_role_snapshots.sql
  - backend/src/main/resources/db/migration/V84__seed_approved_catalog_detail_categories.sql
  - backend/src/main/java/com/storeanalytics/product/service/CatalogCompatibilityService.java
  - backend/src/main/java/com/storeanalytics/product/service/CatalogCompatibilityAuthorizer.java
  - backend/src/main/java/com/storeanalytics/product/service/CatalogCompatibilityProjectionService.java
  - backend/src/main/java/com/storeanalytics/product/repository/CatalogCompatibilityRepository.java
  - backend/src/main/resources/db/migration/V81__store_catalog_compatibility_confirmations.sql
  - backend/src/main/java/com/storeanalytics/product/model/CatalogCompatibilityEvidence.java
  - backend/src/main/java/com/storeanalytics/product/service/CatalogAccessoryAttachPolicy.java
  - scripts/catalog-audit/probe_accessory_roles.py
  - backend/src/main/resources/catalog/category-registry-v2.tsv
  - backend/src/main/java/com/storeanalytics/product/service/CatalogCategoryRegistry.java
  - backend/src/main/java/com/storeanalytics/product/service/ProductAutoClassificationDecision.java
  - scripts/catalog-audit/catalog_registry.py
  - scripts/catalog-audit/catalog_lexicon.json
  - scripts/catalog-audit/lexicon_audit.py
  - scripts/catalog-audit/prepare_group_import.py
  - backend/src/main/java/com/storeanalytics/product/model/Product.java
  - backend/src/main/java/com/storeanalytics/product/model/ProductDetails.java
  - backend/src/main/java/com/storeanalytics/product/service/CatalogDeviceCategoryPolicy.java
  - backend/src/main/java/com/storeanalytics/product/service/CatalogDeviceDetailProposer.java
  - backend/src/main/java/com/storeanalytics/product/model/AnalyticsCategory.java
  - backend/src/main/java/com/storeanalytics/product/service/ProductAutoClassificationRuleEngine.java
  - backend/src/main/java/com/storeanalytics/product/service/ProductCategoryImportService.java
  - backend/src/main/java/com/storeanalytics/product/service/ProductClassificationReconciliationService.java
  - backend/src/main/java/com/storeanalytics/product/service/CatalogProductReviewQueueService.java
  - backend/src/main/java/com/storeanalytics/product/service/CatalogProductReviewDecisionService.java
  - frontend/src/admin/CatalogProductReviewPanel.tsx
  - backend/src/main/java/com/storeanalytics/product/service/ProductClassificationResolver.java
  - backend/src/main/resources/db/migration/V38__attach_rate_units_methodology.sql
  - backend/src/main/resources/db/migration/V5__add_payroll.sql
  - backend/src/main/resources/db/migration/V56__split_speaker_category.sql
  - backend/src/main/resources/db/migration/V57__split_fitness_wearable_category.sql
  - backend/src/main/resources/db/migration/V58__split_smart_glasses_category.sql
  - backend/src/main/resources/db/migration/V59__split_camera_category.sql
  - backend/src/main/resources/db/migration/V60__classify_setup_products.sql
  - backend/src/main/resources/db/migration/V61__split_hair_styler_category.sql
  - backend/src/main/resources/db/migration/V62__split_headphone_categories.sql
  - backend/src/main/resources/db/migration/V63__correct_iphone_camera_glass.sql
  - backend/src/main/resources/db/migration/V64__correct_x_crystal_iphone_cases.sql
  - backend/src/main/resources/db/migration/V65__split_ipad_mac_and_other_device_cases.sql
  - backend/src/main/resources/db/migration/V66__correct_mago_pro_iphone_cases.sql
  - backend/src/main/resources/db/migration/V67__correct_mago_15_16_iphone_cases.sql
  - backend/src/main/resources/db/migration/V68__correct_remaining_model_named_keephone_iphone_cases.sql
  - backend/src/main/resources/db/migration/V69__classify_explicit_iphone_cases.sql
  - backend/src/main/resources/db/migration/V70__add_unresolved_phone_case_category.sql
  - backend/src/main/resources/db/migration/V74__correct_confirmed_charging_products.sql
  - backend/src/main/resources/db/migration/V75__split_power_bank_category_and_attach_rate.sql
  - backend/src/main/resources/db/migration/V76__classify_hubs_and_adapters.sql
  - backend/src/main/resources/db/migration/V77__classify_charging_stations_and_trackers.sql
  - frontend/src/admin/CategoryImportPanel.tsx
  - frontend/src/admin/ClassificationPanel.tsx
verification_sources:
  - backend/src/test/java/com/storeanalytics/product/service/CatalogClassificationCutoverTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/CatalogRoleSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogSaleRoleSnapshotIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/CatalogCategoryRegistryDatabaseIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogLegacyCompatibilityEvidenceTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogCompatibilityPersistenceIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogCompatibilityServiceTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogCompatibilityAuthorizerTest.java
  - backend/src/test/java/com/storeanalytics/product/model/CatalogCompatibilityEvidenceTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogAccessoryAttachPolicyTest.java
  - scripts/tests/test_catalog_accessory_roles.py
  - scripts/tests/test_catalog_registry.py
  - backend/src/test/java/com/storeanalytics/product/service/CatalogCategoryRegistryTest.java
  - scripts/tests/test_catalog_lexicon.py
  - scripts/tests/test_catalog_review_profile.py
  - scripts/tests/test_group_import_preview.py
  - backend/src/test/java/com/storeanalytics/product/model/ProductSourceGroupObservationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogDeviceDetailProposerTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogDeviceDetailAmbiguityTest.java
  - backend/src/test/java/com/storeanalytics/product/service/ProductAutoClassificationRuleEngineTest.java
  - backend/src/test/java/com/storeanalytics/product/service/DeviceAnnotationAutoClassificationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/PhoneAccessoryTargetAutoClassificationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/ProductCategoryImportIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/ProductClassificationResolverTest.java
  - backend/src/test/java/com/storeanalytics/product/service/ProductClassificationReconciliationServiceTest.java
  - backend/src/test/java/com/storeanalytics/common/database/CareClassificationMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/SpeakerCategoryMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/FitnessWearableCategoryMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/SmartGlassesCategoryMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/CameraCategoryMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/SetupProductsMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/HairStylerCategoryMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/HairStylerAutoClassificationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/HeadphoneCategoryMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/HeadphoneAutoClassificationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/CameraGlassCorrectionMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CameraGlassAutoClassificationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/XCrystalIphoneCaseMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/XCrystalIphoneCaseAutoClassificationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/DeviceAccessorySplitMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/MagoProIphoneCaseMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/MagoProIphoneCaseAutoClassificationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/Mago1516IphoneCaseMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/Mago1516IphoneCaseAutoClassificationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/RemainingKeephoneIphoneCaseMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/KeephoneNamedIphoneCaseAutoClassificationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/ExplicitIphoneCaseMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/ExplicitIphoneCaseAutoClassificationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/ChargingProductsMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/PowerBankMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/AdapterProductsMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/ChargingStationsAndTrackersMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/DeviceAccessorySplitAutoClassificationTest.java
  - frontend/src/quality/actions.test.ts
runtime_evidence: []
required_reviewers:
  - product
  - backend
review_triggers:
  - classification-change
  - metric-change
  - payroll-change
supersedes: []
superseded_by: null
---

# Классификация товаров

Один товар участвует в трёх независимых проекциях.

**Статус массовых исправлений:** после решения применять новую классификацию только
с даты обновления SQL-черновики V56–V70 и V74–V77 больше не переписывают старые
карточки, назначения, продажи или возвраты. Описания отдельных товарных блоков
ниже фиксируют согласованную целевую категорию; прежние фразы о переносе
«сохранённых продаж» относятся к историческому проекту изменений и не являются
действием нынешней цепочки миграций. Для фактического применения нужны точные
датированные назначения. Это предупреждение не распространяется на независимые
решения по конкретным продажам в очереди проверок.

## Перспективная граница обычной синхронизации — локальный этап

`app.catalog-classification.activate-from` принимает явный ISO instant с timezone/offset.
Дата без времени/offset недопустима; точность ограничена микросекундами БД.
Время запуска приложения или загрузки не используется.
Точное значение для выпуска ещё не выбрано. T должна приходиться на 00:00
бизнес-зоны `Europe/Kaliningrad`: зарплатное назначение имеет точность одного дня,
поэтому внутридневная T может затронуть продажи до обновления. Migration runner однократно
фиксирует явную будущую дату в `catalog_classification_activation` после structural migration. Повтор с той же датой
допустим, UPDATE/DELETE/TRUNCATE записи запрещены. Runtime только читает запись при старте:
другая дата, пропавшая настройка при существующей записи или настройка без записи — ошибка.
При включённом capture его дата должна совпадать с датой классификации.
Без записи и без настройки сохраняется прежний development-режим рабочей копии;
**это не безопасная настройка для prospective production rollout**. Production Compose передаёт
одну явную дату мигратору, API и worker; preflight требует совпадающую дату захвата снимков
и запас более часа до включения. До репетиции на восстановленной копии guard остаётся обязательным.
Подробности — [контракт миграций](../architecture/migrations.md).

При заданной границе T:

- Для повторного обычного sync продажи с `occurredAt < T` и тем же product UUID сохраняются
  записанные category, assignment, classification version и condition, включая UNMAPPED.
  Имя/денежные поля CRM продолжают проходить обычный update; это не заморозка самой продажи.
  Прямые команды ручной переклассификации не являются обычным sync и не разрешаются этим шагом.
- Для впервые пришедшей старой позиции сначала ищется назначение, действующее на дату события.
  Если его нет, применяется замороженный fallback `LegacyProductClassificationRulesV9`,
  восстановленный из исходного кода прежнего выпуска, а не новый словарь.
  Retired historical category разрешена для такого fallback; её код не заменяется новым автоматически.
- На границе `occurredAt = T` и позже действуют обычные эффективные назначения и текущий
  классификатор. Неактивные автоматические цели по-прежнему не назначаются.
- Замена самого товара в CRM (другой product UUID) не наследует категорию чужой позиции:
  применяется resolver по дате события. Это не основание переносить старое назначение на новый товар.
- Связанный возврат наследует сохранённую классификацию исходной продажи через существующий
  return sync. Если исходная позиция отсутствует, поздняя привязка/роль возврата требуют
  отдельной проверки; данный этап не вводит нового предположения об исходной категории.

После T товар, впервые созданный в локальном каталоге на T или позже, без явного назначения
не получает категорию по словарю автоматически: продажа остаётся `UNMAPPED`, а алгоритм даёт
только подсказку в очереди `Настройки → Новые товары`. Для подтверждения нужны обе независимые
категории, состояние товара и причина. Операция ADMIN-only, с проверкой версии карточки и
утверждённых/оплаченных ведомостей; аналитическое назначение, зарплатное назначение, аудит
и повторная классификация активных `UNMAPPED`-продаж сохраняются одной транзакцией.
Аналитическое назначение начинается в T, зарплатное — с бизнес-даты T, не с первой
увиденной продажи: позднее загруженные продажи после T получают те же роли. Продажи
до T сохраняют прежнюю аналитическую и зарплатную классификацию. Суммы продаж не меняются. Если старый неизменяемый снимок роли аксессуара стал неактуальным,
соответствующая продажа попадает в уже существующую проверку attach-rate. Роль MANAGER пока
не может назначать общую карточку, потенциально влияющую на оба магазина; правило полномочий
для такой операции требует отдельного решения. Для уже существовавших до T карточек эта
очередь не заменяет выпускной точный реестр назначений.

Замороженный fallback нельзя «улучшать» вместе с новым словарём. Он воспроизводит прежние
правила на доступном имени товара, но не восстанавливает неизвестное старое имя/группу CRM.

Локально проверены 13 интеграционных сценариев реального sales/return sync на PostgreSQL:
граница T, новые и старые товары, связанные возвраты, частично заполненные назначения
и одна карточка в двух магазинах. Отдельно проверены API-контракт и локальный frontend.
Это диагностический javac/JUnit, не полный Gradle release gate.

**Ещё не закрыто:** точная дата T на выпуск, свежий реестр назначений для существующих
карточек, полноценная репетиция на восстановленной копии БД с API/worker и новыми метриками,
проверка всех orphan/relink-сценариев и полный Gradle gate. Передача даты T всем runtime-ролям
локально подключена, но production не обновлён. Миграционный guard пока блокирует
заполненную БД и не должен отключаться без этих ворот.

## Аналитическая категория

Определяет участие в store/category/employee KPI, kind
`DEVICE|ACCESSORY|SERVICE|WARRANTY|PROTECTION` и группы. Отсутствие assignment — `UNMAPPED`:
товар входит в store revenue, но не в группы. `EXCLUDE` исключает из аналитики.

Для source type `PRODUCT` перед коммерческими правилами действует узкое исключение:
если название начинается с модели iPhone/айфон или MacBook Air/Pro с диагональю (допустим
префикс Apple) и содержит объём памяти (`128GB`, `16/512` и т. п.), конечная пометка «ремонт»/`repair` либо «брак по гарантии [лежит]»
не превращает устройство в услугу. Категория и состояние определяются по названию без этой
пометки. Исключение не применяется к `SERVICE`/`UNKNOWN`, названиям работ, распознанным
аксессуарам, коммерческим услугам в оставшейся части имени и явным комплектам с `+`,
«комплект» или «набор». Это не универсальное правило для любых гарантийных/ремонтных текстов.
Реальные услуги сохраняют прежний приоритет; остальные неоднозначности проверяются отдельно.

Автоматическое назначение допускается только в активную категорию (`is_active=true`).
Если автоправило предложило существующую, но неактивную категорию, resolver возвращает
отсутствие классификации: обычный sync сохраняет путь UNMAPPED/проверки, а не активирует
подготовленную категорию. Отсутствующий в БД код остаётся ошибкой конфигурации.
Эффективное явное назначение имеет приоритет и продолжает читаться после деактивации
категории: запрет новых автоназначений не переписывает историю. Регрессии —
`ProductClassificationResolverTest`.

Изменение автоправил не является миграцией назначений или исторических строк: MacBook пока
использует действующую `IPAD_MAC`, а не будущую `LAPTOP_APPLE`. Ручные назначения сохраняют
приоритет. Перед развёртыванием и переклассификацией истории требуется отдельная сверка
продаж/возвратов, attach-rate и эффективных зарплатных категорий; одна правка правил не
доказывает неизменность этих показателей.

В остальных случаях автоклассификация сначала применяет более специфичные правила коммерческих услуг, например
гарантий и Care-продуктов. После них подтверждённый source type `SERVICE` имеет приоритет над
лексическими правилами товаров: работа со словами `стекло`, `камера`, `iPhone`, `клавиатура` и
подобными относится к `SETUP_SERVICE`, а не к аксессуару или устройству. Лексические правила
защитных стёкол и других товаров продолжают применяться к source type `PRODUCT`.

Для распознанного названия стекла отдельное слово «автоустановка» и его окончания
не считаются работой по установке: это характеристика физического товара. Исключение
убирает только этот маркер, а не остальные сервисные признаки. Явные «установка»/
«переустановка», в том числе в комплекте со стеклом, ремонт и source type `SERVICE`
сохраняют приоритет. Исключение не применяется к явным комплектам с `+`,
«комплект» или «набор», произвольным товарам и «автоустановке программ». Оно действует и при `UNKNOWN`, не подтверждая исходный тип.
Изменение проверено локально; назначенные категории и исторические строки не переписываются.

### Общий реестр категорий: первый блок этапа 4.2

Коды и согласованные свойства категорий описаны в одном артефакте
`backend/src/main/resources/catalog/category-registry-v2.tsv` (v1 сохранён без изменений). Его читают Java
(`CatalogCategoryRegistry`) и локальный Python-аудит (`catalog_registry.py`).
В реестре 55 кодов: целевые категории, сохраняемые legacy-коды и технические категории.
Реестр — контракт кодов и метаданных; он сам по себе не назначает товары и не включает
новую категорию в расчёты. Добавочная миграция `V84` создаёт 18 ранее отсутствовавших кодов; после неё
в БД представлены все 46 согласованных `STANDARD` кодов с их денежными
признаками, включая `GLASS_OTHER`,
`GLASS_PHONE_UNRESOLVED`, `PROTECTIVE_FILM`, `PACKAGING` и `PHONE_OTHER`. Новые 18 кодов создаются неактивными; последующая миграция активирует их для
датированных назначений. Ни одна из этих миграций не меняет назначения товаров,
строки продаж или возвратов. Отложенные сервисные коды не создаются.
`PHONE_OTHER` предназначена для самостоятельных телефонов других брендов (например, POCO),
имеет `counts_as_phone=true`, семейство `OTHER` и базовую зарплатную роль `TECH_TIER_1`.
В общих телефонных показателях и attach-rate это `OTHER_PHONE`, но не Samsung/iPhone;
упоминание бренда на чехле или зарядке не делает товар телефоном. В теневом словаре
пока распознаётся только конкретная наблюдаемая модель POCO F8 Ultra; ошибочная исходная
группа Samsung остаётся поводом для проверки. Для телефонов Samsung источник
`/SAMSUNG Б/У` (также под `/Основные`) подтверждает USED только после распознавания
самого телефона; явно противоречащее имени состояние отправляется на проверку.
Это словарное предложение, а не безусловная запись назначения в БД.
Для PS5 в `GAME_CONSOLES` SQL fallback сохраняет `TECH_TIER_1`; другая консоль
остаётся `TECH_TIER_2`. Ручные зарплатные назначения имеют приоритет, а изменения
ставок и формулы этим переходом не вводятся. Денежные флаги отражают согласованную цель; действующие
SQL-расчёты продолжают использовать свой существующий источник.

Формат — UTF-8 TSV с версией, фиксированными 13 колонками и `-` вместо пустого значения
необязательных колонок. Для каждого кода заданы имя, kind/family, денежные флаги,
граница использования `STANDARD|LEGACY|DEFERRED|TECHNICAL`, отображения типа/бренда
устройства, ключей предложений и допустимых подтверждённых функций/состояний.
Эти границы не означают состояние внедрения. `REPAIR_SERVICE` и
`DIAGNOSTICS_SERVICE` сохранены как `DEFERRED`: решение не дробить рабочий
сервис пока остаётся в силе.

Читатели отклоняют неизвестные enum/boolean, повторяющиеся коды/отображения,
повреждённую структуру и противоречащие друг другу денежные флаги. Создание
`ProductAutoClassificationDecision` проверяет наличие кода в реестре; опечатка
не должна дойти до сохранения новой продажи. Это не подтверждает наличие кода
в БД: resolver по-прежнему отдельно проверяет действующий справочник.
Неизвестное состояние товара не подменяется новым или бывшим в употреблении.

`CatalogDeviceCategoryPolicy` получает отсюда отображения планшетов, ноутбуков и
часов Apple/Samsung/других брендов. Неизвестный бренд не становится автоматически
«другим». Локальный аудит получает отсюда базовые отображения функций и категорий
и проверяет коды журнала согласований. Для `GLASS_OTHER` разрешена функция
`SCREEN_GLASS`; отсутствие целевого устройства всё равно остаётся предупреждением,
а не подтверждённой совместимостью. Остальные проверки признаков сохраняются.

В данном блоке не менялись порядок runtime-распознавания и датированный приоритет
ручных назначений. Локальные предложения, включая отложенный сервисный split,
не включены в синхронизацию продаж. Зарплатные правила, история продаж/возвратов,
attach-attribution и производные показатели не пересчитывались.
Функция товара, подтверждённая совместимость и роль в attach-rate остаются разными
понятиями: например, включение `CHARGER_CABLE` в реестр ещё не реализует исключение
для зарядки только Apple Watch.

Этап 4.2 этим не закрыт. Остаётся подключить подтверждённые признаки и роли,
согласовать runtime-потребителей с целевым справочником и legacy-историей,
расширить проверки назначений и безопасно включить новые продажи после проверки
денежных/attach/payroll-инвариантов. Лексические движки Python и Java пока не сведены
в один движок; общий реестр устраняет часть дублирования, но не заменяет эту работу.
Развитие импорта по-прежнему приостановлено.

### Подтверждённая совместимость и отдельные attach-роли: второй блок 4.2

`CatalogCompatibilityEvidence` задаёт типизированный контракт свидетельства о совместимости,
не меняя `Product`, назначение категории или сохранённую продажу. Различаются UNKNOWN,
OBSERVED, CONFIRMED и CONFLICT; полнота назначения — UNDETERMINED, EXCLUSIVE,
MULTI_DEVICE или UNIVERSAL_PHONE. Один элемент в списке наблюдений, например Apple Watch,
**не доказывает исключительное назначение**. Для подтверждения нужны непустые источник
авторства/причина, положительная ревизия, fingerprint и период действия.

Подтверждение привязано к подключению, виду PRODUCT/SERVICE, типу идентификатора
EXTERNAL_ID/CATALOG_CODE и самому идентификатору. Код Excel не выдаётся за provider ID.
Семантический fingerprint включает эту идентичность, имя и группу; цены, остатки,
время синхронизации и версия классификатора в него не входят. Для будущего runtime-пути
нужна объединённая карточка с сохранённой ранее группой, не отсутствующая группа
случайной позиции документа. Ненаблюдаемая группа и явно пустая различаются.
CONFLICT — результат оценки свидетельств, не команда стереть прежнее подтверждение.

Период — [validFrom, validTo). Этот контракт только перспективный: validFrom не может
предшествовать confirmedAt. Исправление истории требует отдельной контролируемой операции.
Контекст оценки передаёт дату и наблюдение соответствующего факта. Нельзя передавать
сегодняшнюю карточку и сегодняшнее подтверждение для пересчёта старой продажи/возврата.
Чужая идентичность, изменившийся fingerprint или неподходящая дата не активируют новую роль.

`CatalogAccessoryAttachPolicy` реализует чистую политику согласованных изменений.
Catalog SQL-проекции читают её сохранённый датированный результат, не вызывая политику заново для истории. Категория остаётся входным фактом, а результат содержит не более одной конечной роли:

- CHARGER_CABLE с действительным подтверждённым EXCLUSIVE Apple Watch даёт роль
  ACCESSORY_APPLE_WATCH, сохраняя денежную категорию. Одного чека с часами не требуется.
  При Watch-признаке без подтверждения исключительности требуется проверка товара.
  Подтверждённая многофункциональная зарядка и обычные зарядки без Watch-исключения
  возвращают DEFER_TO_EXISTING: сохранить существующую обработку, **не обнулить вклад**.
  VLP NEO не разбивается автоматически на зарядку и пауэрбанк.
- ACCESSORY_AIRPODS и ACCESSORY_APPLE_WATCH дают соответствующие дочерние роли.
  Противоречие категории и целей требует проверки. Политика предполагает, что денежная
  категория уже определена вызывающим классификатором; она не назначает её по имени.
  ACCESSORY_PODS_WATCH выдаётся только как подсказка группировки двух дочерних ролей,
  а не второй вклад той же строки. Знаковые количества и знаменатели подключаются позже.
- Для PROTECTIVE_FILM действительное подтверждённое телефонное назначение даёт FILM_PHONE,
  подтверждённое явно не-телефонное — NO_CONTRIBUTION. Смешанное phone/tablet,
  неизвестное назначение или неопределённое OTHER_DEVICE требует проверки продажи.
  OTHER_DEVICE не приравнивается к явно заданному OTHER_NON_PHONE_DEVICE.
- OTHER_CASE, CASE_UNIVERSAL и GLASS_PHONE_UNRESOLVED требуют решения конкретной продажи;
  подтверждение товарного признака само по себе не подменяет эту атрибуцию.
  SOURCE SERVICE не получает новую аксессуарную роль.
- Остальные категории, включая POWER_BANK, PACKAGING, EXCLUDE, сервисные и смешанные
  legacy-коды, возвращают DEFER_TO_EXISTING. Их прежний охват/исключения сохраняются
  вызывающей проекцией; это не универсальная новая методика для всех категорий.

Результаты REVIEW_PRODUCT/REVIEW_SALE в снимке поддерживаемого аксессуара направляют
продажу в общую очередь проверки совместимости. Хранение подтверждений, снимки,
правила применения и пересмотра описаны ниже. Объект Confirmation сам по себе не заменяет
серверную авторизацию и проверку полного scope влияния.

`probe_accessory_roles.py` запускает именно Java-политику для локального отчёта; формул
или отдельной таблицы ролей в Python нет. Адаптер сохраняет исходный уровень подтверждения
в отчёте, но не выдумывает даты, автора или EXCLUSIVE из старого списка целей.
Поэтому его предварительные REVIEW-результаты нельзя считать новым списком бизнес-вопросов:
сначала нужен перенос уже принятых решений с доказательствами. Например, подтверждённые
планшетные плёнки не требуется заново согласовывать только из-за нового формата provenance.
Старый журнал и его решения не изменяются. Существующий CONFLICT передаётся политике,
а неизвестный target отклоняется, не превращается в OTHER.

Подключение датированных ролей не меняет денежные факты, зарплатные правила или публикации.
Перенос прежних согласований в актуальную БД и общая проверка P0 остаются отдельными задачами. Этап 4.2 и интеграционные проверки 4.4–4.6 остаются открыты.

### Хранение подтверждений: третий технический блок 4.2

Добавлены неизменяемая история `catalog_compatibility_decisions` и внутренние сервисы
сохранения и чтения. Это локальная реализация, не свидетельство развёртывания или переноса
реальных согласований в БД. Публичного endpoint и интерфейса пока нет; внутренние записи
по умолчанию выключены. Существующий импорт, назначения аналитических/зарплатных категорий
и сохранённые продажи эта команда не изменяет.

- `CatalogCompatibilityService` принимает ID локальной карточки, решение, ожидаемый сильный
  ETag и ключ идемпотентности. Автор берётся из текущей аутентификации, а не из запроса.
  Поскольку каталог общий для магазинов, сейчас допускается только действующий ADMIN:
  проверяются активность, роль, отсутствие обязательной смены пароля и securityVersion.
  MANAGER одного магазина не получает неявных полномочий на общий каталог.
- Preview возвращает наблюдение карточки и последнюю ревизию; ETag связывает продукт,
  ревизию и семантический fingerprint. Сохранение блокирует карточку/группу, сверяет ETag
  и дописывает следующую ревизию. Повтор того же запроса возвращает прежний ответ без
  второй записи/аудита; изменённое тело с тем же ключом отклоняется. Права проверяются и
  перед повтором. Команда, receipt и аудит находятся в одной транзакции.
- Сохраняются подключение и его ключ, внешний ID, имя/путь группы, fingerprint, targets,
  coverage, автор, причина и происхождение решения. SQL проверяет актуальные исходные
  поля, активность карточки/подключения, полномочия автора, последовательность ревизий и
  допустимость targets/coverage. Fingerprint рассчитывает серверный репозиторий.
  Обычные UPDATE/DELETE истории запрещены; исправление и REVOKE добавляют ревизию.
- Начало действия задаёт БД в момент записи. Переданная клиентом дата не принимается;
  следующая ревизия закрывает предыдущую границу [recordedAt, validTo). REVOKE не
  возвращает старое подтверждение в действие. Изменение имени/группы/идентичности
  делает прежнее свидетельство неприменимым к новому наблюдению, не стирая историю.
- LEGACY_ADOPTION сохраняет SHA-256 исходного документа и точный ключ PRODUCT:код.
  Код сверяется с текущей карточкой внутри блокировки; внешний ID сохраняется отдельно.
  Это дата и автор **переноса сейчас**, не выдуманное авторство первоначального решения.
  Старый список целей не превращается в EXCLUSIVE/MULTI_DEVICE. Проверка содержимого
  файла реализована следующим блоком ниже; сама строка SHA-256 без исходных байтов
  не доказывает содержание или подлинность согласования.
- `CatalogCompatibilityProjectionService` читает решение по дате факта и передаёт его
  чистой политике с наблюдением именно этого факта. Отозванное решение даёт сигнал
  CONFIRMATION_REVOKED. Следующий блок подключает read-bridge только к выключенному по
  умолчанию writer первоначального снимка; SQL-метрики читают сохранённый результат. Возврат наследует снимок
  исходной продажи, а не классифицируется по текущей карточке.

Тесты используют синтетические записи в отдельном PostgreSQL: новая миграция, конкурирующие
ревизии, границы действия, отзыв, stale-наблюдение, запрет изменения истории и перенос без
ложной исключительности. Unit-тесты проверяют полномочия, отключённую команду, ETag,
идемпотентное кодирование/повтор и финансовый класс аудита. Это не сверка рабочей БД.

Следующий блок: контролируемый перенос прежних свидетельств, сохранение роли/версии в снимке
новой продажи, наследование возвратом и совместимый legacy-путь. Лишь после этого — подключение
к рабочим формулам, очередям и пересчёту с проверкой N/B, денег и неизменности зарплат.
Этап 4.2 остаётся открытым.

### Снимки новых фактов и проверяемый перенос: четвёртый блок 4.2

`CatalogSaleRoleSnapshotWriter` подключён к веткам **создания новых строк** в
SalesSyncPersistence/ReturnSyncPersistence. Сохранённые роли потребляются catalog-проекциями;
отсутствие снимка сохраняет прежнюю обработку. По умолчанию запись отключена; для включения
требуются `app.catalog-compatibility.snapshots-enabled` и явная стабильная дата начала
`app.catalog-compatibility.snapshots-from`. Без даты включённый writer не запускается.
Настройки здесь — контракт source tree, не значения production.

Writer работает в транзакции sync и перед JDBC-чтением делает JPA flush. Ошибка записи
откатывается вместе с исходной транзакцией. Отключённый writer не читает БД и не делает flush.
Существующие строки при повторном sync не получают новый снимок; записи до заданной даты,
будущие факты, не-PRODUCT продажи и неизвестные реестру категории остаются без новой проекции.
Никакого автоматического заполнения всей истории нет.

`catalog_sale_role_snapshots` сохраняет первоначальное датированное решение: исходные поля факта,
денежную категорию, версию политики/хеш реестра, outcome, максимум одну роль, причину,
ссылку на подтверждение, fingerprint и известную при приёме группу. Группа берётся из
объединённой карточки, имя — из сохранённой строки продажи. Это наблюдение при приёме,
не доказательство исторической группы из LiveSklad. Выбирается только подтверждение,
действовавшее на дату факта; более позднее решение не применяется к более ранней продаже.
Денежная категория CHARGER_CABLE при Watch-роли сохраняется.

Первоначальный снимок неизменяем, повтор не создаёт дубликат. Представление
`catalog_sale_role_snapshot_states` различает CURRENT, STALE и DELETED. Изменение
товара/документа/магазина/подключения, даты, категории/назначения/версии, имени/группы
в строке, состояния, признака работы или связи с оригиналом делает снимок STALE.
Изменение цены/количества само по себе не меняет смысл роли: расчёты берут величины
из актуальных signed-фактов. Удаление исключает факт. Позднейшее изменение
текущей карточки каталога не переписывает уже сохранённое решение старой продажи.

Возврат копирует точное решение и provenance действующего снимка исходной продажи,
включая версию политики. Проверяются оригинал, товар, магазин, подключение и порядок дат.
Отзыв подтверждения после продажи не переопределяет роль возврата. Если оригинальный снимок
отсутствует/неактуален либо классификация фактов не согласуется, сохраняется LEGACY_RETURN /
DEFER_TO_EXISTING с ORIGINAL_SNAPSHOT_UNAVAILABLE. Это **сохранение прежнего расчёта, не ноль**.
Сегодняшнее подтверждение товара не используется. Повторный sync не переводит такой возврат
автоматически в новую методику; последующий relink делает исходный снимок STALE. Изменение
классифицирующих полей оригинала также делает унаследованный снимок STALE.

STALE, REVIEW_* и неизвестная версия политики у CHARGER_CABLE, PROTECTIVE_FILM,
ACCESSORY_AIRPODS/APPLE_WATCH требуют per-sale проверки во вкладке аксессуаров.
Первоначальная роль неизменяема; ручное исправление — новая ревизия существующего
журнала решений продаж. Оно не устанавливает постоянную совместимость товара.

CURRENT/ASSIGNED поддерживаемой политики catalog-accessory-roles-v1 задаёт N;
CURRENT/NO_CONTRIBUTION не даёт N; DEFER_TO_EXISTING сохраняет прежний путь.
Актуальное per-sale решение замещает автоматическую роль продажи и точных возвратов.
Ручной DEFER оставляет проверку открытой без подтверждённого N.

Если у CURRENT-продажи обнаружен точный возврат без текущего унаследованного снимка,
продажа появляется на проверке, затронутые метрики становятся preliminary.
До явного решения CURRENT-роль продажи и legacy-путь возврата сохраняются; ручная
ревизия согласованно замещает оба. Сам LEGACY_RETURN не обновляется задним числом.
Предупреждение открытого возврата присутствует и в его собственном периоде.
Массовый backfill и активация новых категорий этим подключением не выполняются.

Для старых согласований добавлен `adoptLegacy`: принимает реальные байты журнала, ожидаемый
SHA-256 и точный PRODUCT:код. Проверяются формат, уникальность ключей (в том числе JSON-полей),
явный confirmed_compatibility и совпадение подключения/кода/исходного имени/группы с карточкой.
Нельзя передать LEGACY_ADOPTION обычной команде без документа. Верифицированное решение
проходит прежние ADMIN/ETag/idempotency/транзакционные guards; сравнение карточки происходит
под блокировкой. Автор и дата всё ещё означают перенос сейчас, не первоначальное согласование.
Hash должен быть сверён с доверенным журналом оператором: совпадение хеша не устанавливает
личность автора исходного файла. Новый публичный импорт/API не добавлен.

Read-only проверка исходного журнала и локального аудита нашла **33 явных совместимости**
с точным совпадением карточек: 22 Watch, 5 iPhone, 4 планшетных, 2 универсальных телефонных.
Всего в журнале 301 решение, в локальном каталоге 3355 строк. Эти 33 не подменяют все решения
по категориям; одиночный Watch-target не доказывает EXCLUSIVE. Четыре планшетные плёнки
не требуют повторного бизнес-согласования. Реальная привязка к актуальным provider ID/БД
и применение переноса ещё не выполнены.

Проверки используют отдельный PostgreSQL и синтетические факты: возврат после отзыва/переименования,
неактуальность при изменении исходной строки, отсутствие автоматического обновления legacy,
временная граница, идемпотентность, неизменность финансовых полей и rollback. Это не полный
end-to-end прогон рабочего sync и не сверка production. Следующий шаг: локальная интеграционная
приёмка полного sync, контролируемая привязка прежних решений к свежим данным БД, затем
официальная проекция категорий/ролей и денежные, attach, зарплатные инварианты. Этап 4.2 открыт.

### Совместимость чехлов и защиты телефона

В автоправилах распознанных чехлов и защиты дисплея/камеры явный iPhone/айфон
важнее одиночного слова Galaxy, которое может обозначать расцветку. Явное Samsung/самсунг
или модель Samsung не считается расцветкой. При одновременных признаках iPhone и Samsung
автоклассификатор возвращает отсутствие решения, не продолжает распознавание как телефона:
при отсутствии действующего ручного назначения используется обычный путь UNMAPPED.
Это не создаёт решения attach-attribution и не меняет постоянную совместимость по одному чеку.

Только в контексте таких аксессуаров распознаются A3x/A5x (латинская A или кириллическая А),
S2x, включая слитное/раздельное FE, Ultra и Plus, с границами модельного токена.
Обычные устройства, зарядки, услуги и прочие товары не наследуют это расширение.
Краткое подтверждённое название `Keephone X Crystal Samsung` (также X-Crystal)
распознаётся как чехол, даже без слова «чехол»; правило не распространяется на весь бренд.

Защита камеры и дисплея сохраняет разные категории; прежние категории аксессуаров iPad,
Mac и Watch, приоритет работ/коммерческих услуг и ручных назначений не меняются.
Это локальная правка автоправил без миграции БД и исправления сохранённых строк.
Общий небезопасный fallback неизвестного стекла в GLASS_IPHONE здесь ещё не устранён:
его замена требует отдельного внедрения GLASS_PHONE_UNRESOLVED и проверки расчётов.

## Attach-rate категория

Использует отдельные numerator/denominator codes. Care-продукт может быть
`PREMIUM_PROTECTION` для units-rate и одновременно warranty/protection в денежной структуре. Это
две проекции, не двойной денежный учёт. См. [attach-rate v3](attach-rate.md).

## Зарплатная категория

Определяет фонд: `TECH_TIER_1`, `TECH_TIER_2`, `ACCESSORY`, `SERVICE`,
`PLAYSTATION_SUBSCRIPTION`, `PAID_REPAIR`, `EXCLUDE`. Она не исправляет analytics assignment и не
меняет attach mapping. UI «Категории зарплаты» меняет только payroll category.

Для подтверждённой платной ремонтной работы используется payroll category `PAID_REPAIR`; её
analytics category при этом остаётся `SETUP_SERVICE`. Назначение одной проекции не создаёт и не
изменяет назначение другой.

## Ручное исправление

UI «Категории аналитики» создаёт effective-dated analytics assignment. Действие
`CLASSIFY_PRODUCTS` для `SOURCE_PRODUCTS_UNMAPPED` ведёт именно в этот раздел; payroll form остаётся
отдельным контуром.

После аналитического импорта backend повторно классифицирует только активные позиции со снимком
`UNMAPPED`, только для канонических external product IDs из импорта и только внутри указанного
integration connection. Уже классифицированные позиции и товары других подключений не меняются.
Quality issue товара закрывается только когда все найденные активные позиции этого товара получили
аналитическую категорию. Assignment учитывается только начиная с его `validFrom`; автоматическое
правило не используется для обхода этой даты. Связанный возврат наследует классификацию исходной
продажи.

Backend возвращает точный набор затронутых магазинов. Frontend после успешного аналитического
импорта запрашивает пересборку текущего Weekly Review для каждого из них и сбрасывает связанные
query caches. Новая immutable revision создаётся только при изменении content hash. Ошибка этого
дополнительного обновления не откатывает сохранённую категорию, показывается отдельно и допускает
повтор запроса.

Автоматические правила `livesklad-product-rules-v18` распознают кабели, название которых состоит
только из пар разъёмов `USB-C/Type-C` и `Lightning`, как `CHARGER_CABLE`. Оба признака обязательны,
чтобы слово `Lightning` в названии самостоятельной техники не превращало её в аксессуар.
Написания `картхолдер`, `кардхолдер` и `cardholder` относятся к
`OTHER_ACCESSORY_PRODUCT`. Эти категории имеют зарплатную категорию `ACCESSORY`; отдельное
payroll-назначение не создаётся.

## Зарядные блоки, кабели и комплекты

`CHARGER_CABLE` объединяет зарядные блоки, кабели и готовые комплекты
«блок + кабель». Это аналитическая категория зарядных принадлежностей,
а не всей группы LiveSklad «СЗУ/АЗУ/БЗУ»: пауэрбанки выделены в
`POWER_BANK`; обычные переходники и USB-хабы относятся к
`OTHER_ACCESSORY_PRODUCT`, но зарядные адаптеры остаются в `CHARGER_CABLE`.
В attach-rate категория остаётся числителем для телефонов по действующей методике.

Правило v26 распознаёт `Power Adapter`, `Fast Charger`, кабель с
`Cable USB-C/Type-C`, блок Baseus с указанной мощностью и комплекты
Baseus с мощностью и разъёмом до общего правила устройств Samsung.
V74 исправляет только 15 подтверждённых кодов LiveSklad: 4767, 4768,
4769, 324, 1941, 3241, 3494, 3493, 47, 48, 64, 65, 66, 67 и 690.
Код и признаки названия проверяются вместе;
карточки и сохранённые строки продаж/возвратов переводятся в
`CHARGER_CABLE/NOT_APPLICABLE`. Суммы и сотрудники не меняются.

Зарплатные назначения миграция не меняет. Однако расчёт зарплаты
использует default аналитической категории при отсутствии ручного
зарплатного назначения: например, исправление `SAMSUNG_NEW` в
`CHARGER_CABLE` меняет такой default с `TECH_TIER_1` на `ACCESSORY`.
Отдельный пересмотр зарплатных категорий отложен.

## Пауэрбанки

`POWER_BANK` — отдельная аналитическая категория для портативных и внешних
аккумуляторов, в том числе MagSafe Battery Pack. Правило v27 обрабатывает новые
карточки по названию до общего правила зарядок. V75 переносит 37 подтверждённых
кодов LiveSklad из выгрузки 24.09.2026, проверяя также название, и обновляет
сохранённые позиции продаж и возвратов. Код 3527 входит в эту группу, хотя
в LiveSklad он лежит не в группе зарядок.

Для `POWER_BANK` считается собственный attach-rate: чистое количество
пауэрбанков / чистое количество всех телефонов. Они не входят в числитель
`CHARGER_CABLE`; база у двух показателей одинаковая. Категория остаётся
дополнительной выручкой с зарплатным default `ACCESSORY`. Явные зарплатные
назначения V75 не меняет; если их нет, новый default может повлиять на
зарплатный расчёт, который нужно проверить отдельно при обновлении блока зарплат.

## USB-хабы и переходники

USB-хабы 6057 и 3242, дорожные/сетевые переходники 3301, 4973 и 4779,
а также аудиопереходник Lightning–3,5 мм 44 относятся к
`OTHER_ACCESSORY_PRODUCT`. Для них не создаются отдельная аналитическая
категория и отдельный attach-rate. Зарядный адаптер 20W, код 4775, относится
к `CHARGER_CABLE`. Название и код проверяются вместе; V76 только готовит
правила, а назначения действуют перспективно. Сохранённые продажи/возвраты
и явные зарплатные назначения не меняются.

Правило v28 распознаёт новые USB-хабы и аудиопереходники до общего правила
зарядок. Обычные названия со словом «переходник» или «адаптер» продолжают
получать `OTHER_ACCESSORY_PRODUCT`; зарядные признаки направляют в
`CHARGER_CABLE`. Наличие только USB, Type-C, Lightning или HDMI больше не
считается доказательством зарядки для новых продаж в attach-rate v4. Для
продаж до даты обновления прежний опубликованный числитель сохраняется.

## Подтверждённые зарядки, станция 3-в-1 и метки Taggy

V77 относит к `CHARGER_CABLE` коды 3480, 3481, 4013 (зарядки Ugreen),
6108 (кабель USB-C–Lightning), 4350 (беспроводная зарядка iPhone/Apple Watch)
и 71 (подтверждённая зарядная станция 3-в-1). Коды 3390 и 3391 —
Bluetooth-метки Keephone Taggy, они остаются в `OTHER_ACCESSORY_PRODUCT`.
Применение ограничивается проверенными кодом, названием и подключением LiveSklad
и требует отдельного датированного назначения; старые строки обоих магазинов
не переписываются.

Автоправило v29 после нормализации названия распознаёт сокращение
«зар. устройство» как зарядку, а «Станция 3 в 1 (Стоячая)» — только по
этому подтверждённому точному названию. Это предотвращает ошибочное
определение зарядки 4350 как самих Apple Watch. Суммы, сотрудники и явные
зарплатные назначения не меняются; изменение аналитической категории может
повлиять на зарплатный default там, где явного назначения нет.

## Колонки и умные станции

`SPEAKERS` — отдельная аналитическая категория для обычных колонок и Яндекс Станций.
Это `DEVICE` с семейством `OTHER`: товар входит в число устройств, но не телефонов
и не в дополнительную выручку. В attach-rate такие товары остаются
`OTHER_DEVICE`. Наушники, зарядные док-станции и аксессуары не входят в
`SPEAKERS`. Поскольку штатный API продаж не заполняет группу LiveSklad в карточке приложения,
автоматическое правило v18 распознаёт колонки по названию и известным
семействам моделей; подтверждённые существующие карточки и позиции
продаж/возвратов переводит ограниченная миграция V56.

Зарплатный default категории оставлен `TECH_TIER_2`, как у прежней
`PODS_WATCH_OTHER_DEVICE`, до отдельного пересмотра зарплатной классификации.
Суммы и состояния позиций миграция не меняет; уже сформированные immutable
снимки отчётов не переписывает.

## Фитнес-часы и браслеты

Ниже описана существующая runtime-классификация. Согласованное локальное уточнение для
Garmin Forerunner 165 Music и Vivoactive 6 — `WATCH_OTHER`; оно реализовано только в словаре
и read-only детализации (см. раздел ниже), ещё не заменяет правила sync, назначения и продажи.

`FITNESS_WEARABLE` — отдельная аналитическая категория для Garmin Forerunner/Vivoactive,
Google Fitbit Air и Whoop 5.0 из подтверждённой группы LiveSklad. Это `DEVICE` с
семейством `OTHER`: входит в число устройств, но не телефонов и не в дополнительную
выручку. Для attach-rate такие позиции остаются `OTHER_DEVICE`; отдельного показателя
для фитнес-устройств нет. Сменные ремешки и спортивные ленты не относятся к этой
категории.

Правило v18 распознаёт новые товары по семействам моделей. Миграция V57 переводит
подтверждённые карточки и сохранённые позиции продаж/возвратов; карточке Whoop
без назначения добавляется постоянное назначение. Зарплатный default остаётся
`TECH_TIER_2`, как у прежней категории, до пересмотра зарплат. Финансовые суммы
и уже сформированные immutable снимки отчётов не меняются.

## Умные очки

`SMART_GLASSES` — отдельная аналитическая категория для товаров RayBan
в обоих магазинах, в том числе Wayfarer и Starfire без слова Meta. Это `DEVICE` с семейством `OTHER`:
товар входит в число устройств, но не телефонов и не в дополнительную выручку.
Для attach-rate сохраняется роль `OTHER_DEVICE`; отдельного показателя для
очков нет. Зарплатный default остаётся `TECH_TIER_2` до пересмотра зарплат.

Миграция V58 переводит карточки товаров RayBan и сохранённые позиции
продаж/возвратов из других аналитических категорий в `SMART_GLASSES`;
карточкам без назначения добавляет постоянную категорию. Явные
аксессуары исключены. Суммы и уже сформированные immutable снимки отчётов
не меняются; позиции, прежде считавшиеся `UNMAPPED`, теперь входят в число
устройств attach-rate. Новые товары с `Ray Ban`, `Ray-Ban` или `Rayban` в названии
распознаёт правило v18; работы и явные аксессуары имеют приоритет.

## Фотоаппараты

`CAMERAS` — отдельная аналитическая категория для подтверждённых Instax Mini 13
с кодами LiveSklad 6031 и 6032. Это `DEVICE` семейства `OTHER`: устройства
учитываются в attach-rate как `OTHER_DEVICE`, но не как телефоны или аксессуары.
`GLASS_CAMERA_*` означает защиту камеры телефона и к фотоаппаратам не относится.

Миграция V59 добавляет постоянное назначение карточке 6031 и переводит её
сохранённую продажу из `UNMAPPED`; карточку 6032, отсутствующую в снимке БД,
она не создаёт. Новые товары семейства Instax Mini 13 распознаёт правило v18,
но плёнку и чехлы не относит к фотоаппаратам. Другие модели камер остаются
на отдельную проверку. Суммы и immutable снимки отчётов не меняются;
ранее `UNMAPPED` продажа начинает учитываться как устройство в attach-rate.
Зарплатный default — `UNMAPPED` до согласования зарплатных категорий.

## Услуги, заведённые как товары

Коды LiveSklad 6278 («Восстановления паролей»), 6151 («настройка Apple Watch»)
и 5348 («Настройка РЕЖИМА МОДЕМА») подтверждены для `SETUP_SERVICE`.
Все три карточки пришли как `PRODUCT`, а продажи имеют `is_work=false`:
аналитическая категория не меняет эти признаки источника.

Миграция V60 добавляет постоянные назначения трём карточкам. Сохранённую
продажу 6278 она переводит из `UNMAPPED/UNKNOWN` в
`SETUP_SERVICE/NOT_APPLICABLE`; продажи 6151 и 5348 уже имели правильную
аналитическую категорию. Правило v18 распознаёт новые услуги восстановления
пароля по названию, не превращая телефон с упоминанием пароля в услугу.

Суммы, сотрудник и immutable снимки отчётов не меняются. Новая зарплатная
методика не вводится, но существующий default категории `SETUP_SERVICE` —
`SERVICE` — будет применяться к 6278 при последующем перерасчёте зарплаты.

Для `SERVICE|WARRANTY|PROTECTION` ожидаемый ноль себестоимости допустим. В других категориях
`ZERO_UNEXPECTED` сохраняется как открытое `INFO`: оно требует проверки смысла позиции и источника,
но не блокирует readiness и не превращает cost/GP в `null`.

## Стайлеры для волос

`HAIR_STYLERS` — отдельная аналитическая категория для подтверждённых Dyson
HS08/Airwrap с кодами LiveSklad 3105, 3183, 4282 и 5201. Код 3183 был ошибочно
отнесён к `IPAD_MAC`, хотя находится в группе «ДР. ТЕХНИКА APPLE»; группа
источника сама по себе не определяет аналитику. Остальные три кода находились
в группе Dyson. Категория имеет тип `DEVICE` и семейство `OTHER`, поэтому
attach-rate по-прежнему учитывает эти продажи как `OTHER_DEVICE`.
Зарплатный default сохранён `TECH_TIER_1`; методика зарплаты не меняется.

Миграция V61 ограничена этими четырьмя кодами и проверкой названия. Она
исправляет постоянные назначения 3105, 3183 и 4282, добавляет отсутствующее
назначение 5201 и переводит сохранённые позиции продаж в новую аналитическую
категорию. Суммы, состояние товара и immutable снимки отчётов не меняются.
Новое правило v18 распознаёт Dyson HS08 и Airwrap по названию, но не относит
к стайлерам насадки, чехлы, иные аксессуары или любой другой товар Dyson.

## Наушники Apple, Samsung и других брендов

Три самостоятельные аналитические категории заменяют широкую
`PODS_WATCH_OTHER_DEVICE` для подтверждённых наушников: `HEADPHONES_APPLE`
(9 карточек AirPods и EarPods), `HEADPHONES_SAMSUNG` (4 карточки Galaxy Buds)
и `HEADPHONES_OTHER` (12 карточек Marshall, Sony, Яндекс и EW).
Galaxy Buds не являются телефонами Samsung; три EW ошибочно лежат в группе
LiveSklad «РЕМЕШКИ Watch», но аналитически относятся к наушникам. Часы и
чехлы для наушников в эти категории не входят.

Миграция V62 ограничена 25 кодами с проверкой названия. Она переводит
постоянные назначения и сохранённые позиции продаж/возвратов, не меняя суммы,
состояние товара, сотрудников и immutable снимки отчётов. Все три категории
наследуют прежние признаки устройства и действующий зарплатный default от
`PODS_WATCH_OTHER_DEVICE`: зарплатная методика и отдельные payroll-назначения
не пересматриваются. Новое правило v18 классифицирует новые AirPods/EarPods,
Galaxy Buds и остальные наушники до общих правил телефонов и устройств;
аксессуары и работы имеют приоритет.

Правило v18 также устраняет два пересечения: слово «беспроводные» больше
не считается признаком провода/зарядки, а Samsung Galaxy Watch
распознаются как часы до общего правила телефонов Samsung. Это влияет
на автоматическую классификацию новых карточек, не меняя уже утверждённые
зарплатные уровни.

## Защита дисплея и камеры телефона

Аналитические категории остаются раздельными для каждой пары бренд ×
назначение: `GLASS_IPHONE` и `GLASS_CAMERA_IPHONE`, `GLASS_SAMSUNG` и
`GLASS_CAMERA_SAMSUNG`. Все четыре — аксессуары с прежним зарплатным
default `ACCESSORY`, но в attach-rate имеют отдельные числители.
Слияния категорий и изменения формул нет.

Код LiveSklad 5716 («Защитное стекло для камеры VLP iPhone Air»)
подтверждён как `GLASS_CAMERA_IPHONE`; в снимке БД две его продажи
ошибочно имели `GLASS_IPHONE`. Код 5162 («Защитные линзы Camera Film»)
подтверждён как защита камеры iPhone: его продажи уже имели правильную
категорию, но постоянного назначения карточки не было. Миграция V63
ограничена этими кодами и названиями: исправляет назначения, а среди
сохранённых позиций меняет только неверную категорию 5716. Суммы,
состояние товара, зарплатные уровни и immutable отчёты не меняются.
Правило v18 распознаёт «стекло для/на камеру» как защиту камеры.

## Чехлы

V64 переводит 14 подтверждённых чехлов Keephone X-Crystal iPhone 14–17 из
`OTHER_ACCESSORY_PRODUCT` в `CASE_APPLE_IPHONE`, включая ошибочные снимки
продаж. Правило v19 распознаёт эти модели при поступлении новых карточек.

V65 оставляет код `CASE_APPLE_IPHONE` и отдельную категорию `CASE_SAMSUNG`.
Чехлы AirPods остаются в `ACCESSORY_PODS_WATCH`. Явные аксессуары iPad,
включая чехлы, переходят в `ACCESSORY_IPAD`; явный чехол MacBook — в
`ACCESSORY_MAC`; три чехла «для ноутбуков 14″» — в `CASE_OTHER_DEVICE`.
Чехлы без явного устройства остаются на проверку в прежней категории.

Четыре плёнки «для планшета» без признака iPad переводятся из смешанной
`ACCESSORY_IPAD_MAC` в `OTHER_ACCESSORY_PRODUCT`: их нельзя автоматически
считать аксессуарами iPad. Подтверждённые Apple Pencil 2579, 3325, 3901,
6175 и Magic Keyboard iPad Pro 3784 переходят в `ACCESSORY_IPAD`.
Magic Mouse 2591, 2972, 2973 переходят в `ACCESSORY_MAC` по явному выбору
пользователя. Правило v20 распознаёт такие будущие товары; Magic Keyboard
без указанного устройства остаётся на проверку. Старый код
`ACCESSORY_IPAD_MAC` сохраняется для исторической совместимости.

Все новые категории имеют зарплатный default `ACCESSORY`, но для восьми
ранее классифицированных как устройства Pencil, Mouse и Keyboard сохраняется
прежний `TECH_TIER_2` до отдельного пересмотра зарплаты. Суммы продаж
не меняются.

V66 переводит восемь подтверждённых чехлов Keephone Mago Pro Matte MagSafe
для iPhone 17 Pro и Pro Max (коды 2539, 2540, 2541, 2618, 2542, 2543,
2544, 2619) из `OTHER_ACCESSORY_PRODUCT` в `CASE_APPLE_IPHONE`, включая
снимки продаж и возвратов. Правило v21 распознаёт будущие карточки той же
модели по названию. Неопределённые Keephone Mago остаются на проверку.

V67 переводит ещё восемь подтверждённых чехлов Keephone Mago для iPhone
15 Pro Max (3423, 3424), 15 Pro (3293, 3294, 3295) и 16 Pro Max
(2870, 2871, 2872) в `CASE_APPLE_IPHONE`, включая сохранённые продажи.
Правило v22 распознаёт будущие карточки этих моделей; неопределённые
чехлы Mago не переклассифицируются.

V68 переводит оставшиеся 12 чехлов Keephone с явной моделью iPhone из
`OTHER_ACCESSORY_PRODUCT` в `CASE_APPLE_IPHONE` и добавляет постоянное
назначение карточке 5882, чья сохранённая продажа уже была классифицирована
правильно. Среди 12 карточек три несбытых товара имеют технический тип
`UNKNOWN`; миграция охватывает их по точному коду и названию, не меняя тип.
Правило v23 распознаёт будущие чехлы Keephone с явным iPhone
либо номером 14–19 и уточнением после него, исключая явные другие
устройства; чехлы без модели остаются на проверку. Сохранённые продажи
обоих магазинов исправляются без изменения
сумм и зарплатных категорий.

V69 применяет общее правило независимо от бренда: карточка с названием
«Чехол … iPhone/айфон» либо с однозначной моделью iPhone 13–19
(`Pro`, `Pro Max`, `Plus`, `Mini`, `e`, `Air`) получает
`CASE_APPLE_IPHONE`. Названия других устройств, смешанные комплекты
«чехол+стекло» и существующие ручные назначения другой категории
исключены. Для карточок без назначения создаётся постоянное назначение;
сохранённые продажи из `OTHER_ACCESSORY_PRODUCT` переводятся в категорию
чехлов, если нет конфликтующего ручного решения. Правило v24 применяется
к будущим карточкам. Суммы и зарплатные категории не меняются.

Чехлы с неизвестным целевым устройством не считаются чехлами iPhone только
по бренду Keephone или слову MagSafe. Категория `OTHER_CASE` выделяет восемь
согласованных карточек LiveSklad (14, 30, 36, 38, 39, 40, 41, 588) из
`OTHER_ACCESSORY_PRODUCT`, включая сохранённые строки продаж и возвратов.
Новые чехлы с неуказанной моделью также получают `OTHER_CASE`; смешанные
комплекты, чехлы очков, фотоаппаратов и техники Dyson остаются в прежних
категориях. `OTHER_CASE` сохраняет зарплатную категорию `ACCESSORY` и
денежные суммы. Ручное решение по отдельной продаже не означает, что вся

## Локальные предложения детализации устройств

`CatalogDeviceDetailProposer` — отдельный read-only компонент для планшетов, ноутбуков и часов.
Он выделяет тип, кандидаты бренда и явно указанное состояние, возвращает объяснения, причины
проверки и fingerprint исходного наблюдения. `CatalogDeviceCategoryPolicy` сопоставляет признаки
с согласованными кодами TABLET_APPLE/TABLET_OTHER, LAPTOP_APPLE/LAPTOP_OTHER,
WATCH_APPLE/WATCH_SAMSUNG/WATCH_OTHER.

Это не новая действующая политика sync: `ProductClassificationResolver` не вызывает компонент,
новые коды им не записываются в БД. PROPOSED всегда требует подтверждения; NEEDS_REVIEW и CONFLICT
не дают разрешения на применение. OUT_OF_SCOPE не означает подтверждённую классификацию.
Неизвестный бренд не считается OTHER, отсутствие признака состояния не означает NEW.
Совместимость аксессуара не считается типом/брендом проданного устройства. Специализированная
FITNESS_WEARABLE не пересматривается целиком: исключение в локальном слое сделано только для
согласованных моделей Garmin Forerunner 165 Music и Vivoactive 6.

Локальная детализация и словарь распознают эти две модели Garmin как часы известного другого
бренда: `WATCH_OTHER`. Сопоставление модели ограничено границами слов/чисел; одного Garmin,
Forerunner без номера/Music, Vivoactive 7 или Vivoactive 60 недостаточно для этого уточнения.
Чехол, ремешок, кабель, зарядка и услуга с упоминанием модели не становятся часами.
`Garmin Vivoactive 6 … with Black Band` означает комплектные часы; отдельный replacement band
не попадает в устройства. Состояние NEW/USED из бренда, группы или согласования категории
не выводится. Неизвестное состояние и предупреждение комплектного ремешка остаются видимыми.
Изменение локальной политики меняет fingerprint; это не команда к переопределению ручных назначений.

Read-only replay через `scripts/catalog-audit/probe_details.py` не требует БД и не создаёт
назначений. Модель хранения признаков, алиасов, manager queue и условия подключения описаны в
[проекте детализации каталога](../../maintenance/catalog-classification-architecture.md).
Этот компонент не меняет зарплатные уровни, факты продаж, attach-rate или ручные назначения.

## Локальный словарь признаков каталога (не рабочий классификатор)

`scripts/catalog-audit/catalog_lexicon.json` — версионируемый словарь наблюдаемых
семейств моделей, предметов, производителей, совместимости, услуг, состояния и пометок.
`lexicon_audit.py` применяет его только к локальным XLSX-выгрузкам и составляет предложения.
Словарь не подключён к sync, `ProductClassificationResolver`, manager API или расчёту показателей.
Он не заменяет действующие Java-правила и не применяет ранее согласованные назначения заново.

Правила собирают все совпадения с rule ID, найденным текстом и позицией в нормализованном имени.
Отдельный контекстный обработчик отличает устройство от аксессуара/работы и его совместимости.
Порядок правил не определяет победителя. Производитель аксессуара не наследуется от упомянутого
iPhone/Watch; по умолчанию группа LiveSklad используется для обнаружения расхождений.
Исключение — явно одобренные владельцем правила источника состояния, описанные ниже.
Отсутствие «Б/У» не означает NEW; A/B/C, процент аккумулятора и активация не заменяют состояние.
UNKNOWN не превращается в OTHER. NEW + ASIS совместимы в этом прототипе; NEW + USED — конфликт.

Правила Camera Film, RayBan и другие договорённости магазинов имеют явную область подключения.
Без совпадения `connection_key` такие правила не работают. Короткие модельные алиасы, неясная
совместимость и комплекты требуют проверки. Неизвестная защита дисплея может предлагаться как
`GLASS_PHONE_UNRESOLVED`, неизвестный чехол как `OTHER_CASE`, но это не доказательство платформы
и не разрешение увеличивать телефонный attach-rate по одному названию.

Каждая строка результата содержит предложение, причины, evidence и fingerprint наблюдения.
PROPOSAL означает только отсутствие обнаруженных противоречий, а не подтверждение человеком.
NEEDS_REVIEW может относиться к отдельному признаку, не обязательно к неверной категории.
Наличие старого ручного решения проверяется отдельно; отсутствие предложения из имени не отменяет
это решение и не означает необходимость повторного согласования.
Предыдущий Java replay, если передан, сравнивается по hash выгрузок и всем идентификаторам/именам.
Он не считается экспортом фактических назначений базы.

Подробный запуск и границы применения описаны в
[проекте архитектуры каталога](../../maintenance/catalog-classification-architecture.md#11-словарь-признаков-по-полному-каталогу).
Зарплата, продажи, возвраты и attach-rate этим инструментом не изменяются.

### Точечные подтверждения владельца в локальном аудите

Опциональный `--owner-decisions` принимает отдельный локальный JSON со статусом
`OWNER_CONFIRMED_NOT_APPLIED`, областью подключения и списком `decisions`.
Запись задаёт `source_kind`, `code`, `expected_name`, `expected_group`, `category`
и конкретные `resolved_reasons`. Дубликат, неизвестный код, изменённое имя/группа,
чужое подключение или уже изменившиеся причины проверки останавливают прогон.
Одинаковое название у другого кода не наследует подтверждение.

Уточнение категории не заполняет неизвестного производителя. Исходное предложение и причины
сохраняются в `lexical_proposal`, подтверждение — в отдельной колонке и `owner_decision`.
Снятие согласованных причин не скрывает остальные предупреждения. OWNER_CONFIRMED означает
только подтверждение категории для локального просмотра, не запись назначения в БД.
Файл подтверждений хранится в игнорируемом outputs, его hash включён в отчёт.
Общие лексические правила не расширяются из-за подтверждения одной карточки.

### Локальное уточнение планшетных плёнок

Четыре согласованные защитные плёнки с общим указанием «для планшета» в локальном аудите
отнесены к `PROTECTIVE_FILM` вместо `OTHER_ACCESSORY_PRODUCT`. Предмет остаётся FILM,
а целевой тип задаётся `TABLET_GENERIC`: это не подтверждение другого, не-Apple бренда
и не утверждение универсальной совместимости. Бренд/модель устройства из этого признака
не выводятся. Словарь различает общий планшетный target и модельные iPad/Galaxy Tab/Xiaomi Pad;
в аксессуарном контексте общий признак не используется как OTHER_TABLET.

Нейтральная категория предлагается для плёнки с общей планшетной целью, а не для любого
предмета со словом «планшет». Явные iPad-плёнки сохраняют `ACCESSORY_IPAD`, стёкла/защита камеры,
чехлы и работы не становятся плёнками. Противоречивые цели не выбираются по первому совпадению.
Без цели прежние неподтверждённые лексические предложения не получают TABLET_GENERIC:
точные решения по восьми нейтральным плёнкам продолжают применяться отдельным слоем.

В локальном профиле допустима подтверждённая цель TABLET_GENERIC и функция FILM для
PROTECTIVE_FILM. Они не создают телефонный attach-rate и не определяют NEW/USED.
Это уточнение словаря, профиля и журнала решений; приведённое выше поведение V65/runtime
остаётся прежним до согласованной миграции, интеграции метрик и свежей сверки продаж.

### Итоговый профиль локальной проверки

Для явно подтверждённых универсальных чехлов локальный профиль поддерживает отдельную цель
`PHONE_UNIVERSAL`. Она допустима только в паре с `CASE_UNIVERSAL` и не смешивается в одном
подтверждённом списке с брендовыми целями. Категория `CASE_UNIVERSAL` без явного подтверждения
этой цели отклоняется. Значение проецируется в JSON/Excel как `review_compatibility` с источником
`OWNER_CONFIRMED`; исходные признаки не переписываются. Проверки закреплены синтетическими
тестами в `scripts/tests/test_catalog_review_profile.py`, включая паритет JSON/Excel.
Это только локальное представление согласования: новое автоправило, запись в рабочий справочник
и участие в attach-rate не включаются. Универсальность не означает совместимость с любыми
габаритами и не даёт права засчитать одну единицу в два брендовых числителя.

JSON и XLSX разделяют наблюдение, точное подтверждение и итог для просмотра:

| Поле | Значение |
|---|---|
| `condition`, `condition_source`, `types`, `compatibility` | Исходные признаки; подтверждение не переписывает их |
| `owner_confirmed_condition`, `owner_confirmed_function` | Явно подтверждённые состояние и функция из owner_decision |
| `owner_confirmed_compatibility` | Ранее сохранённая подтверждённая совместимость |
| `review_condition`, `review_condition_source` | Подтверждённое состояние с источником OWNER_CONFIRMED; иначе исходное состояние и его источник |
| `review_types`, `review_types_source` | Подтверждённая функция одним элементом списка; иначе все исходные типы, без произвольного выбора одного |
| `review_compatibility`, `review_compatibility_source` | Непустой подтверждённый список; иначе исходные кандидаты совместимости |

LEXICON обозначает признаки словаря, UNKNOWN — отсутствие признаков.
Источник OWNER_APPROVED_SOURCE_GROUP сохраняется как источник состояния, а не подменяется
точечным OWNER_CONFIRMED. Пустая подтверждённая совместимость означает отсутствие уточнения,
не команду очистить наблюдённую совместимость.

Профиль не выводит состояние или функцию из одной категории. Неизвестное состояние остаётся
неизвестным, а не NEW/NOT_APPLICABLE; подтверждение одного поля не подтверждает остальные.
Исходные причины, не снятые конкретным решением, сохраняются, включая CONFLICT.
`review_*` — **не действующие значения в БД и не разрешение на импорт**;
`effective_assignment` остаётся null. Версия этой добавленной проекции указана в
`summary.review_profile_version`, счётчики подтверждённых состояний/функций показаны отдельно.

Значения confirmed_condition и confirmed_function проверяются до формирования файлов:
неверные типы/неизвестные значения отвергаются; состояние должно соответствовать выбранной
телефонной категории, функция — допустимой для неё категории. Невалидное подтверждение
останавливает прогон, не создавая частичного отчёта. Готовые строки предыдущего review
нельзя повторно передавать в apply_owner_decisions: сначала заново получают исходные признаки.
Копии вложенных списков отделены от исходного наблюдения и журнала решений.

Подтверждения остаются в прежнем журнале без переписывания. Обновление кода меняет fingerprint
наблюдения; само по себе это не новое бизнес-решение. Перед будущим применением в сервере
необходимы отдельные preview/confirm, свежая сверка назначений/продаж и проверки зарплаты.

## Согласованное разделение аксессуаров Apple Watch и AirPods

Владелец одобрил аналитические leaf-коды `ACCESSORY_APPLE_WATCH` и `ACCESSORY_AIRPODS`.
В локальном словаре/аудиторе они заменяют смешанный `ACCESSORY_PODS_WATCH`:
ремешки, чехлы и защита дисплея Apple Watch относятся к первому; чехлы AirPods — ко второму.
Сами часы/наушники и зарядки остаются в своих категориях. Аксессуар EarPods не становится
аксессуаром AirPods автоматически. Одновременная совместимость с Watch и AirPods требует проверки,
а не выбора первой категории.

По исходной выгрузке подготовлено 52 назначения: 28 чехлов AirPods и 24 аксессуара Apple Watch
(21 ремешок, 3 защитных стекла). Восемь ремешков uBear подтверждены владельцем для Apple Watch;
ранее подтверждённый Watch Nike следует этому же разделению. Совместимость ещё 12 ремешков
Silicone/VLP/Ocean Band/миланская петля впоследствии также подтверждена владельцем для Apple Watch;
в локальном отчёте флаг TARGET_MODEL_REVIEW снят только для этих точных карточек.
Лексические предположения и исходные причины сохранены отдельно от подтверждения.
Оставшийся аксессуар-брелок из прежней смешанной группы впоследствии подтверждён владельцем
как зарядный брелок для Apple Watch: его локальная категория — CHARGER_CABLE.
Подтверждённая функция CHARGER сохранена отдельно от лексического признака KEYCHAIN;
это точечное решение для карточки, не общее правило для всех брелоков или аксессуаров Watch.

В local JSON/XLSX подтверждённая совместимость теперь выведена отдельно от лексических признаков
(`owner_confirmed_compatibility`). Неизвестное имя производителя не заменяется брендом устройства.
Все предыдущие локальные подтверждения сохраняются; точечные решения требуют совпадения
подключения, source-kind, кода, имени и исходной группы.

Это выполненное локальное распределение, не изменение рабочего справочника БД.
Production-классификатор и приведённые выше действующие правила пока используют старую категорию.
Ни исторические продажи/возвраты, ни зарплата, ни attach-rate этой итерацией не изменены.

Перед включением в рабочий контур необходимы:

- Миграция двух leaf-категорий с сохранением свойств дополнительной продажи и эффективной зарплаты,
  а не только явных payroll assignments. Старую категорию не удалять до завершения сверки.
- Обновление `ProductAutoClassificationRuleEngine` и тестов приоритетов: аксессуар/устройство,
  Watch/AirPods, зарядка, неизвестная совместимость.
- Обновление SQL-проекций attach-rate: текущий `attach_rate_ordinary_item_facts_v4` проверяет
  равенство `ACCESSORY_PODS_WATCH`. Для сохранения существующего общего показателя оба новых
  аналитических кода должны отображаться в прежний numerator metric с прежними ограничениями.
  Два отдельных attach-rate — отдельное изменение методики, здесь оно не включалось.
- Проверка остальных проекций, потребителей и возвратов, а также подписей/порядка frontend.
  Аналитический leaf-код нельзя механически подставлять вместо metric code.
- Свежая read-only сверка действующих назначений, ограниченный перенос нужных карточек/позиций,
  проверка store/employee totals, effective payroll и пересборка зависимых отчётов.
  Изменения frontend проверять локально по правилам проекта.

Локальный тестовый набор покрывает разделение, недопустимое смешение целей, EarPods,
зарядки, самостоятельные устройства и изоляцию подтверждённых признаков. Прогон всей выгрузки
проверен по кодам: изменились ровно 52 категории; производители и состояние не изменились.

## Одобренный источник состояния iPhone: группа Б/У

Владелец разрешил использовать группы LiveSklad `IPHONE (Б/У)` и `IPHONE 2 (Б/У)`
как источник состояния USED для самих iPhone. В локальном словаре правило
`owner_iphone_used_group` имеет `input=source_group`, ограничено подключением
`livesklad-default`, source-kind PRODUCT и распознанным семейством IPHONE.
Оно выполняется после определения роли продаваемого предмета, не по одному слову в пути группы.

Если в названии состояние не указано, правило даёт USED и `IPHONE_USED`;
источник состояния в JSON/XLSX — `OWNER_APPROVED_SOURCE_GROUP`.
Evidence содержит ID правила, совпавшую группу и `input=source_group`, чтобы это не выглядело
как найденное в названии «Б/У». Если USED уже указан в имени, источник остаётся NAME.
При явном NEW/ASIS в такой группе возникает конфликт; автоматическое предложение категории
пустое до проверки. Правило не назначает NEW по отсутствию «Б/У» и не распространяется
на группу активированных iPhone, Samsung, другие подключения или произвольные вложенные группы.
Свежая выгрузка от 1 октября добавила корневой путь `/Основные` к тем же группам.
По явному подтверждению владельца правило допускает только этот необязательный корень,
но не допускает другие вложения или похожие названия групп.

iPad, MacBook, Samsung, часы, аксессуары и работы даже внутри ошибочной iPhone-группы не
получают `IPHONE_USED`. Ручные локальные подтверждения по-прежнему обрабатываются отдельным слоем.
Прежние подтверждения владельца для двух конкретных iPhone как б/у с этим правилом согласуются.

На локальной выгрузке правило заполнило состояние и категорию у 15 ранее неопределённых iPhone,
включая последние шесть согласуемых карточек. Остальные категории и производители не менялись.
Правило проверено синтетическими тестами на scope, роли, чужие группы и противоречивые состояния.
Это изменение только локального словаря/аудита: рабочий sync пока не получает такое правило
из XLSX, постоянные назначения, продажи, возвраты и зарплата не изменены.

## Подготовка импорта исходных групп из Excel

Для первой поставки выбран Excel, без подключения внутреннего API LiveSklad или FTP.
`prepare_group_import.py` создаёт только PREVIEW_ONLY JSON/XLSX в игнорируемом outputs:
это **не серверная команда импорта** и не payload существующего product-category-import API.
Последний может переклассифицировать UNMAPPED-позиции; использовать его для загрузки групп нельзя.

Обязательны «Код», «Наименование», «Полная группа»; «Название группы» сохраняется при наличии.
Код остаётся source_code, не externalProductId и не UUID. Непустая группа — SET_CANDIDATE,
пустая — KEEP_EXISTING с предупреждением, а не удаление старой связи. Отсутствующий столбец,
пустой файл и повтор source-kind/code отвергаются. Полный путь и имя сохраняются буквально;
иерархия по разделителю не придумывается. Товары и работы имеют разные пространства кодов.

Preview связывает connection, file/code hashes, точное название и группу каждой строки;
не запрашивает БД, не создаёт товары, группы или назначения. Отдельно показывает предложения
локального правила iPhone Б/У, не применяя их и не отменяя ручных решений.
`effective_from` и `export_observed_at` остаются null: время запуска не считается датой выгрузки.
Хеш preview не заменяет DB revision и не разрешает применение.

В серверной модели ProductDetails различаются отсутствие сведений о группе
(`sourceGroupObserved=false`) и явное наблюдение (`true`, включая null для подтверждённого
отсутствия). Прежний шестипараметровый конструктор с null означает отсутствие сведений.
Поэтому продажи, заказы, возвраты и подтверждение provisional identity не стирают уже сохранённую
группу только из-за отсутствующего поля. Проверки давности наблюдения и принадлежности подключения
сохранены. Excel-preview никогда не создаёт команду явного удаления группы.

**Оставшаяся часть блока:** свежая сверка source-kind/code/name с существующим product UUID,
проверка повторов и ручных назначений; серверный preview/confirm с ревизиями и аудитом;
отдельная дата действия и версия наблюдений группы, чтобы повторная синхронизация старой продажи
не применяла к ней сегодняшнюю группу. Только после этого подключать правило группы к рабочему
resolver. Сейчас оно остаётся локальным, runtime-классификация и история не переключены.
