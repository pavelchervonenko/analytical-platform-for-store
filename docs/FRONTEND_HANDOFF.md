# Store Analytics: контекст и контракт для frontend

Дата актуализации: 2026-08-06.

Этот документ предназначен для разработки и сопровождения SPA. Он фиксирует текущее состояние
проекта, предметную область, права доступа, бизнес-правила и фактически реализованный HTTP API.
Разделы с экранами и пользовательскими сценариями описывают фактически реализованную SPA и ее
текущие границы. Бизнес-правила и API нельзя менять только на стороне frontend: такие изменения
сначала должны быть согласованы с backend.

Практическая карта уже доступных экранов, конкретных кнопок, условий их активности и cache
invalidation вынесена в `docs/frontend-actions.md`. При разработке UI эти два документа нужно
читать вместе.

## 1. Краткий статус

### Реализовано

- Модульный backend на Spring Boot с PostgreSQL и Flyway.
- Серверная аутентификация по email и паролю, cookie-сессии, CSRF, роли `ADMIN` и `MANAGER`.
- Ограничение перебора паролей, принудительная смена временного пароля, ограничение времени и
  количества сессий, закрытая по умолчанию модель маршрутов.
- Доступ пользователей к конкретным магазинам.
- Единая durable-синхронизация магазинов, сотрудников, продаж и возвратов из LiveSklad с аудитом
  исходных версий данных.
- KPI магазина, сотрудников и категорий, средние показатели, динамика и attach-rate.
- Планы магазинов, фактические часы и неполные смены, многомерный рейтинг и карточка сотрудника.
- Исторический рейтинг имеет явный lifecycle `LIVE`/`FINALIZED`; завершённый период фиксируется
  store-scoped POST, после чего поздние изменения источников не меняют ответ.
- Полное выполнение плана магазина: факт, доли, остаток, календарный темп, прогноз, фокусные
  направления и качество данных на явную дату среза.
- Периодный quality API объединяет надежность источников, плана, рейтинга и payroll для выбранных
  магазина, месяца и даты среза; возвращает общий флаг и машинно-читаемые причины/действия.
- Полный backend зарплаты: диагностика, preview фактической комбинации трех независимых планов, расчет, ручные удержания,
  утверждение, выплата, ревизии, аудит и сравнение ревизий.
- Административные API пользователей, версий формул, товарной классификации и синхронизации.
- Реализован `GET /api/stores`: `ADMIN` получает все активные магазины, `MANAGER` — только активные
  магазины из назначений. Ответ содержит название, адрес, timezone и рабочие часы и используется
  настоящим переключателем магазина.
- Реализован GET /api/stores/{storeId}/data-status: дата покрытия продаж и возвратов, отставание, активная синхронизация, последняя безопасная ошибка и число открытых quality issues.
- Реализован React/Vite/TypeScript SPA с React Query, Router, Zod-контрактами, единым
  session/CSRF API client и lazy-loaded разделами.
- Реализован read-only архив неизменяемых месячных и годовых отчётов.
- Реализованы отдельные разделы качества данных, профиля и активных сессий, пользовательская
  привязка Telegram, preview ИИ-разбора и административные экраны LLM/Telegram operations.
- Интерфейс приведен к спокойной Apple-подобной иерархии с сохранением «жидкого стекла», сокращенной
  пользовательской копией и русскими presentation mappings вместо backend enum/reason codes.
- Адаптивность и пользовательские сценарии проверены Playwright на desktop, tablet 768x1024 и
  mobile Pixel 7; подробный результат находится в docs/FRONTEND_ACCEPTANCE.md.

### Еще не реализовано или требует решения

- Нет production deployment, Nginx-конфигурации и публичного окружения.
- LLM/Telegram application-контур реализован, но production feature flags остаются выключенными до
  отдельной staging-приемки и operational drills.
- Нет отдельной аналитики ремонтов.
- Нет sync остатков.

## 2. Назначение продукта

Store Analytics — закрытый кабинет руководителей розничных магазинов. Он показывает показатели
магазина и сотрудников по данным LiveSklad, позволяет вводить общий месячный план и смены, а также
рассчитывать и утверждать зарплату.

Главная архитектурная граница:

```text
Browser SPA -> Store Analytics API -> PostgreSQL <- sync worker <- LiveSklad
```

Frontend никогда не обращается к LiveSklad и не разбирает его payload. Dashboard читает уже
нормализованные данные PostgreSQL через backend. Формулы рейтинга, KPI и зарплаты принадлежат
backend; UI только отправляет входные данные и отображает результат с объяснениями качества.

## 3. Стек и архитектура

### Backend — уже реализован

- Java 21;
- Spring Boot 4.1.0, Spring MVC 7.0.8, Spring Security 7.1.0;
- Spring Data JPA/JDBC;
- PostgreSQL 16 и Flyway;
- OpenAPI/Swagger через springdoc 3.0.3;
- Gradle Kotlin DSL;
- Testcontainers, JUnit, Checkstyle, JaCoCo;
- Docker Compose для dev/production-сборки.

### Frontend — реализовано

- React 19, Vite 8 и TypeScript 6;
- React Router, TanStack React Query и Zod;
- SPA с cookie-based session, без хранения access token;
- один централизованный API client с `credentials: "include"` и CSRF interceptor;
- server state отделён от локального UI state;
- маршруты и кнопки скрываются по роли, но реальная авторизация всегда остается на backend.
- Playwright с отдельными desktop/tablet/mobile проектами и защищенным изменяющим lifecycle-тестом.

Выбор router, библиотеки запросов, форм, таблиц и UI kit пока не является частью backend-контракта.
Если используется генерация типов, ADMIN может получить OpenAPI JSON с `/v3/api-docs`; Swagger UI
доступен на `/swagger-ui/index.html`. Эти маршруты намеренно доступны только `ADMIN`.
Правила совместимости, null-семантика и порядок изменения DTO зафиксированы в
`docs/frontend-contract.md`.

### Рекомендуемые frontend-границы

