# Frontend: экраны, кнопки и условия действий

Дата проверки по controller, request DTO, security rules и service invariants: 2026-08-06.

Это прикладная карта уже реализованного backend. Она отвечает на вопросы: какой экран можно
собрать сейчас, какую кнопку показать, когда она доступна, что отправлять и что обновлять после
успеха. Формулы и полные response DTO остаются в тематических `*-api.md` и
`FRONTEND_HANDOFF.md`; runtime-контракт доступен администратору через `/v3/api-docs`.

## Общие правила интерфейса

- `MANAGER` работает только с магазинами из `GET /api/stores`; `ADMIN` — со всеми активными.
- Все mutation требуют CSRF. API client всегда использует `credentials: "include"`.
- `passwordChangeRequired=true` оставляет только профиль, смену пароля и выход.
- Backend повторно проверяет роль, магазин, состояние и конкурентные изменения. Скрытая кнопка не
  заменяет обработку `403`/`409`.
- После mutation authoritative state — полный ответ backend. Не пересчитывать рейтинг, план,
  payroll или quality status на клиенте.
- Поле `version` отправляется там, где оно входит в body request: участие в рейтинге, payroll
  run, удержание и отмена удержания. План и полный день графика используют strong ETag headers.
- Payroll calculate/adjust/void/approve/paid требуют `Idempotency-Key`; transport сохраняет его
  после timeout/network/5xx и ротирует только после достоверного результата.
- Необратимые действия требуют confirm: фиксация рейтинга, утверждение/выплата зарплаты, отмена
  удержания, новая payroll-ревизия, сброс чужого пароля и отмена sync job.

## 1. Вход и оболочка

| Экран/кнопка | Когда доступно | API | Результат интерфейса |
| --- | --- | --- | --- |
| `Войти` | Неавторизованный пользователь | `POST /api/auth/login` | Снова получить CSRF, затем `/auth/me` и `/stores`. |
| `Сменить временный пароль` | `passwordChangeRequired=true` | `POST /api/auth/change-password` | `204`; очистить session state и вернуть на вход. |
| `Выйти` | Авторизован | `POST /api/auth/logout` | `204`; очистить все пользовательские cache/state. |
| `Выбрать магазин` | Пароль уже сменен | `GET /api/stores` | Store ID входит во все store-scoped query keys. |
| `Выбрать период` | На аналитических экранах | локальное действие | Перезапросить KPI/рейтинг; календарные границы включительные. |

При `429 LOGIN_THROTTLED` кнопка входа блокируется на `Retry-After`. При защищенном `401` локальная
сессия завершается. `403` после входа может означать обязательную смену пароля или потерю права на
магазин; нужно перечитать `/api/auth/me` и `/api/stores`.

## 2. Обзор магазина и качество данных

Экран полностью read-only для `MANAGER` и `ADMIN`:

- KPI магазина: `GET /api/stores/{storeId}/kpi`;
- категории и группы: `/kpi/categories`;
- средние и динамика: `/kpi/averages`;
- attach-rate: `/kpi/attach-rates`;
- свежесть: `/data-status`;
- проблемы магазина: `/data-quality`;
- общий индикатор всех доступных магазинов: `GET /api/data-quality/summary`;
- готовность выбранного месяца: `/period-quality/{YYYY-MM}?asOf=...`.

Рекомендуемые переходы строятся по `recommendedAction`, а не по тексту сообщения:

