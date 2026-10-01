---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-09-30
requirement_sources:
  - docs/archive/legacy-contracts/attach-rate-api.md
  - docs/archive/discoveries/analytics-business-rules-draft.md
implementation_sources:
  - backend/src/main/resources/db/migration/V88__bound_catalog_pending_role_lookup.sql
  - backend/src/main/resources/db/migration/V85__project_catalog_attach_details.sql
  - backend/src/main/resources/db/migration/V55__attach_warranty_attribution.sql
  - backend/src/main/resources/db/migration/V80__bound_warranty_fingerprint_context_to_document.sql
  - backend/src/main/resources/db/migration/V62__split_headphone_categories.sql
  - backend/src/main/resources/db/migration/V63__correct_iphone_camera_glass.sql
  - backend/src/main/resources/db/migration/V64__correct_x_crystal_iphone_cases.sql
  - backend/src/main/resources/db/migration/V65__split_ipad_mac_and_other_device_cases.sql
  - backend/src/main/resources/db/migration/V66__correct_mago_pro_iphone_cases.sql
  - backend/src/main/resources/db/migration/V67__correct_mago_15_16_iphone_cases.sql
  - backend/src/main/resources/db/migration/V68__correct_remaining_model_named_keephone_iphone_cases.sql
  - backend/src/main/resources/db/migration/V69__classify_explicit_iphone_cases.sql
  - backend/src/main/resources/db/migration/V70__add_unresolved_phone_case_category.sql
  - backend/src/main/resources/db/migration/V71__add_case_attach_decisions.sql
  - backend/src/main/resources/db/migration/V72__project_confirmed_cases_into_attach_rates.sql
  - backend/src/main/resources/db/migration/V83__extend_accessory_sale_reviews.sql
  - backend/src/main/resources/db/migration/V75__split_power_bank_category_and_attach_rate.sql
  - backend/src/main/resources/db/migration/V76__classify_hubs_and_adapters.sql
  - backend/src/main/resources/db/migration/V77__classify_charging_stations_and_trackers.sql
  - backend/src/main/resources/db/migration/V38__attach_rate_units_methodology.sql
  - backend/src/main/java/com/storeanalytics/metrics/service/AttachRateService.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/AttachAttributionQualityRepository.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/AttachAttributionQuality.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewAttributionRepository.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewFactsSource.java
  - backend/src/main/java/com/storeanalytics/metrics/cases/CaseAttachService.java
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeRatingService.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/common/database/CatalogChargerAdapterCutoverIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/CatalogChargerAdapterReturnIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogSaleRoleSnapshotIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/CatalogAttachDetailsIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/cases/AccessorySaleReviewIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/WarrantyFingerprintContextMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/warranty/WarrantyAttributionIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/AttachRateIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/AttachAttributionQualityTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyV3LoadIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewSnapshotStoreIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewServiceTest.java
  - backend/src/test/java/com/storeanalytics/common/database/PowerBankMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/AdapterProductsMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/ChargingStationsAndTrackersMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/AttachRateServiceTest.java
  - backend/src/test/java/com/storeanalytics/common/database/CareClassificationMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/HeadphoneCategoryMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/CameraGlassCorrectionMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/XCrystalIphoneCaseMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/DeviceAccessorySplitMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/MagoProIphoneCaseMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/Mago1516IphoneCaseMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/RemainingKeephoneIphoneCaseMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/ExplicitIphoneCaseMigrationIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - product
  - backend
review_triggers:
  - attach-rate-change
  - classification-change
  - return-attribution-change
supersedes:
  - receipt-cooccurrence-attach-rate-methodology
superseded_by: null
---

# Attach-rate: количества и атрибуция гарантий

Реализация поддерживает прежнюю `attach-rate-v3` и новую `attach-rate-v4` за флагом
`app.attach.attribution-enabled` / `ATTACH_ATTRIBUTION_ENABLED`. Это контракт кода;
наблюдаемое состояние среды указывается только в [project-state](../project-state.md).

**Перспективный переход каталога:** описания V56–V70 и V74–V77 ниже
содержат исторические формулировки о переносе сохранённых строк. В текущих
неопубликованных черновиках эти DML-переносы удалены; старые продажи и их
опубликованные числители/знаменатели остаются прежними. Новые категории
появятся в расчётах только после точных назначений с датой включения.

```text
N = положительные количества допродаж − количества связанных возвратов
B = количества соответствующих устройств − количества возвратов устройств
AttachRate = max(0, N) / B × 100%, если B > 0; иначе null
```