```text
src/
|-- app/                 # bootstrap, router, providers, route guards
|-- api/                 # общий client, CSRF, ApiError, сгенерированные/ручные типы
|-- auth/                # session bootstrap, login, password change
|-- stores/              # выбранный магазин и период
|-- quality/             # общая сводка и проблемы качества выбранного магазина
|-- dashboard/           # KPI, категории, средние, attach-rate
|-- employees/           # список, рейтинг, карточка, участие
|-- performance/         # план магазина и смены
|-- payroll/             # readiness, preview, run, adjustments, revisions
|-- admin/               # users, formulas, classification, sync
`-- shared/              # форматирование, UI primitives, даты, empty/error states
```

Не переносить backend-формулы в `shared` или frontend selectors. Допустимы только форматирование и
производные представления, которые не меняют смысл ответа.

## 4. Основные термины

| Термин | Значение |
| --- | --- |
| Магазин | Отдельная торговая точка. Пользователь выбирает один магазин и работает в его контексте. |
| Отчетный день | Календарный день `00:00–23:59` в `Europe/Kaliningrad`. |
| Смена | Полная смена `10:00–21:00`, равна 11 часам. Руководитель отмечает состав смены по дням. |
| План магазина | Общий план магазина на календарный месяц. Персональных планов сотрудников нет. |
| Чистая выручка | Завершенные продажи минус учитываемые возвраты. |
| Дополнительная выручка | Аксессуары, кабели/зарядные, стекла/пленки, услуги, гарантии и протекция; устройства в нее не входят. |
| Attach-rate | Чистое количество единиц допа на 100 чистых единиц релевантной техники; возвраты вычитаются. |
| Участник рейтинга | Активный сотрудник с активным назначением, включенным `participatesInRanking` и хотя бы одной сменой. |
| Покрытие рейтинга | Доля направлений формулы, для которых достаточно данных. Место появляется при покрытии не менее 75%. |
| Dense rank | Равные баллы занимают одно место: `1, 1, 2`. |
| Зарплатный фонд дня | Сумма вознаграждений по пяти компонентам, делимая поровну между сотрудниками смены. |
| Payroll run | Сохраненный расчет зарплаты магазина за месяц. |
| Ревизия | Неизменяемая версия расчета. Новый расчет после утверждения/выплаты создает следующую ревизию. |
| Удержание | Ручное вычитание типа `PENALTY`, `INVENTORY` или `TAX`. Положительная сумма в API уменьшает выплату. |
| Readiness | Диагностика возможности рассчитать и утвердить зарплату. |
| Data quality | Явные признаки неполных/неоднозначных данных; UI не должен заменять `null` нулем. |
| Актуальность данных | Общая дата полного покрытия продаж и возвратов. Текущий незавершенный день не обязан быть загружен. |
| Optimistic version | Поле `version`, которое нужно вернуть при изменении. Ответ `409` означает устаревшие данные. |

## 5. Роли и права доступа

Публичной регистрации нет. Пользователей создает администратор с временным паролем.

| Возможность | `MANAGER` | `ADMIN` |
| --- | :---: | :---: |
| Вход, профиль, смена своего пароля | Да | Да |
| Просмотр KPI | Только назначенные магазины | Все магазины |
| Статус, общая и периодная проверка качества | Только назначенные магазины | Все магазины |
| Сотрудники, рейтинг, карточки | Только назначенные магазины | Все магазины |
| Изменение участия в рейтинге | Только назначенные магазины | Все магазины |
| Фиксация исторического рейтинга | Только назначенные магазины | Все магазины |
| План магазина и смены | Только назначенные магазины | Все магазины |
| Readiness, preview и расчет зарплаты | Только назначенные магазины | Все магазины |
| Удержания, утверждение и выплата | Только назначенные магазины | Все магазины |
| Пользователи и доступ к магазинам | Нет | Да |
| Версии формул рейтинга/зарплаты | Нет | Да |
| Товарная классификация зарплаты | Нет | Да |
| Sync/backfill, Swagger, actuator metrics | Нет | Да |

Все три руководителя заказчика считаются главными, поэтому широкие бизнес-права руководителя внутри
доступного магазина являются ожидаемым поведением. Разница ролей в основном в администрировании
системы и ограничении набора магазинов.

```ts
type UserRole = "ADMIN" | "MANAGER";