| Action | Кнопка/переход | Кому доступна фактическая операция |
| --- | --- | --- |
| `WAIT_FOR_SYNC` | `Обновить статус` | Все с доступом к магазину. |
| `RUN_SYNC` | `К синхронизации` | Только `ADMIN`; менеджеру показать пояснение. |
| `SET_STORE_PLAN` | `Заполнить план` | `MANAGER`, `ADMIN`. |
| `UPDATE_WORK_SCHEDULE` | `Заполнить смены` | `MANAGER`, `ADMIN`. |
| `REVIEW_EMPLOYEE_ELIGIBILITY` | `Проверить участников` | `MANAGER`, `ADMIN`. |
| `CLASSIFY_PRODUCTS` | `К классификации` | Только `ADMIN`. |
| `PROVIDE_COST_DATA` | `Проверить себестоимость` | В текущем API нет ручного ввода себестоимости; переход в диагностику/admin workflow. |
| `CALCULATE_PAYROLL` | `Рассчитать зарплату` | `MANAGER`, `ADMIN`, если readiness разрешает. |
| `RECALCULATE_PAYROLL` | `Пересчитать` | `MANAGER`, `ADMIN`. |
| `FINALIZE_RATING` | `Зафиксировать рейтинг` | `MANAGER`, `ADMIN`, только закрытый период. |
| `REVIEW_DATA_ISSUES` | `Открыть проблемы` | Все с доступом к магазину. |

## 3. Сотрудники и рейтинг

### Список и карточка

| Действие | API | Примечание |
| --- | --- | --- |
| Открыть список | `GET /api/stores/{storeId}/employees?periodStart&periodEnd` | Текущий результат и динамика к предыдущему равному периоду. |
| Открыть карточку | `GET /api/stores/{storeId}/employees/{employeeId}?periodStart&periodEnd` | Для полного месяца может содержать последнюю payroll statement. |
| Открыть подробный рейтинг | `GET /api/stores/{storeId}/employee-ratings?periodStart&periodEnd` | Использует snapshot, если период уже зафиксирован. |
| Показать KPI без рейтинга | `GET /api/stores/{storeId}/kpi/employees?periodStart&periodEnd` | Финансовая детализация, включая не назначенные документы. |

Поиск, фильтры и визуальная сортировка локальны. Нельзя менять `rank`: backend возвращает dense
rank. Сотрудник без допуска остается в списке, но не получает `overallScore` и место.

### `Включить/исключить из рейтинга`

- Сначала загрузить `GET /api/stores/{storeId}/employee-rating-settings`.
- Кнопка/переключатель доступна `MANAGER` и `ADMIN` для доступного магазина.
- Отправить `PUT .../{employeeId}` с `{participatesInRanking, version}`.
- После успеха заменить setting ответом и инвалидировать live-рейтинг, список/карточку и period
  quality. Уже `FINALIZED` snapshot не изменится.
- При `409` перечитать settings и не повторять старую команду автоматически.

### `Зафиксировать рейтинг`

Показывать кнопку, когда `history.status=LIVE` и `periodEnd` раньше текущей даты в timezone
магазина. После подтверждения вызвать:

```text
POST /api/stores/{storeId}/employee-ratings/finalize?periodStart=...&periodEnd=...
```

Успех возвращает полный результат с `history.status=FINALIZED`. Повтор безопасен и возвращает тот
же snapshot. Изменить или удалить снимок через API нельзя. Для текущего/незавершенного периода
backend вернет `409 RATING_PERIOD_NOT_CLOSED`.

## 4. План магазина

Персональных планов сотрудников нет. Одна форма редактирует общий план одного магазина и месяца.

| Кнопка | Условие | API |
| --- | --- | --- |
| `Создать план` | GET вернул `404 PERFORMANCE_PLAN_NOT_FOUND` | `PUT /api/stores/{storeId}/performance-plans/{YYYY-MM}` |
| `Сохранить изменения` | План загружен и форма валидна | Тот же `PUT` |
| `Отменить` | Есть несохраненные локальные изменения | Восстановить последний GET/response. |

Payload: положительный `revenueTarget` и три доли `0..100` с максимум двумя знаками:
`accessoryShareTarget`, `serviceShareTarget`, `additionalShareTarget`. Для создания отправить
`If-None-Match: *`; для обновления — strong ETag последнего GET в `If-Match`. После
`412 PRECONDITION_FAILED` перечитать план и дать пользователю проверить новую версию, не повторять
старый draft вслепую. После сохранения обновить plan, progress, рейтинг, payroll preview/readiness
и period quality.

