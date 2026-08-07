# Планы, смены и рейтинг сотрудников

Статус: реализовано, сверено с backend/OpenAPI и frontend 2026-07-27.

## Назначение и границы

Модуль `performance` отвечает за общий план магазина, фактически отработанные смены,
управленческий рейтинг и его финализацию. Рейтинг нужен руководителю и будущему аналитическому
ассистенту, но не является формулой зарплаты. Payroll независимо использует план магазина, смены,
категории и свою effective-dated схему.

План задаётся магазину на календарный месяц. Персональных планов сотрудников нет: сотрудники
участвуют в достижении общего плана и сравниваются в едином контексте магазина. Штрафы, поощрения
и место в рейтинге не влияют на payroll.

Все endpoints store-scoped и требуют доступ текущего пользователя к магазину.

## План магазина

`GET /api/stores/{storeId}/performance-plans/{month}` читает план, где `month` имеет формат
`yyyy-MM`. Отсутствующий план возвращает `404 PERFORMANCE_PLAN_NOT_FOUND`.

`PUT /api/stores/{storeId}/performance-plans/{month}` создаёт или заменяет показатели месяца:

```json
{
  "revenueTarget": 24000000.00,
  "accessoryShareTarget": 3.90,
  "serviceShareTarget": 3.00,
  "additionalShareTarget": 7.00
}
```

Все доли задаются в процентах, план выручки должен быть положительным. GET возвращает view с
`version`, аудитом автора и strong `ETag`.

Conditional contract:

- update существующего плана требует последний ETag в `If-Match`;
- создание после ожидаемого 404 требует `If-None-Match: *`;
- отсутствующая precondition возвращает `428 PRECONDITION_REQUIRED`;
- устаревшая или ложная precondition возвращает `412 PRECONDITION_FAILED`;
- после 412 клиент перечитывает план и не повторяет запись вслепую.

Backend блокирует строку магазина перед проверкой precondition. Это сериализует одновременное
создание одного месяца и не позволяет двум вкладкам успешно сохранить изменения от одной версии.

Для произвольного периода план выручки распределяется пропорционально календарным дням, а целевые
доли усредняются по дням. Если период пересекает несколько месяцев, планы должны существовать для
каждого месяца; иначе `plan.complete=false` и направление структуры не оценивается.

Полный контроль выполнения плана доступен через:

`GET /api/stores/{storeId}/performance-plans/{month}/progress?asOf=YYYY-MM-DD`.

Темп, прогноз, направления и data-quality правила описаны в
`docs/store-plan-progress-api.md`.

## Смены и фактически отработанные часы

`GET /api/stores/{storeId}/work-schedule?periodStart=YYYY-MM-DD&periodEnd=YYYY-MM-DD` возвращает
активные смены периода для календаря.

Перед редактированием frontend читает отдельный aggregate дня:

`GET /api/stores/{storeId}/work-schedule/{workDate}`.

Ответ `WorkScheduleDayView` содержит `storeId`, `workDate`, aggregate `revision`, полный
список `shifts` и strong ETag. Ещё не изменённый пустой день является существующим виртуальным
ресурсом revision 0, а не 404.

`PUT /api/stores/{storeId}/work-schedule/{workDate}` требует ETag дня в `If-Match` и атомарно
заменяет весь состав:

```json
{
  "shifts": [
    {
      "employeeId": "11111111-1111-1111-1111-111111111111",
      "workedHours": 11.00
    },
    {
      "employeeId": "22222222-2222-2222-2222-222222222222",
      "workedHours": 6.50
    }
  ]
}
```

Полный рабочий день `10:00–21:00` равен 11 часам. Допустимое значение `workedHours` —
`0.01..11.00` с точностью до двух знаков. Пустой `shifts` очищает день. Дубли, неактивный
сотрудник и сотрудник без активного назначения отклоняют запрос целиком.

Старый payload `{"employeeIds":[...]}` временно совместим и записывает каждому 11 часов; новый
frontend всегда использует `shifts`.

Версии отдельных `EmployeeShiftView` не используются как precondition полного дня. Aggregate
`work_schedule_day_revisions` хранит одну монотонную ревизию `storeId + workDate`, поэтому
удаление последней смены и конкуренция за пустой день защищены так же, как обычное обновление.
Первый успешный replace переводит revision 0 в 1; каждый следующий — увеличивает её на единицу.

## Участие сотрудников

Новые назначения после синхронизации по умолчанию не участвуют в рейтинге. Руководитель управляет
этим явно:

- `GET /api/stores/{storeId}/employee-rating-settings`;
- `PUT /api/stores/{storeId}/employee-rating-settings/{employeeId}`.

```json
{
  "participatesInRanking": true,
  "version": 2
}
```

Это mutable setting уже использует optimistic version. Устаревшая версия возвращает
`409 EMPLOYEE_RATING_CONFLICT`, после чего клиент перечитывает список настроек. Неактивный
сотрудник или назначение остаются видимыми, но не становятся кандидатом рейтинга.

## Расчёт рейтинга

Live-расчёт:

`GET /api/stores/{storeId}/employee-ratings?periodStart=YYYY-MM-DD&periodEnd=YYYY-MM-DD`.

Первая effective-dated схема `employee-rating-v1` содержит четыре направления по 25%:

| Направление | Смысл |
| --- | --- |
| Коммерческий вклад | Выручка сотрудника относительно средней выручки кандидатов магазина. |
| Эффективность времени | Выручка за час относительно средней по кандидатам. |
| Структура продаж | Выполнение долей аксессуаров и услуг; внутренние веса 50/50. |
| Attach-rate | Среднее отношение attach-rate сотрудника к показателю магазина. |

База направления равна 100, верхний предел — 150, отрицательное отношение даёт 0.
Attach-метрика участвует при знаменателе сотрудника не менее 3 и положительной базе магазина.
Недоступное направление исключается, а `coveragePercent` показывает покрытие. Место присваивается
при покрытии не ниже 75%.

Кандидат рейтинга должен одновременно:

- иметь активного сотрудника и активное назначение в магазин;
- иметь `participatesInRanking=true`;
- иметь хотя бы одну активную смену периода.

Сотрудники вне рейтинга не исчезают из ответа. Они возвращаются с `ranked=false`, nullable
`rank` и исходными метриками для объяснения причины.

## Live и finalized история

`POST /api/stores/{storeId}/employee-ratings/finalize?periodStart=YYYY-MM-DD&periodEnd=YYYY-MM-DD`
фиксирует только завершённый период. Результат сохраняется как immutable snapshot с formula code,
JSON payload, SHA-256, автором и временем.

Повторная финализация того же store/period идемпотентно возвращает существующий snapshot.
Snapshot-read проверяет hash и идентичность header. После финализации новые продажи, возвраты,
смены, планы, настройки участия и версии формулы не меняют исторический ответ.

`history.status`:

- `LIVE` — ответ рассчитан по текущим нормализованным данным;
- `FINALIZED` — ответ прочитан из проверенного immutable snapshot.

Текущий или незавершённый период нельзя финализировать:
`409 RATING_PERIOD_NOT_CLOSED`.

## Справочник и карточка сотрудника

`GET /api/stores/{storeId}/employees?periodStart&periodEnd` возвращает
`EmployeeDirectoryView`: текущий рейтинг, предыдущий равный период и динамику каждого сотрудника.

`GET /api/stores/{storeId}/employees/{employeeId}?periodStart&periodEnd` возвращает
`EmployeeCardView` с:

- текущими и предыдущими показателями;
- изменением места и общего балла;
- четырьмя score breakdown;
- сменами, часами, выручкой за смену и час;
- attach-rate по каждой метрике;
- общим плановым контекстом магазина;
- nullable payroll statement для полного календарного месяца.

Frontend не пересчитывает rank, score, динамику, plan coverage или eligibility.

## Тип дня графика

```ts
interface WorkScheduleDayView {
  storeId: string;
  workDate: string;
  revision: number;
  shifts: EmployeeShiftView[];
}
```

Успешный PUT возвращает новый `WorkScheduleDayView` и новый ETag. Именно этот ответ является
authoritative state для cache.

## Ошибки и повторные запросы

- `404 STORE_NOT_FOUND` — магазин не существует в доступном scope;
- `404 PERFORMANCE_PLAN_NOT_FOUND` — ожидаемый empty state для создания плана;
- `409 EMPLOYEE_RATING_CONFLICT` — stale body version настройки участия;
- `409 RATING_PERIOD_NOT_CLOSED` — период ещё нельзя финализировать;
- `412 PRECONDITION_FAILED` — план или день изменены после GET;
- `428 PRECONDITION_REQUIRED` — conditional mutation отправлена без нужного header.

CSRF остаётся обязательным для всех PUT/POST. ETag не заменяет авторизацию, store access или
валидацию payload. При network timeout клиент не должен изобретать новую версию: сначала нужно
перечитать ресурс и определить, была ли mutation применена.