interface CurrentUser {
  id: string;
  email: string;
  displayName: string;
  role: UserRole;
  passwordChangeRequired: boolean;
  allStores: boolean;
  storeIds: string[];
}
```

Если `passwordChangeRequired=true`, пользователь может вызывать только `/api/auth/me`,
`/api/auth/change-password`, `/api/auth/sessions/**` и `/api/auth/logout`. SPA должна
принудительно отправить его на экран смены пароля. После успешной смены сервер завершает сессию;
нужен новый вход.

## 6. Экраны и сценарии

Ниже — рекомендуемая информационная архитектура. Точные mutation, кнопки и условия их доступности
зафиксированы в `docs/frontend-actions.md` и проверены по service invariants.

### 6.1. Вход

- Email и пароль.
- Ошибка входа всегда общая, без указания существования пользователя.
- При `429 LOGIN_THROTTLED` показать ограничение попыток и учесть `Retry-After`.
- После входа: обязательная смена пароля либо последнее рабочее пространство.

### 6.2. Смена временного пароля

- Текущий и новый пароль.
- После `204` очистить frontend session state и открыть login.
- Требования: 12–128 Java-символов, не более 72 UTF-8 байт для bcrypt, без управляющих символов,
  не из локального списка распространенных паролей. Backend остается источником истины валидации.

### 6.3. Общая оболочка

- Выбор магазина.
- Выбор периода: неделя, месяц, произвольный включительный интервал.
- Навигация: «Обзор», «ИИ-разбор» при включенном preview, «Сотрудники», «План и смены»,
  «Зарплата», «Отчеты», «Качество данных»; для ADMIN — «Настройки».
- Профиль и выход.
- Выбранные `storeId`, `periodStart`, `periodEnd` должны быть частью query/cache key.

### 6.4. Обзор магазина

Предлагаемый состав:

- чистая выручка, количество, себестоимость, валовая прибыль, маржа;
- план и процент выполнения;
- бизнес-группы `PHONES`, `DEVICES`, `ADDITIONAL_REVENUE`;
- категории и предупреждения качества;
- средний чек, допвыручка на телефон, средняя цена по категориям и динамика;
- attach-rate как таблица/тепловая карта;
- фактический статус данных, дата покрытия, отставание и предупреждение о quality issues;
- переходы к сотрудникам и деталям проблем данных.

### 6.5. Сотрудники

- Общий список за период: место, общий балл, четыре направления, смены/часы, выручка, структура,
  attach-rate и динамика.
- Поиск и визуальная сортировка допустимы, но исходное `rank` нельзя пересчитывать на frontend.
- Сотрудник вне рейтинга остается видимым; вместо места показывается причина/«не участвует».
- Отдельное действие руководителя для `participatesInRanking` с обработкой `version` и `409`.

### 6.6. Карточка сотрудника

- Текущий и предыдущий равный период.
- Место, общий балл, изменение места и балла.
- Четыре направления с исходными метриками и вкладом в итог.
- Смены, часы, выручка за смену/час, доли аксессуаров/услуг/допродаж.
- Attach-rate по каждой метрике: числитель, знаменатель, значение сотрудника, значение магазина,
  участие в расчете и динамика.
- Для полного календарного месяца — последняя рассчитанная зарплатная ведомость, если существует.

### 6.7. Планы и смены

- Для выбранного месяца и даты среза загружать единый progress endpoint.
- Показывать денежное выполнение отдельно от критерия: у аксессуаров, услуг и допродаж критерием
  остается доля в фактической выручке.
- `focusDirections` и `status` приходят с backend; frontend не повторяет формулы темпа и прогноза.

- Месячная форма общего плана магазина: выручка и три целевые доли.
- Календарь/таблица смен с редактированием фактических часов: `0.01..11.00`, полная смена — 11.
- Сохранение одного дня полностью заменяет его состав; пустой `shifts` очищает день.
- В зарплате часы показываются для аудита, но текущий дневной фонд делится поровну по участникам.
- Нельзя создавать персональный план сотрудника.

### 6.8. Зарплата

Рекомендуемый workflow одного месяца:

1. Показать readiness и блокирующие причины.
2. Показать preview фактической комбинации трех независимых статусов плана без сохранения.
3. Показать три фактических статуса плана и примененный набор ставок.
4. Рассчитать первую ревизию или пересчитать текущую `CALCULATED`.
5. Показать дневные фонды, распределения, ведомости и аудит.
6. Добавить или отменить удержание в последней `CALCULATED` ревизии.
7. Утвердить полный расчет.
8. Отметить утвержденный расчет выплаченным.
9. При новом расчете после утверждения/выплаты запросить обязательную причину ревизии.
10. Сравнить две ревизии и показать машинно-читаемые причины изменений.

Для кнопок утверждения и выплаты использовать `run.freshness.requiresRecalculation`: при `true`
действие блокируется, показываются `reasons` и предлагается перерасчет. Backend повторно проверяет
источники в момент перехода; `409 PAYROLL_SOURCE_DATA_CHANGED` означает, что нужно перечитать run.

Нельзя разрешать редактирование старой/утвержденной ревизии. При `409` нужно перезагрузить run и
предложить повторить действие на актуальной версии.

### 6.9. ИИ-разбор

- Полноценный недельный разбор расположен только на `/insights`; на обзоре магазина его не
  дублировать.
- Период интерпретации фиксирован backend как понедельник–воскресенье и подписан отдельно от
  selector периода остальных KPI.
- Для `READY`-ответа `content.evidence` индексируется по `evidenceCode`. Каждый
  `evidenceRefs` конкретного вывода разрешается через этот индекс, а подтверждающие карточки
  показываются непосредственно рядом с выводом.
- Frontend не разбирает opaque-коды `EV001`, не обращается к snapshot и не пересчитывает значения:
  label, current/previous, delta, comparison, sufficiency и scope уже подготовлены backend.
- Неизвестный код не подменяется догадкой и не показывается как техническая ссылка. В consumer UI
  также не должны появляться snapshot refs вида `STORE.*`, `EMP:E01.*` или псевдонимы `E01`.
- Для недоступного evidence показывается безопасный label/статус, но не выдуманное числовое
  значение.

Подробный lifecycle и ограничения модели зафиксированы в `docs/llm-notifications-design.md`.

### 6.10. Администрирование

Только `ADMIN`:

- пользователи, роль, активность, доступ к магазинам, сброс временного пароля;
- неизменяемые версии формул рейтинга и зарплаты;
- назначения зарплатных категорий товарам;
- backfill/sync и состояние jobs;
- восстановление архива отчетов, ИИ-разбор и Telegram delivery operations;
- технический Swagger — отдельный dev/служебный инструмент, не пользовательский экран.

## 7. Бизнес-логика

### 7.1. Периоды и даты

- `periodStart` и `periodEnd` — ISO `YYYY-MM-DD`, обе даты включаются.
- Аналитический период содержит не более 366 календарных дней включительно; это ограничение
  независимо проверяют и frontend, и backend.
- Отчетный timezone — `Europe/Kaliningrad` (`UTC+02:00`).
- Неделя: понедельник–воскресенье.
- Месяц в path — `YYYY-MM`.
- Сравнение динамики идет с непосредственно предыдущим периодом той же длины.
- Frontend не должен строить UTC-интервалы для KPI: в API передаются календарные даты.

### 7.2. Продажи, возвраты и качество

```text
Чистая выручка = завершенные продажи - возвраты периода
Продано = проданные единицы - возвращенные единицы
Себестоимость = себестоимость продаж - себестоимость возвратов
Валовая прибыль = чистая выручка - себестоимость
Маржа = валовая прибыль / чистая выручка * 100
```

- Возврат в KPI учитывается датой возврата и относится к продавцу исходной продажи.
- Удаленные/отмененные документы, удаленные позиции, ремонты и `EXCLUDE` не входят в KPI.
- `UNMAPPED` остается в финансовых итогах и отражается в качестве данных.
- Если себестоимость неполна, зависимые `costAmount`, `grossProfit`, `marginPercent` равны `null`.
- При нулевом знаменателе метрика равна `null`, а не нулю.

### 7.3. Категории

- `PHONES` — телефонные категории.
- `DEVICES` — все устройства, включая телефоны.
- `ADDITIONAL_REVENUE` — допродажи, без устройств.
- Группы пересекаются: их нельзя складывать.
- Категории в ответе не пересекаются и используют snapshot на момент нормализации продажи.
- Поздняя переклассификация не переписывает историю.

### 7.4. Attach-rate

```text
attach-rate = max(0, чистое количество допа)
              / чистое количество релевантной техники * 100
```

Каждая единица учитывается отдельно, совместный чек не требуется, возвраты уменьшают числитель или
соответствующую базу. Гарантия для новых и Б/У телефонов считается раздельно.
`ratePerHundred`/`ratePercent` равен `null`, когда чистой релевантной техники нет.

### 7.5. Рейтинг сотрудников

Рейтинг не является формулой зарплаты. Первая версия `employee-rating-v1`:

| Направление | Вес | Смысл |
| --- | ---: | --- |
| Коммерческий вклад | 25% | Выручка сотрудника относительно средней выручки кандидатов магазина. |
| Эффективность времени | 25% | Выручка за час относительно средней по кандидатам. |
| Структура продаж | 25% | Выполнение долей аксессуаров и услуг, внутренние веса 50/50. |
| Интенсивность attach-rate | 25% | Среднее отношение attach-rate сотрудника к магазину. |

- База направления — 100, верхний предел — 150, отрицательное отношение дает 0.
- Attach-метрика входит при знаменателе сотрудника не менее 3 и положительной базе магазина.
- Недоступное направление исключается, а `coveragePercent` показывает покрытие.
- Место дается при покрытии от 75%.
- Для кандидата обязательна хотя бы одна смена.
- Изменение весов создает новую effective-dated схему, старые версии не редактируются.
- План только общий для магазина; для произвольного периода он пропорционально распределяется по
  календарным дням. При неполном покрытии планами структура не оценивается.

### 7.6. Зарплата

Рейтинг и место не участвуют в зарплате. Backend независимо проверяет три показателя общего плана
магазина. Выручка управляет только ставками техники, доля аксессуаров — только процентом
аксессуаров, доля услуг — процентом услуг, PlayStation-подписок и платных ремонтов. Получившаяся
комбинация ставок применяется ко всем дням месяца:

| Компонент фонда | План выполнен | План не выполнен |
| --- | ---: | ---: |
| Аксессуары (план аксессуаров) | 20% оборота | 15% оборота |
| Обычные услуги (план услуг) | 20% оборота | 15% оборота |
| Подписки PlayStation | 20% валовой прибыли | 15% валовой прибыли |
| Платный ремонт | 20% валовой прибыли | 15% валовой прибыли |
| Техника `TECH_TIER_1` | 500 ₽/ед. | 400 ₽/ед. |
| Техника `TECH_TIER_2` | 300 ₽/ед. | 200 ₽/ед. |

- Фонд каждого дня поровну делится между сотрудниками смены этого дня.
- Продавец, оформивший конкретную продажу, не влияет на распределение фонда.
- Начисление сотрудника — сумма дневных долей.
- Из начисления вычитаются аванс 50 000 ₽ и активные `PENALTY`, `INVENTORY`, `TAX`.
- Минимума выплаты нет; `payableAmount` может быть отрицательным.
- Зарплатный возврат уменьшает фонд дня и месяца оформления возврата. Товарная категория
  определяется на дату исходной продажи, а ставки — планами месяца возврата.
- Ведомость всегда относится к одному магазину. Один сотрудник, работавший в двух магазинах,
  получает две независимые ведомости одного месяца.
- `UNMAPPED`, отсутствующая себестоимость нужных компонентов и продажи без смены блокируют
  утверждение. UI должен показывать `canCalculate` и более строгий `canApprove` отдельно.

## 8. Общие правила HTTP API

### 8.1. Transport

- Base path: `/api`.
- JSON: `Content-Type: application/json`.
- UUID сериализуется строкой.
- `LocalDate`: `YYYY-MM-DD`; месяц: `YYYY-MM`; `Instant`: ISO-8601 с timezone/`Z`.
- Java `BigDecimal` приходит JSON-числом. Не использовать JS-вычисления как источник финансового
  результата; деньги уже рассчитаны backend.
- Все browser-запросы выполняются с `credentials: "include"`.
- CORS разрешает только явно настроенные origins и cookies; wildcard не поддерживается.

### 8.2. Session и CSRF

```text
1. GET  /api/auth/csrf
2. POST /api/auth/login + X-XSRF-TOKEN
3. GET  /api/auth/csrf повторно, потому что после login токен ротируется
4. GET  /api/auth/me, затем GET /api/stores и загрузка рабочего пространства
```

Ответ `/api/auth/csrf`:

```json
{"headerName":"X-XSRF-TOKEN","cookieName":"XSRF-TOKEN"}
```

Cookie `XSRF-TOKEN` доступна JavaScript; ее сырое значение отправляется в заголовке, указанном
сервером. `JSESSIONID` нельзя читать из JS. Idle timeout по умолчанию — 30 минут, absolute timeout —
12 часов, максимум — 3 активные сессии; четвертая вытесняет самую старую.

Профиль может показать `GET /api/auth/sessions`: только opaque `sessionReference`, `lastSeenAt`
и `current`, без IP/User-Agent. DELETE одной другой сессии и
`DELETE /api/auth/sessions/others` требуют обычный CSRF header. Current row должна предлагать
logout, а не DELETE; `409 CURRENT_SESSION_REQUIRES_LOGOUT` означает перечитать список и использовать
logout. После revoke другой вкладке при следующем запросе придёт `401 SESSION_EXPIRED`.

### 8.3. Ошибки

```ts
interface ApiError {
  timestamp: string;
  status: number;
  code: string;
  message: string;
  path: string;
  correlationId: string;
}
```

Основные реакции UI:

| HTTP | Примеры code | Поведение |
| ---: | --- | --- |
| 400 | `INVALID_ARGUMENT` | Показать ошибки формы/периода. |
| 401 | `AUTHENTICATION_REQUIRED`, `INVALID_CREDENTIALS`, `SESSION_EXPIRED` | Для защищенного запроса очистить session state и открыть login; login-ошибку показать локально. |
| 403 | `ACCESS_DENIED` | Нет роли/магазина либо требуется смена пароля. Проверить `/auth/me`. |
| 404 | `STORE_NOT_FOUND`, `PERFORMANCE_PLAN_NOT_FOUND` | Показать соответствующий empty state; отсутствие плана — ожидаемый сценарий создания. |
| 409 | доменный конфликт, `IDEMPOTENCY_KEY_CONFLICT`, `CURRENT_SESSION_REQUIRES_LOGOUT` | Перечитать ресурс/session list; ключ с другим request body не использовать повторно. |
| 413 | `PAYLOAD_TOO_LARGE` | Прекратить отправку body и показать безопасную ошибку размера. |
| 412 | `PRECONDITION_FAILED` | Ресурс изменён после GET; перечитать и повторно проверить форму. |
| 428 | `PRECONDITION_REQUIRED` | Mutation не содержит обязательный ETag/create precondition. |
| 422 | ограничение бизнес-операции/синхронизации | Показать безопасное сообщение backend. |
| 429 | `LOGIN_THROTTLED` | Заблокировать повтор на время `Retry-After`. |
| 502 | ошибка внешнего источника | Сообщить о временной проблеме данных, не показывать raw upstream body. |
| 500 | `INTERNAL_ERROR` | Показать нейтральную ошибку. `correlationId` сохранить в transport diagnostics, но не выводить обычному пользователю. |

Frontend принимает решения по стабильному `code`, а не по `message`. Не выводить пользователю
stack trace, HTML-ошибку proxy или неизвестный объект целиком. Полные правила находятся в
`docs/error-handling.md`.

### 8.4. Idempotency и optimistic locking

`version` из последнего GET передается в mutation для участия в рейтинге, payroll run, удержания,
отмены удержания и переходов статуса. Все пять изменяющих payroll POST также требуют
`Idempotency-Key` из 8–100 безопасных символов. Ключ принадлежит user/action/resource/body на 24
часа: тот же запрос возвращает сохранённый response, а другое тело с тем же ключом получает
`409 IDEMPOTENCY_KEY_CONFLICT`. Frontend transport сохраняет ключ при timeout, network error и 5xx,
повторяет его для того же запроса и ротирует после достоверного ответа.

План и полный день графика используют strong ETag. GET плана возвращает `ETag`; update отправляет
его в `If-Match`, а создание после ожидаемого `404 PERFORMANCE_PLAN_NOT_FOUND` отправляет
`If-None-Match: *`. Перед replace-day frontend читает отдельный ресурс дня, включая пустой день
revision 0, и передаёт его ETag в `If-Match`. Устаревший токен получает
`412 PRECONDITION_FAILED`, отсутствующий — `428 PRECONDITION_REQUIRED`. После `412` нельзя
слепо повторять запись: перечитать ресурс и дать пользователю проверить актуальные значения.

## 9. API contract

Ниже перечислены фактически существующие endpoints. Имена response DTO совпадают с backend/OpenAPI.
Для больших аналитических ответов поля перечислены в документах из раздела 11 и в `/v3/api-docs`.

### 9.1. Auth и система

| Method | Path | Доступ | Request | Response |
| --- | --- | --- | --- | --- |
| GET | `/api/auth/csrf` | Public | — | `CsrfConfigurationResponse` |
| POST | `/api/auth/login` | Public + CSRF | `{email, password}` | `CurrentUserResponse` |
| GET | `/api/auth/me` | Authenticated | — | `CurrentUserResponse` |
| GET | `/api/auth/sessions` | Authenticated | — | `ActiveSessionListResponse` |
| DELETE | `/api/auth/sessions/{sessionReference}` | Authenticated + CSRF | — | `204` |
| DELETE | `/api/auth/sessions/others` | Authenticated + CSRF | — | `204` |
| POST | `/api/auth/change-password` | Authenticated + CSRF | `{currentPassword, newPassword}` | `204` |
| POST | `/api/auth/logout` | Authenticated + CSRF | — | `204` |
| GET | `/api/system/status` | Password changed | — | `{application, version, time}` |
| GET | `/api/stores` | Password changed | — | `StoreSummaryView[]` |
| GET | `/api/stores/{storeId}/data-status` | Store access | — | `StoreDataStatusView` |
| GET | `/api/data-quality/summary` | Password changed | — | `DataQualityOverviewView` |
| GET | `/api/stores/{storeId}/data-quality` | Store access | — | `StoreDataQualityView` |
| GET | `/api/stores/{storeId}/period-quality/{YYYY-MM}?asOf=YYYY-MM-DD` | Store access | — | `StorePeriodQualityView` |

```ts
interface StoreSummaryView {
  id: string;
  name: string;
  address: string | null;
  timezone: string;
  businessDayStart: string; // ISO local time, например 00:00:00
  opensAt: string;          // например 10:00:00
  closesAt: string;         // например 21:00:00
  active: boolean;
}
```

Полный контракт свежести и рекомендации polling: `docs/store-data-status-api.md`. Store-wide
сводка описана в `docs/data-quality-api.md`. Для выбранного месяца frontend использует
`period-quality`: он объединяет исходные данные, план, рейтинг и payroll, а детальные исправления
по-прежнему выполняются через специализированные endpoints.

### 9.2. KPI

У всех KPI обязательны `periodStart` и `periodEnd`; границы включительные, максимальная
продолжительность периода — 366 календарных дней.

| Method | Path | Response |
| --- | --- | --- |
| GET | `/api/stores/{storeId}/kpi` | `StoreKpiResult` |
| GET | `/api/stores/{storeId}/kpi/employees` | `EmployeeKpiResult` |
| GET | `/api/stores/{storeId}/kpi/categories` | `CategoryKpiResult` |
| GET | `/api/stores/{storeId}/kpi/attach-rates` | `AttachRateResult` |
| GET | `/api/stores/{storeId}/kpi/averages` | `AverageKpiResult` |

Ключевые формы:

```ts
interface StoreKpiResult {
  storeId: string;
  periodStart: string;
  periodEnd: string;
  formulaVersion: "store-kpi-v1" | string;
  netRevenue: number;
  netQuantity: number;
  costAmount: number | null;
  grossProfit: number | null;
  marginPercent: number | null;
  dataQuality: {
    completeCostData: boolean;
    includedItemCount: number;
    unmappedItemCount: number;
    missingCostItemCount: number;
    unexpectedZeroCostItemCount: number;
    storeOpenQualityIssueCount: number;
  };
}

interface AttachRateEntry {
  metricCode: string;
  numeratorCategoryCode: string;
  denominatorCode: string;
  numeratorQuantity: number;
  denominatorQuantity: number;
  /** @deprecated compatibility field; value is a quantity, not receipts */
  numeratorReceiptCount: number;
  /** @deprecated compatibility field; value is a quantity, not receipts */
  denominatorReceiptCount: number;
  ratePerHundred: number | null;
}
```

`CategoryKpiResult` содержит `groups[]` и `categories[]`; каждый элемент содержит `metrics` с
выручкой, количеством, cost/profit/margin и локальным data quality. `AverageKpiResult` содержит
текущий и предыдущий периоды, `averageReceipt`, `additionalRevenuePerPhone` и
`categoryAveragePrices[]`; `value` и `changePercent` nullable.

### 9.3. Сотрудники, рейтинг, план и смены

| Method | Path | Request/response |
| --- | --- | --- |
| GET | `/api/stores/{storeId}/employees?periodStart&periodEnd` | `EmployeeDirectoryView` |
| GET | `/api/stores/{storeId}/employees/{employeeId}?periodStart&periodEnd` | `EmployeeCardView` |
| GET | `/api/stores/{storeId}/employee-ratings?periodStart&periodEnd` | `EmployeeRatingResult` |
| POST | `/api/stores/{storeId}/employee-ratings/finalize?periodStart&periodEnd` | `EmployeeRatingResult` with `FINALIZED` history |
| GET | `/api/stores/{storeId}/employee-rating-settings` | `EmployeeRatingSettingView[]` |
| PUT | `/api/stores/{storeId}/employee-rating-settings/{employeeId}` | `{participatesInRanking, version}` -> setting |
| GET | `/api/stores/{storeId}/performance-plans/{YYYY-MM}` | `StorePerformancePlanView` |
| GET | `/api/stores/{storeId}/performance-plans/{YYYY-MM}/progress?asOf=YYYY-MM-DD` | `StorePlanProgressView` |
| PUT | `/api/stores/{storeId}/performance-plans/{YYYY-MM}` | conditional headers + plan payload -> plan view + ETag |
| GET | `/api/stores/{storeId}/work-schedule?periodStart&periodEnd` | `EmployeeShiftView[]` |
| GET | `/api/stores/{storeId}/work-schedule/{YYYY-MM-DD}` | `WorkScheduleDayView` + ETag |
| PUT | `/api/stores/{storeId}/work-schedule/{YYYY-MM-DD}` | If-Match + shifts -> `WorkScheduleDayView` + ETag |

План:

```ts
interface StorePerformancePlanInput {
  revenueTarget: number;          // > 0, 2 decimals
  accessoryShareTarget: number;   // 0..100
  serviceShareTarget: number;     // 0..100
  additionalShareTarget: number;  // 0..100
}