Progress-экран читает `/progress?asOf=...` и показывает четыре независимых направления. У
`REVENUE` критерий — сумма; у `ACCESSORY`, `SERVICE`, `ADDITIONAL` — доля в фактической выручке.
`status`, `achieved`, прогноз и `focusDirections` уже рассчитаны backend.

## 5. Смены и часы

Календарь читает `GET /api/stores/{storeId}/work-schedule?periodStart=...&periodEnd=...`.

Кнопка `Сохранить день` отправляет полный новый состав дня:

```json
{
  "shifts": [
    {"employeeId": "uuid", "workedHours": 11.00}
  ]
}
```

- `workedHours`: `0.01..11.00`, максимум два знака; shortcut `Полная смена` ставит `11.00`.
- Один сотрудник не может повторяться. Доступны только активные сотрудники с активным назначением.
- `{ "shifts": [] }` полностью очищает день; для кнопки `Очистить день` нужен confirm.
- Перед открытием редактора прочитать GET полного дня; даже пустой день вернёт revision 0 и ETag.
- PUT отправляет этот ETag в `If-Match`, атомарно заменяет день и возвращает новый
  `WorkScheduleDayView` с новым ETag.
- `version` отдельной смены не является версией дня и не используется для replace-day.
- После сохранения обновить график, live-рейтинг/карточки, payroll readiness/preview/run freshness и
  period quality. Уже зафиксированный рейтинг не меняется; рассчитанный payroll станет `STALE`.
- При `412 PRECONDITION_FAILED` закрыть stale editor, перечитать полный день и не перезаписывать
  изменение другой вкладки автоматически.

## 6. Зарплата

### Порядок экрана

1. Загрузить `readiness` и `preview`.
2. Если есть расчет, загрузить latest run; историю — `/payroll-runs`.
3. Показать три независимых результата: выручка, аксессуары, услуги. Они выбирают ставки только
   своих компонентов; frontend не формирует «единый выполнен/не выполнен» сценарий.
4. Показать дневные фонды, равные доли участников смены, часы для аудита, удержания, ведомость и
   события.

### Кнопки и состояния

| Кнопка | Показывать/разрешать | Request и результат |
| --- | --- | --- |
| `Рассчитать` | Нет run и `readiness.canCalculate=true` | `POST .../calculate`, body можно не передавать или `{}`. Создается revision 1. |
| `Пересчитать` | Latest run `CALCULATED` | Тот же POST; обновляет текущую черновую ревизию. `revisionReason` не нужен. |
| `Создать новую ревизию` | Latest run `APPROVED` или `PAID` | Тот же POST с непустым `{revisionReason}`. Активные удержания переносятся. |
| `Добавить удержание` | Только latest `CALCULATED` | `{employeeId,type,amount,reason,runVersion}`; amount положительный. |
| `Отменить удержание` | Активное удержание latest `CALCULATED` | `{reason,runVersion,adjustmentVersion}`; отмена остается в аудите. |
| `Утвердить` | Latest `CALCULATED`, `calculationComplete=true`, `freshness=CURRENT`, readiness допускает approval | `{version}`; результат `APPROVED`. |
| `Отметить выплаченным` | Latest `APPROVED`, `freshness=CURRENT` | `{version}`; результат `PAID`. |
| `Сравнить ревизии` | Выбраны разные revisions одного магазина и месяца | GET compare; read-only. |

Если `freshness.requiresRecalculation=true`, блокировать approve/paid и предлагать `Пересчитать`.
Backend всё равно перепроверяет источники; `409 PAYROLL_SOURCE_DATA_CHANGED` требует перечитать
run. Все пять финансовых POST отправляются с отдельным `Idempotency-Key`. Любой другой `409`
при mutation требует перечитать latest run/version. Обратных переходов
`PAID -> APPROVED` или `APPROVED -> CALCULATED`, удаления run и редактирования старой ревизии нет.