Процент не ограничивается 100%. Исходные N/B сохраняются, в том числе отрицательные.
Финансовые выручка, себестоимость, прибыль и employee_id документа не меняются.

| Код | Числитель | База |
|---|---|---|
| `CASE_APPLE_IPHONE` | Чехлы iPhone | Все iPhone |
| `CHARGER_CABLE` | Зарядки/кабели телефонов | Все телефоны |
| `POWER_BANK` | Пауэрбанки и внешние аккумуляторы | Все телефоны |
| `GLASS_IPHONE` | Стёкла iPhone | Все iPhone |
| `GLASS_CAMERA_IPHONE` | Защита камеры iPhone | Все iPhone |
| `FILM_PHONE` | Плёнки телефонов | Все телефоны |
| `SETUP_SERVICE` | Настройка | Телефоны + MacBook + PlayStation |
| `CASE_SAMSUNG` | Чехлы Samsung | Новые и Б/У Samsung |
| `GLASS_SAMSUNG` | Стёкла Samsung | Новые и Б/У Samsung |
| `GLASS_CAMERA_SAMSUNG` | Защита камеры Samsung | Новые и Б/У Samsung |
| `ACCESSORY_PODS_WATCH` | Общая сводка аксессуаров AirPods/Watch, включая явный legacy-остаток | AirPods + Apple Watch; в v3 сохраняется историческая база EarPods |
| `ACCESSORY_AIRPODS` | Аксессуары AirPods | AirPods, без EarPods |
| `ACCESSORY_APPLE_WATCH` | Аксессуары Apple Watch | Apple Watch |
| `ACCESSORY_IPAD` | Аксессуары iPad | iPad |
| `WARRANTY_GENERIC_USED` | Гарантии Б/У | Б/У iPhone + Samsung |
| `WARRANTY_GENERIC_NEW` | Гарантии новых | Новые/ASIS iPhone + новые Samsung |
| `PREMIUM_PROTECTION` | Privilege Care, Ultimate Care+, Elite Care | iPhone и Samsung new/used; iPad; MacBook/другие Mac; AirPods; Apple Watch; headphones; PlayStation |

`POWER_BANK` и `CHARGER_CABLE` имеют одинаковую базу «все телефоны», но
раздельные числители. Новая метрика появляется у магазина и сотрудников;
при достаточной базе она участвует в рейтинговом attach-score по общему
правилу. V75 не меняет явные зарплатные назначения и уровни; если назначения
нет, смена аналитической категории может изменить зарплатный default. Его
проверим отдельно при обновлении блока зарплат.

Для старых продаж v4 сохраняет прежнюю эвристику адаптеров, иначе миграция
ретроактивно меняла бы опубликованный `CHARGER_CABLE`. С даты активации для
`OTHER_ACCESSORY_PRODUCT` одного упоминания USB, Type-C, Lightning или HDMI
недостаточно: нужен явный зарядный признак. У связанного возврата для выбора
правила используется дата исходной продажи.
Категории и числители ранее подтверждённых зарядных блоков не меняются.

V77 относит подтверждённые коды 3480, 3481, 4013, 6108, 4350 и 71
к числителю `CHARGER_CABLE` при продаже с телефоном, а метки Taggy
3390 и 3391 исключает из этого числителя. Сохранённые продажи и возвраты
переклассифицируются; финансовые суммы не пересчитываются.

V64 добавляет количества продаж 14 подтверждённых чехлов Keephone X-Crystal
в существующий числитель `CASE_APPLE_IPHONE` (v3 и v4). Суммы и зарплатные
категории не меняются.

V65 сохраняет коды и методику расчёта attach-rate, но исправляет вклад
конкретных товаров. Новая аналитическая категория `ACCESSORY_IPAD` питает
прежний числитель `ACCESSORY_IPAD`; старый `ACCESSORY_IPAD_MAC` остаётся
совместимым для исторических строк. `ACCESSORY_MAC` и
`CASE_OTHER_DEVICE` отдельного числителя не имеют. Плёнки для
неопределённого планшета больше не считаются аксессуарами iPad.
Подтверждённые Pencil и Magic Keyboard iPad переходят из роли устройства
в аксессуары iPad и добавляются к его числителю; Magic Mouse переходит в
аксессуары Mac без отдельного числителя. Эти товары больше не считаются
устройствами, но базы iPad/Mac в attach-rate не меняются: прежние названия
периферии не соответствовали устройствам iPad или MacBook. Суммы продаж
не меняются.