interface StorePerformancePlanView extends StorePerformancePlanInput {
  id: string;
  storeId: string;
  planMonth: string;
  updatedBy: string;
  version: number;
  updatedAt: string;
}
```

Смена:

```ts
interface WorkScheduleShiftInput {
  employeeId: string;
  workedHours: number; // 0.01..11.00, максимум 2 знака
}

interface WorkScheduleRequest {
  shifts: WorkScheduleShiftInput[]; // [] полностью очищает день
}

interface EmployeeShiftView extends WorkScheduleShiftInput {
  id: string;
  employeeName: string;
  workDate: string;
  active: boolean;
  version: number;
}

interface WorkScheduleDayView {
  storeId: string;
  workDate: string;
  revision: number; // 0 для ещё не изменённого пустого дня
  shifts: EmployeeShiftView[];
}
```

Старый payload `{employeeIds: string[]}` временно поддерживается как полная 11-часовая смена, но
новый frontend должен всегда отправлять `shifts`.

Основной элемент рейтинга:

```ts
interface EmployeeRatingEntry {
  employeeId: string;
  displayName: string;
  employeeActive: boolean;
  assignmentActive: boolean;
  participatesInRanking: boolean;
  ratingEligible: boolean;
  shiftCount: number;
  workedHours: number;
  netRevenue: number;
  storeRevenueSharePercent: number | null;
  revenuePerShift: number | null;
  revenuePerHour: number | null;
  accessoryRevenue: number;
  accessorySharePercent: number | null;
  serviceRevenue: number;
  serviceSharePercent: number | null;
  additionalRevenue: number;
  additionalSharePercent: number | null;
  scores: {
    contributionScore: number | null;
    contributionWeightedPoints: number | null;
    efficiencyScore: number | null;
    efficiencyWeightedPoints: number | null;
    structureScore: number | null;
    structureWeightedPoints: number | null;
    attachScore: number | null;
    attachWeightedPoints: number | null;
    coveragePercent: number;
    overallScore: number | null;
  };
  ranked: boolean;
  rank: number | null;
  attachRates: EmployeeAttachRatingEntry[];
}
```


```ts
type EmployeeRatingHistoryStatus = "LIVE" | "FINALIZED";