Удержания — только `PENALTY`, `INVENTORY`, `TAX`; это вычеты, а не поощрения. Отдельного API для
ручной премии сейчас нет. Итог может быть отрицательным. Аванс 50 000 ₽ применяется backend к
сотруднику, у которого есть смены.

## 7. Администрирование пользователей (`ADMIN`)

| Кнопка | API | Ограничения |
| --- | --- | --- |
| `Создать пользователя` | `POST /api/admin/users` | Email, временный пароль, имя, роль, storeIds. `201`. |
| `Изменить пользователя` | `PUT /api/admin/users/{id}` | Имя, роль, active. Нельзя менять свою роль/деактивировать себя. |
| `Изменить магазины` | `PUT /api/admin/users/{id}/store-access` | `{storeIds}`; для `ADMIN` явные назначения очищаются. |
| `Сбросить пароль` | `POST /api/admin/users/{id}/reset-password` | Нельзя для себя; новый временный пароль, старые сессии инвалидируются. |

Нельзя деактивировать или понизить последнего активного администратора. Duplicate email и
инварианты администраторов дают `409`. Для manager допустим выбранный набор магазинов; admin всегда
имеет `allStores=true`.

## 8. Формулы и товарная классификация (`ADMIN`)

- `Создать версию рейтинга`: POST `/api/admin/rating-schemes`. Версия immutable; четыре основных
  веса и два веса структуры должны отдельно давать 100. `effectiveFrom` — календарная дата.
- `Создать версию зарплаты`: POST `/api/admin/payroll-schemes`. Версия immutable, начинается с
  первого числа месяца и позже последней существующей версии.
- `Назначить payroll-категорию`: POST
  `/api/admin/products/{productId}/payroll-category-assignments`. Новая дата должна быть позже
  текущего назначения; `UNMAPPED` выбрать нельзя.
- `Назначить категории пакетом`: POST `/api/admin/payroll-category-assignments/bulk`. Общие
  `validFrom` (первое число месяца) и reason; ошибки откатывают весь пакет.
- `Импортировать аналитическую классификацию`: POST
  `/api/integration-connections/{connectionKey}/product-category-imports`. До 10 000 уникальных
  external product IDs, вся операция атомарна и идемпотентна для идентичного импорта.

Редактирования или удаления уже созданных effective-dated версий API не предоставляет.

## 9. Синхронизация (`ADMIN`)

| Кнопка | API | Поведение |
| --- | --- | --- |
| `Синхронизировать данные` | `POST /api/sync/jobs/backfill` | Inclusive LocalDate-период, максимум 730 дней; создаёт durable job и возвращает `202`. |
| `Обновить список` | `GET /api/sync/jobs?limit=20` | limit `1..100`. |
| `Открыть job` | `GET /api/sync/jobs/{jobId}` | Показывать phase, cursor, attempts и безопасную ошибку. |
| `Отменить` | `POST /api/sync/jobs/{jobId}/cancel` | Queued/retry отменяется сразу; RUNNING — после текущей фазы. |

Пользователь запускает одну операцию. Backend выполняет внутренние этапы `STORES → EMPLOYEES →
SALES → RETURNS`; frontend показывает этапы как прогресс, но не предлагает запускать их отдельно.

Один connection не может иметь два non-terminal job. Для статусов `SUCCESS`, `FAILED`,
`CANCELLED` отмена идемпотентна и не меняет результат. Polling нужен для `PENDING`, `RUNNING`,
`WAITING_RETRY`; терминальные jobs больше не опрашиваются.

## 10. Отчёты