V66 добавляет к тому же числителю продажи восьми подтверждённых чехлов
Keephone Mago Pro Matte MagSafe для iPhone 17 Pro/Pro Max и вычитает их
возвраты в v3 и v4. База, суммы продаж и зарплатные категории не меняются.

V67 добавляет к числителю `CASE_APPLE_IPHONE` сохранённые продажи восьми
подтверждённых чехлов Keephone Mago для iPhone 15 Pro/Pro Max и 16 Pro Max.
База, суммы продаж и зарплатные категории не меняются.

V68 добавляет к числителю `CASE_APPLE_IPHONE` продажи ещё девяти
модельных чехлов Keephone, ранее сохранённые как `OTHER_ACCESSORY_PRODUCT`.
У карточки 5882 постоянная категория дополняется, но её уже правильная
продажа повторно не меняется. База, суммы и зарплатные категории прежние.

V69 исправляет исторический числитель `CASE_APPLE_IPHONE` для чехлов
любых брендов с явной совместимостью iPhone. Продажи, уже имевшие эту
категорию, не изменяются повторно. Смешанные комплекты и ручные решения
другой категории исключены; база, суммы и зарплатные категории прежние.



`OTHER_CASE` — отдельная аналитическая категория чехлов без подтверждённой
совместимости. Она не входит ни в один официальный числитель. Для текущего
периода отдельно показывается предположение: если в исходном чеке есть только
iPhone или только Samsung, нетто-количество чехла прибавляется к
предполагаемому числителю соответствующего бренда. Чек с обоими брендами
является конфликтом; чек без этих телефонов остаётся неопределённым. Это не
меняет подтверждённый attach-rate, рейтинг и зарплату.

Проверяющий принимает решение для **конкретной строки продажи** на основании
маркировки, артикула или документа поставщика, а не только соседства с
телефоном в чеке. Решение хранится неизменяемой ревизией с причиной и автором.
Подтверждённые `CASE_APPLE_IPHONE`/`CASE_SAMSUNG` добавляются как отдельные
аналитические facts к текущему расчёту v3/v4; связанный возврат уменьшает
числитель по дате возврата. Возврат без ссылки на исходную строку не
приписывается догадкой; число таких возвратов отдельно показывается как
предупреждение о возможном завышении показателя. Изменение товара, его названия,
количества, аналитической категории, даты продажи или магазина делает решение
устаревшим и возвращает продажу в очередь. Изменение только цены или себестоимости
не сбрасывает подтверждённую совместимость. Новая ревизия может изменить прежний выбор.
История причин доступна только магазину, принявшему решение; после переноса продажи
новый магазин начинает со следующей общей ревизии без доступа к чужой истории.
Денежная и зарплатная классификация строки остаётся `OTHER_CASE`/`ACCESSORY`.
После сохранения live-показатели перечитываются и инвалидируется текущий
Weekly Review; опубликованные снимки не переписываются.

### Проверки универсальных чехлов, неизвестных стёкол и плёнок

Аддитивный механизм проверки охватывает `CASE_UNIVERSAL`, `GLASS_PHONE_UNRESOLVED`
и `PROTECTIVE_FILM`, сохраняя `OTHER_CASE` и прежний API-путь. Категория строки не меняется.
Чехол подтверждается только как чехол iPhone/Samsung/другого устройства; экранное стекло —
только как стекло iPhone/Samsung/другого телефона; плёнка — телефонная или не телефонная.
Чужой тип цели отклоняется также БД. Неопределённые и отложенные решения не дают N.
Подтверждённые другие устройства и не-телефонная плёнка остаются в деньгах, но не дают
брендовый/телефонный N. Никакие новые знаменатели или веса рейтинга не вводятся.

Официальные v3/v4 числители получают ровно один подтверждённый вклад. Возврат наследует
решение точной исходной строки при совпадении товара, оригинального документа, магазина
и подключения, не раньше исходной продажи. В v3 сохраняется финансовый employee; в v4 —
отдельный автор возврата LiveSklad. Неизвестный автор подтверждённого возврата включается
в quality магазина и сотрудников только затронутой метрики, а не теряется при UNION проекций.
Подсказки estimates относятся только к чехлам; предупреждение несвязанных возвратов охватывает
все четыре типа. Для точного возврата исходной проверяемой строки вклад берётся из её
сохранённого решения один раз; автоматическая строка возврата исключается из второго
подсчёта, даже если категория самой возвратной строки отличается. `DEFER` не даёт
числителя, а `EXCLUDE` исключает возвратный факт из аналитики. Несвязанный возврат
не угадывается по имени. Денежные факты и зарплатные назначения при решении не записываются.