interface EmployeeRatingHistoryView {
  status: EmployeeRatingHistoryStatus;
  snapshotId: string | null;
  finalizedAt: string | null;
  finalizedBy: string | null;
  finalizedByName: string | null;
}

interface EmployeeRatingResult {
  storeId: string;
  periodStart: string;
  periodEnd: string;
  formula: RatingFormulaView;
  plan: RatingPlanContext;
  employees: EmployeeRatingEntry[];
  history: EmployeeRatingHistoryView;
}

```

До фиксации UI показывает живой статус и допускает действие «Зафиксировать» только для периода,
дата окончания которого уже прошла в timezone магазина. После `FINALIZED` действие скрывается,
показываются автор и время, а данные считаются историческими. Повторный POST безопасен и вернёт тот
же `snapshotId`. Ошибка открытого периода: `409 RATING_PERIOD_NOT_CLOSED`.

`EmployeeDirectoryView.employees[]` оборачивает `current` и `dynamics`. `EmployeeCardView`
добавляет `formula`, `plan`, `previous`, `dynamics` и nullable `payroll` для полного месяца.

### 9.4. Зарплата

| Method | Path | Request/response |
| --- | --- | --- |
| GET | `/api/stores/{storeId}/payroll/{YYYY-MM}/readiness` | `PayrollReadinessView` |
| GET | `/api/stores/{storeId}/payroll/{YYYY-MM}/preview` | `PayrollPreviewView` |
| POST | `/api/stores/{storeId}/payroll/{YYYY-MM}/calculate` | `{revisionReason}` условно обязателен после `APPROVED`/`PAID`; иначе body может быть `{}` -> `PayrollRunDetailView` |
| GET | `/api/stores/{storeId}/payroll/{YYYY-MM}` | latest `PayrollRunDetailView` |
| GET | `/api/stores/{storeId}/payroll-runs?month&page&size` | Page envelope с lightweight payroll revision items. |
| GET | `/api/stores/{storeId}/payroll-runs/{runId}` | `PayrollRunDetailView` |
| POST | `/api/stores/{storeId}/payroll-runs/{runId}/adjustments` | adjustment -> detail |
| POST | `/api/stores/{storeId}/payroll-runs/{runId}/adjustments/{adjustmentId}/void` | void payload -> detail |
| POST | `/api/stores/{storeId}/payroll-runs/{runId}/approve` | `{version}` -> detail |
| POST | `/api/stores/{storeId}/payroll-runs/{runId}/paid` | `{version}` -> detail |
| GET | `/api/stores/{storeId}/payroll-runs/{previousRunId}/compare/{currentRunId}` | comparison |

Для `calculate`, добавления/отмены удержания, `approve` и `paid` обязателен header
`Idempotency-Key`. OpenAPI contract version 2 ввёл это требование; текущая version 7 сохраняет его.
Тот же header обязателен для `POST /api/admin/reports/backfill`. Version 7 исправляет прежнюю
optional-аннотацию в OpenAPI; runtime и до этого отклонял запрос без корректного ключа.

Для расчета после `APPROVED`/`PAID` пустой или отсутствующий `revisionReason` возвращает
`400 INVALID_ARGUMENT`. Изменять удержания после этих статусов нельзя: ответ
`409 PAYROLL_STATE_CONFLICT`.

Ключ нельзя строить из email, store ID или другого предсказуемого business value; SPA использует
`crypto.randomUUID()` и не журналирует header.

```ts
type PayrollRunStatus = "CALCULATED" | "APPROVED" | "PAID";
type PayrollFreshnessStatus = "CURRENT" | "STALE" | "UNKNOWN";
type PayrollStaleReason =
  | "SALES_DATA_CHANGED"
  | "WORK_SHIFTS_CHANGED"
  | "STORE_PLAN_CHANGED"
  | "PRODUCT_CLASSIFICATION_CHANGED"
  | "PAYROLL_SCHEME_CHANGED"
  | "SOURCE_FINGERPRINT_MISSING";