Раздел `/reports` — read-only архив finalized snapshots. Фильтры `year` и
`type=MONTHLY|ANNUAL` меняют запрос `GET /api/stores/{storeId}/reports`; выбор карточки открывает
`GET /api/stores/{storeId}/reports/{reportId}`. Показываются все ревизии, текущая помечается
`currentRevision=true`; старые ревизии не скрываются и не редактируются.

Месячный документ появляется только после `PAID`. Годовой — автоматически после закрытия года и
наличия всех ожидаемых месяцев, включая допустимый частичный первый год. UI не пересчитывает
payload, не сравнивает отчёты и не предоставляет approve/delete. ADMIN backfill — отдельная
служебная операция `POST /api/admin/reports/backfill?storeId&year`, а не способ менять snapshot.


## 11. ИИ-разбор, Telegram и профиль

| Экран/действие | API | Поведение |
| --- | --- | --- |
| `ИИ-разбор` | `GET /api/stores/{storeId}/insights/weekly/current` | Read-only проекция последнего недельного разбора; маршрут `/insights` включается preview flag. |
| `Активные сеансы` | `GET /api/auth/sessions` | Текущий сеанс отмечается отдельно; другие можно завершить по reference. |
| `Завершить другой сеанс` | `DELETE /api/auth/sessions/{sessionReference}` | Текущий сеанс завершается только обычным выходом. |
| `Подключить Telegram` | `POST /api/notifications/channels/telegram/link` | Одноразовая deep link; токен не сохраняется frontend. |
| `Подтвердить Telegram` | `POST .../confirm` с `If-Match` | После внешнего действия пользователя перечитать channel resource. |
| `Настроить уведомления` | `PUT .../settings` с `If-Match` | Атомарно заменить timezone и quiet hours. |
| `Отключить Telegram` | `POST .../revoke` с `If-Match` | Отозвать подписку и перечитать состояние. |
| `Операции ИИ`, ADMIN | `GET /api/admin/llm/operations`, regenerate/cancel POST | Показывать безопасные статусы и разрешенные действия, не provider payload. |
| `Доставка Telegram`, ADMIN | `GET /api/admin/notifications/telegram/deliveries`, resend POST | Повтор только для допустимого состояния и с idempotency. |

Production flags LLM/Telegram остаются выключенными до прохождения соответствующих staging
runbooks. Frontend не показывает raw prompts, provider errors, токены, chat IDs или технические
reason codes обычному пользователю.

## 12. Чего backend пока не позволяет сделать
- создать персональный план сотрудника;
- изменить `rank` или вручную выставить балл рейтинга;
- изменить/удалить finalized rating snapshot;
- начислить ручное поощрение или оклад отдельной payroll-строкой;
- удалить payroll run, вернуть его на прошлый статус или редактировать старую ревизию;
- вручную исправить себестоимость позиции через публичный API;
- загрузить файл или сформировать Excel/PDF;
- синхронизировать остатки;

## 13. Cache invalidation после mutation

| Mutation | Минимально перечитать/инвалидировать |
| --- | --- |
| План | plan, progress, rating/directory/cards, payroll readiness/preview/latest freshness, period quality. |
| Смены | schedule, live rating/directory/cards, payroll readiness/preview/latest freshness, period quality. |
| Участие в рейтинге | settings, live rating/directory/card, period quality. |
| Finalize rating | rating, directory/cards, period quality. |
| Payroll calculate/adjust/void/approve/paid | latest run, runs list, preview/readiness as needed, employee card payroll, period quality; после paid также reports. |
| Payroll classification/scheme | readiness/preview, latest freshness, period quality. |
| Sync | job/status/quality during polling; после завершения все KPI, plan progress, live rating, payroll freshness и period quality. |
| Пользователь/store access | users; для измененного текущего пользователя также `/auth/me` и `/stores`. |
| Telegram channel | channel resource; при revoke также связанные pending deliveries на backend. |
| LLM regenerate/cancel | ADMIN operations и текущую weekly insight проекцию после завершения job. |