Это реализация **решений по продаже**, не переключение каталога на новые категории.
Датированные роли Watch-зарядок/плёнок подключены по контракту ниже.
Массовая переклассификация истории не выполняется.

## Методика v4

Обычная гарантия относится к типу, дате и продавцу устройства. В одном документе с одним
подходящим типом связь определяется автоматически, включая несколько устройств этого типа.
Новые и Б/У вместе, неподходящая техника, отсутствие точной связи — конфликт. Название гарантии
не выбирает новый/Б/У тип. Отдельная продажа связывается руководителем с конкретной строкой
устройства; в доступном нормализованном источнике отдельный устойчивый ID устройства гарантии
не подтверждён, поэтому эвристического автоматического поиска по клиенту/дате/продавцу нет.

Распределение содержит положительные decimal-количества с точностью до 0,001; сумма равна
количеству источника. Количество устройств не ограничивает число гарантий принудительно:
превышение остаётся предупреждением. Межмагазинные и межинтеграционные ручные связи запрещены.

Возврат гарантии наследует исходное распределение. Полный возврат обнуляет её вклад.
Частичный возврат однозначного распределения уменьшает его; частичный возврат смешанного
распределения требует выбрать возвращённую гарантию вручную. Возврат без оригинала требует
найти исходную гарантию. Превышение исходного/уже возвращённого количества не допускается.
Возврат устройства уменьшает только гарантийную базу в периоде его исходной продажи.

Для остальных 12 метрик, включая Care, дата возврата остаётся фактической, а сотрудник берётся
из строки LiveSklad (`detail.customer.id`, сохраняемый отдельно в
`attach_source_employee_external_id`). Это правило действует и на возвращённую технику в их
знаменателях. Неизвестный сотрудник не заменяется финансовым продавцом: итог магазина учитывает
возврат, employee-сравнение затронутой метрики временно недоступно.

Внутренний seller weekly reader читает потенциальные store-wide риски через отдельную
quality-only проекцию: pending warranties, общее число unassigned ordinary return items и число
таких items для конкретного metric code. Формулы, диапазоны дат и исключения совпадают с
магазинным расчётом; одна строка, участвующая и в numerator, и в denominator одной метрики,
считается один раз. Классификация unassigned returns выполняется один раз за запрос, без
вычисления количественных итогов всего магазина. Seller numerators/denominators и собственные
classification counters не подменяются store-wide значениями. Pending catalog roles и
unassigned metric counters используют общий materialized набор `unassigned_facts`:
одинаковый store/period/negative-quantity/unknown-employee фильтр не запускает тяжёлую
проекцию повторно. Условия отбора pending roles и учитываемых metric rows остаются раздельными.
Из-за стоимости JIT-компиляции
вложенных warranty/catalog views quality-проекция и следующий seller aggregate выполняются
в одной транзакционной области `SET LOCAL jit = off`, без дополнительных SQL round-trips.
`readWith` возвращает исходную настройку только после обоих запросов; caller setting
восстанавливается при успехе, rollback сбрасывает его при ошибке любого из них. Настройки
PostgreSQL/пула глобально не меняются. Legacy v3 не вызывает эту v4
проекцию; публичный store attach API и его quality semantics остаются прежними.

Проверка `catalog_role_review_required` сначала ищет immutable snapshot по первичному
ключу item. При отсутствии snapshot результат `false`, как и у исходного предиката.
При наличии snapshot выполняется прежняя проверка состояния, версии политики и
необработанных возвратов. PL/pgSQL-граница отделяет этот индексный поиск от подготовки
вложенных snapshot views; правила разрешения и закрытия проблем не упрощаются.
В `catalog_review_replaces_automatic` lookup очереди ручного разбора ограничен exact item
до проверки eligibility (`OFFSET 0` как optimization barrier). Это не ограничение числа
строк и не исключение проблем: результат сравнивается с исходной функцией, в том числе
при ручных решениях, stale roles и возвратах.
Перед подготовкой этой проекции проверяется её обязательный предикат
`catalog_accessory_review_required` для exact item. Его исходное условие сохранено
в PL/pgSQL для повторного использования плана. Обычные позиции, не требующие review,
не запускают остальной граф очереди.