interface PayrollFreshness {
  status: PayrollFreshnessStatus;
  requiresRecalculation: boolean;
  reasons: PayrollStaleReason[];
  checkedAt: string;
}
type PayrollReadinessStatus = "READY" | "NEEDS_CORRECTION" | "BLOCKED";
type PayrollAdjustmentType = "PENALTY" | "INVENTORY" | "TAX";

interface PayrollAdjustmentInput {
  employeeId: string;
  type: PayrollAdjustmentType;
  amount: number;       // > 0
  reason: string;
  runVersion: number;
}

interface PayrollVoidAdjustmentInput {
  reason: string;
  runVersion: number;
  adjustmentVersion: number;
}

interface PayrollPlanResult {
  revenueTarget: number;
  actualRevenue: number;
  revenueAchieved: boolean;
  accessoryShareTarget: number;
  actualAccessoryTurnover: number;
  actualAccessorySharePercent: number | null;
  accessoryAchieved: boolean;
  serviceShareTarget: number;
  actualServiceTurnover: number;
  actualServiceSharePercent: number | null;
  serviceAchieved: boolean;
}

interface PayrollAppliedRates {
  accessoryPercentage: number;
  servicePercentage: number;
  tier1Rate: number;
  tier2Rate: number;
}

interface PayrollRunSummary {
  id: string;
  storeId: string;
  periodMonth: string;
  revision: number;
  supersedesRunId: string | null;
  revisionReason: string | null;
  status: PayrollRunStatus;
  freshness: PayrollFreshness;
  planResult: PayrollPlanResult;
  calculationComplete: boolean;
  unmappedItemCount: number;
  missingCostItemCount: number;
  daysWithoutShift: number;
  createdBy: string;
  approvedBy: string | null;
  approvedAt: string | null;
  paidBy: string | null;
  paidAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

interface PayrollStatement {
  id: string;
  employeeId: string;
  employeeName: string;
  shiftCount: number;
  workedHours: number;
  earnedAmount: number;
  advanceAmount: number;
  penaltyAmount: number;
  inventoryAmount: number;
  taxAmount: number;
  payableAmount: number;
}

interface PayrollRunDetail {
  run: PayrollRunSummary;
  scheme: PayrollScheme;
  dailyPools: PayrollDailyPool[];
  dailyAllocations: PayrollDailyAllocation[];
  adjustments: PayrollAdjustment[]; // включая отмененные
  statements: PayrollStatement[];
  events: PayrollEvent[];
}
```

`PayrollPreviewView` содержит `readiness`, `planResult` и единственный фактический
`actualScenario`. Сценарий содержит `appliedRates`, `days[]`, `employees[]`,
`totalFundAmount`, `totalPayableAmount` и `calculationComplete`. В дневных данных аксессуары
и обычные услуги представлены раздельно; PlayStation-подписки и платные ремонты имеют собственные
поля валовой прибыли и вознаграждения, но используют `servicePercentage`. Frontend не выбирает и
не комбинирует ставки самостоятельно.

### 9.5. Администрирование пользователей

| Method | Path | Request/response |
| --- | --- | --- |
| GET | `/api/admin/users?page&size` | Page envelope с `AdminUserResponse`. |
| POST | `/api/admin/users` | create payload -> `201 AdminUserResponse` |
| PUT | `/api/admin/users/{userId}` | update payload -> user |
| PUT | `/api/admin/users/{userId}/store-access` | `{storeIds}` -> user |
| POST | `/api/admin/users/{userId}/reset-password` | `{temporaryPassword}` -> user |

```ts
interface CreateUserInput {
  email: string;
  temporaryPassword: string;
  displayName: string;
  role: "ADMIN" | "MANAGER";
  storeIds: string[];
}

interface UpdateUserInput {
  displayName: string;
  role: "ADMIN" | "MANAGER";
  active: boolean;
}
```

Нельзя деактивировать/понизить последнего активного администратора, менять собственную роль или
деактивировать самого себя. Такие конфликты возвращаются как `409`.

### 9.6. Административные формулы и классификация

| Method | Path | Назначение |
| --- | --- | --- |
| GET | `/api/admin/rating-schemes?page&size` | Страница версий рейтинга. |
| POST | `/api/admin/rating-schemes` | Создать новую effective-dated версию. |
| GET | `/api/admin/payroll-schemes?page&size` | Страница версий зарплатной формулы. |
| POST | `/api/admin/payroll-schemes` | Создать новую версию с первого числа месяца. |
| GET | `/api/admin/products/{productId}/payroll-category-assignments` | История назначения товара. |
| POST | `/api/admin/products/{productId}/payroll-category-assignments` | Назначить категорию с `validFrom` и причиной. |
| POST | `/api/admin/payroll-category-assignments/bulk` | Атомарно назначить несколько товаров. |
| POST | `/api/integration-connections/{connectionKey}/product-category-imports` | Начальный импорт аналитической классификации. |

`PayrollCategoryCode`:

```text
TECH_TIER_1 | TECH_TIER_2 | ACCESSORY | SERVICE |
PLAYSTATION_SUBSCRIPTION | PAID_REPAIR | EXCLUDE | UNMAPPED
```

`UNMAPPED` нельзя назначать вручную. Effective-dated версии не редактируются задним числом.

### 9.7. Неизменяемые отчёты

| Method | Path | Назначение |
| --- | --- | --- |
| GET | `/api/stores/{storeId}/reports?year&type&page&size` | Page envelope finalized summary revisions, без payload. |
| GET | `/api/stores/{storeId}/reports/years` | Доступные годы архива. |
| GET | `/api/stores/{storeId}/reports/{reportId}` | Проверенный месячный или годовой payload. |
| POST | `/api/admin/reports/backfill?storeId&year` | Создать durable ADMIN job с `Idempotency-Key`; `202`. |
| GET | `/api/admin/reports/backfill?limit` | Последние report-backfill jobs. |
| GET | `/api/admin/reports/backfill/{jobId}` | Persisted status, progress и safe error. |
| POST | `/api/admin/reports/backfill/{jobId}/cancel` | Идемпотентно запросить остановку. |


### 9.8. Синхронизация — только ADMIN
| Method | Path | Назначение |
| --- | --- | --- |
| POST | `/api/sync/jobs/backfill` | Создать единую durable-задачу синхронизации, ответ `202`. |
| GET | `/api/sync/jobs?limit=20` | Список jobs. |
| GET | `/api/sync/jobs/{jobId}` | Состояние job. |
| POST | `/api/sync/jobs/{jobId}/cancel` | Отмена job. |

Backfill payload:

```json
{"periodStart":"2026-01-01","periodEndInclusive":"2026-01-03"}
```

Это единственная публичная операция запуска синхронизации. Backend последовательно выполняет
`STORES → EMPLOYEES → SALES → RETURNS`, сам ограничивает окна и повторы. Frontend показывает
текущую phase, но не публикует отдельные кнопки этапов, upstream tokens, raw payload или
подробности исключений.

## 10. Правила реализации frontend

- Не хранить пароль, session id или CSRF token в `localStorage`/`sessionStorage`.
- Не добавлять bearer token: приложение использует `JSESSIONID` cookie.
- Выполнять session bootstrap через `/api/auth/me`; на `401` считать пользователя вышедшим.
- После login обязательно повторно получать CSRF.
- Не кэшировать ответы одного магазина под ключом другого.
- Не доверять UI role guard как защите: backend все равно проверяет право.
- Не пересчитывать `rank`, `overallScore`, статусы `planResult`, фонды и `payableAmount`.
- Отличать `null` от `0`: `null` означает «нельзя надежно рассчитать».
- Деньги отображать через `Intl.NumberFormat("ru-RU", {style: "currency", currency: "RUB"})`;
  точное округление уже выполнено backend.
- Проценты рейтинга/KPI отображать с согласованной точностью, не использовать округленное
  отображение в последующих вычислениях.
- Для mutations инвалидировать только связанные query keys и сразу принимать response как новую
  authoritative state.
- Все опасные действия (`approve`, `paid`, отмена удержания, новая ревизия) подтверждать и
  показывать необратимость/следующий статус.
- Скрывать ADMIN-разделы для `MANAGER`, но корректно обрабатывать `403` при изменении прав в уже
  открытой сессии.
- В сообщениях не раскрывать raw server error, токены, пароли, персональные данные покупателей или
  детали LiveSklad.

## 11. Источники истины в репозитории

- `docs/PROJECT_HANDOFF.md` — общий контекст и текущее состояние.
- `docs/FRONTEND_ACCEPTANCE.md` — фактическая browser-приемка, команды, пропуски и остаточные риски.
- `docs/DESIGN-apple.md` — принципы визуальной иерархии и границы применения «жидкого стекла».
- `docs/authentication-api.md` — login, session, CSRF и права.
- `docs/store-directory-api.md` — доступные пользователю магазины.
- `docs/store-data-status-api.md` — фактическая свежесть, sync activity и quality issues.
- `docs/data-quality-api.md` — единый статус качества доступных магазинов.
- `docs/period-quality-api.md` — периодная готовность источников, плана, рейтинга и payroll.
- `docs/store-plan-progress-api.md` — выполнение плана, темп, прогноз и критерии направлений.
- `docs/frontend-contract.md` — правила совместимости и общие transport-типы.
- `docs/frontend-actions.md` — экраны, кнопки, условия действий и cache invalidation.
- `docs/store-kpi-api.md` — KPI магазина.
- `docs/employee-kpi-api.md` — KPI сотрудников.
- `docs/category-kpi-api.md` — категории и группы.
- `docs/attach-rate-api.md` — attach-rate.
- `docs/average-kpi-api.md` — средние показатели и динамика.
- `docs/employee-rating-api.md` — планы, смены, рейтинг и карточка сотрудника.
- `docs/payroll-api.md` — формула и workflow зарплаты.
- `docs/synchronization-api.md` — фоновые jobs.
- `docs/reports.md` — lifecycle, payload, revisions, backfill и целостность отчётов.
- `docs/security-hardening.md` — реализованные security controls.
- `/v3/api-docs` — runtime OpenAPI-контракт; доступен авторизованному `ADMIN`.

Если документация и runtime OpenAPI расходятся, сначала проверить текущие controller/DTO и
зафиксировать расхождение. Не подстраивать frontend молча под случайный ответ.

## 12. Ближайшие согласованные шаги для frontend

1. Зафиксировать release candidate и повторить `npm run check` и production-preview Playwright на
   Node.js `>=22.22.0`; критерии и команды — в `docs/FRONTEND_ACCEPTANCE.md`.
2. На staging пройти необратимые сценарии с контрольными данными: финализацию рейтинга, полный
   payroll lifecycle, sync/import и внешние LLM/Telegram side effects.
3. Добавить backend regression на одновременные login одной учетной записью: при локальной приемке
   один из трех параллельных входов однажды завершился 500 INTERNAL_ERROR.
4. Сверить фактические KPI, рейтинг и payroll с контрольными ручными расчетами заказчика.
5. После закрытия staging и infrastructure gates перейти к production rollout и monitoring.