Care — отдельный показатель «Премиум-сервис / Протекция»: нормализованные Privilege Care,
Ultimate Care+, Elite Care с допустимыми пробелами/дефисами/подчёркиваниями и префиксом
Future Store. Он не попадает в обычные гарантии. EarPods входят в headphones для Care, но не
в базу AirPods/Watch. Зарядные/соединительные адаптеры из прочих аксессуаров распознаются
по назначению. Настройка ограничена названиями согласованных услуг; ремонт/замена исключены.

## Конфликты, рейтинг и история

Неопределённые гарантии не входят в два гарантийных числителя. Затронутые показатели имеют
`preliminary=true`; в сравнении всех сотрудников с магазином они временно исключены, без
нулевого штрафа. Остальные показатели доступны. Перераспределение весов не гарантирует
сохранения прежнего общего балла/места. Порог базы и формулы рейтинга остаются прежними.

Для смешанного документа без ручного решения ограничение относится к его периоду. Отдельная
гарантия без исходной продажи, возврат и устаревшее ручное решение консервативно ограничивают
обе гарантийные метрики магазина во всех периодах до разбора. Это не приписывает неизвестную
продажу сегодняшнему сотруднику. Полностью возвращённая нераспределённая гарантия остаётся
в очереди проверки связи, но её активное нераспределённое количество равно нулю.

Очередь считает строки, документы и активное нераспределённое количество отдельно. Связанный
возврат не удваивает счётчик нераспределённой исходной гарантии. Предупреждения доступны
отдельным фильтром: больше гарантий, покрытие выше 100%, активная гарантия при возврате
устройства, фактическое число дней задержки, использование одного SKU с обоими типами.
Произвольные пороги «значительно позже» и «регулярно» не устанавливаются.

Решения ALLOCATE/EXCLUDE/DEFER и распределения неизменяемы; новое решение создаёт ревизию.
Автор, время и причина сохраняются. Повтор синхронизации без существенных изменений их
не сбрасывает. Изменение количества, устройства, периода, сотрудника, типа, удаления или
существенной связи делает решение неактуальным и открывает SOURCE_CHANGED. Денежная
стоимость и технический sync timestamp в fingerprint атрибуции не входят.

Контекст source/target fingerprint агрегируется по конкретному document_id через LATERAL.
Добавочная миграция сохраняет ordered MD5, поля, validity predicates и deferred constraints;
старые решения не переписываются, формула и версии атрибуции не меняются. Regression сравнивает
старую и новую source/cases/effective/attach проекции, включая изменения исходных и целевых
документов, Care, возвраты, DEFER/EXCLUDE и новую ревизию исходной гарантии. Это оптимизация
чтения, не разрешение автоматического выбора неизвестных связей или обход проверки при commit.

Новые live-показатели читаются из общей проекции v4. Опубликованные месячные/годовые снимки
не переписываются; формула различима по версии. Изменение атрибуции вызывает повторную проверку
текущего Weekly Review: изменённое содержимое создаёт новую детерминированную ревизию,
неизменившееся повторно использует snapshot ID/hash. Legacy v2 подтверждает только точный DB marker,
прочитанный в одной RR-транзакции с facts, а не время приложения; изменение после чтения остаётся
неподтверждённым. Проверка не зависит от направления сдвига application/DB clock. Старый wall-clock
checkpoint при несовпадении с marker требует повторной проверки, но не лишней финансовой ревизии.
Старое AI-обогащение не переносится на новый snapshot ID. V3 source revision fence остаётся отдельным.

Порядок включения и подготовки истории: [runbook](../../runbooks/attach-attribution.md).
Решение о двух атрибуциях: [ADR-0003](../../decisions/ADR-0003-warranty-attach-attribution.md).

## Детализация AirPods/Apple Watch и новых кодов техники

Оба режима атрибуции читают catalog-проекции со списком `numerator_metric_codes`
на одной исходной строке. Аксессуар AirPods/Apple Watch даёт один дочерний вклад
и входит в общую сводку; это не две проданные единицы и не два возврата в quality.
Справочник метрик содержит два дополнительных знаменателя AIRPODS и APPLE_WATCH.
Новые дочерние показатели всегда справочные, без веса в рейтинге.

Для ACCESSORY_PODS_WATCH в истории явное имя AirPods без Apple Watch даёт подтип
AirPods; Apple Watch/iwatch без AirPods — Watch. Одновременные/неопределённые признаки
не угадываются. Прежнее исключение Samsung/Galaxy/Buds/AirTag сохраняется.
Неуточнённое количество остаётся в общей сводке, а оба дочерних показателя отмечаются
preliminary. Отображается разность исходных N/B сводки и двух детей; при полном
разборе N/B сводки равны сумме детей. Проценты никогда не усредняются.
В v3 прежняя база EarPods остаётся только в сводке, новая база AirPods её не наследует.

TABLET_APPLE сохраняет базу iPad, LAPTOP_APPLE — MacBook, WATCH_APPLE — Apple Watch.
Для GAME_CONSOLES роль PlayStation сохраняется только при явном PlayStation/sony ps.
Другие планшеты, ноутбуки, Samsung/другие часы и неизвестные консоли автоматически
не расширяют базы настройки/Care/Apple. Отсутствующие назначения товаров этим
не создаются; подготовленные категории остаются неактивными до проверки перехода.

### Датированные роли аксессуаров

CURRENT-снимок политики catalog-accessory-roles-v1 применяется обеими catalog-проекциями.
ASSIGNED задаёт конечный N, NO_CONTRIBUTION исключает N, DEFER_TO_EXISTING сохраняет
прежний путь. Watch-only CHARGER_CABLE даёт ACCESSORY_APPLE_WATCH и сводку один раз,
без телефонного CHARGER_CABLE; денежная категория не меняется. Телефонная PROTECTIVE_FILM
даёт FILM_PHONE, подтверждённая не-телефонная — нет. Имя Watch не доказывает исключительность.

STALE/REVIEW/неподдерживаемая версия требуют решения продажи в общей очереди.
Актуальная ручная ревизия имеет приоритет; NO_ATTACH не даёт N, DEFER оставляет проверку
открытой без подтверждённого вклада. Сохранение пересчитывает live-проекции, но не
перезаписывает исходные роли, деньги, зарплатные правила или опубликованные снимки.

Возврат наследует только точный оригинал. Если у автоматической продажи возврат
не имеет текущего унаследованного снимка, исходная продажа требует согласования.
До решения CURRENT-роль продажи и legacy-путь возврата сохраняются; затронутые N
помечаются preliminary. Затем явное решение согласованно замещает оба пути.
Предупреждение присутствует в периоде возврата, даже если продажа вне этого периода.
Одна роль не создаёт второй денежный факт или второй quality-return.
Проверка `catalog_role_pending_issue` читает очередь аксессуаров через коррелированный
`LATERAL` только для текущей строки продажи или её точного оригинала. Барьер
`OFFSET 0` сохраняет результат и не позволяет планировщику разворачивать проверку
в повторный расчёт очереди для посторонних продаж. Приоритет ручного решения,
`DEFER`, признаки STALE/REVIEW, NULL для отсутствующего оригинала, N/B и quality
не меняются; строки, решения и снимки ролей миграция не переписывает.
Регрессия сравнивает старую и новую функции, очередь и обе проекции на CURRENT/STALE,
ручных назначениях/NO_ATTACH/DEFER, частичных и несвязанных возвратах, изменении
периода и удалении возврата. Глобальные настройки PostgreSQL/JIT не меняются.

Writer по умолчанию выключен и требует даты начала; история не заполняется автоматически.
Подробнее — [контракт классификации](classification.md).

## Наушники после аналитического разделения

V62 вводит `HEADPHONES_APPLE`, `HEADPHONES_SAMSUNG` и
`HEADPHONES_OTHER` без новой attach-rate метрики. Обе существующие проекции
сохраняют прежние роли и базы на тех же продажах: AirPods — `AIRPODS`;
Galaxy Buds и другие наушники — `HEADPHONES`. Для проводных EarPods
сохраняется историческое различие методик: v3 — `AIRPODS`, v4 —
`HEADPHONES`. Это аналитическое разделение не меняет формулу и числители
или знаменатели attach-rate.

## Исправление защиты камеры iPhone

V63 переводит две сохранённые позиции товара 5716 из числителя
`GLASS_IPHONE` в `GLASS_CAMERA_IPHONE` и закрепляет правильное
назначение за карточками 5716 и 5162. База для обоих показателей —
iPhone — и формулы v3/v4 остаются прежними. Это исправление исходной
классификации, поэтому исторические значения двух отдельных показателей
изменятся; общий объём продаж и денежные суммы не меняются.
