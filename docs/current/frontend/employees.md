---
doc_schema: 1
doc_type: current
status: current
owner: frontend
audience:
  - developer
  - manager
last_verified: 2026-09-15
requirement_sources:
  - docs/current/product/employees-and-rating.md
  - docs/current/product/periods.md
  - docs/current/product/sales-and-returns.md
implementation_sources:
  - frontend/src/employees
  - frontend/src/api/queries.ts
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeCardService.java
verification_sources:
  - frontend/src/employees/EmployeesPage.test.tsx
  - frontend/src/employees/EmployeeCardPage.test.tsx
  - backend/src/test/java/com/storeanalytics/performance/service/EmployeeCardServiceTest.java
  - backend/src/test/java/com/storeanalytics/performance/service/EmployeeRatingServiceTest.java
runtime_evidence: []
required_reviewers:
  - frontend
  - product
review_triggers:
  - employee-page-change
  - employee-scope-change
  - rating-change
supersedes: []
superseded_by: null
---

# Экран сотрудников

| View | Endpoint | Период | Cohort | Null/partial | Label |
|---|---|---|---|---|---|
| Directory | `/employees` | Selected + previous equal period | Назначения магазина | May have no facts | «Сотрудники» |
| Card | `/employees/{id}` | Selected + explicit comparison mode | One employee | Metrics nullable | Имя + обе даты |
| Full KPI | `/kpi/employees` | Selected | Full financial cohort | GP nullable | «Все факты» |
| Rating | `/employee-ratings` | Selected | Eligible/candidate | Score/rank nullable | Причина без места |

После блока «Результаты сотрудников» находится раскрываемый блок «Участники рейтинга и смен».
По умолчанию он свёрнут; переход по `#rating-participants` из календаря смен раскрывает его и
прокручивает страницу к настройкам. Менеджер может включить активного сотрудника с активным
назначением или исключить его с optimistic `version`. Изменение обновляет рейтинг, календарь смен
и готовность зарплаты. Состояние доступного сотрудника показывает сама кнопка «Включен»/«Выключен»;
поясняющая строка остаётся только для недоступного профиля или назначения. «Без места» —
фактический фильтр; отсутствие места не объявляется «требующим внимания», а таблица показывает
конкретную причину.
Endpoint directory возвращает назначения магазина, а таблица результатов дополнительно оставляет
только записи с `participatesInRanking=true`. Отдельный блок управления участниками показывает и
выключенных сотрудников, чтобы их можно было включить обратно.

Командный блок показывает distribution roster, карточка — конкретного сотрудника; store-level
вывод не дублируется как персональный. Full financial cohort шире roster, поэтому узкий список
обязан иметь соответствующий label.

Список сотрудников сравнивает выбранный диапазон с непосредственно предшествующим периодом той же
длительности. В карточке `WEEK` использует `PREVIOUS_WEEK` и сдвигает обе даты на семь дней;
`MONTH` и `CUSTOM` используют `PREVIOUS_PERIOD`. Например, `01.08–28.08` сравнивается с
`04.07–31.07`. Обе пары дат всегда выводятся в шапке карточки.

Нет предыдущей базы — «нет данных», не нулевой рост. Возврат уменьшает показатели сотрудника
исходной продажи по [ADR-0001](../../decisions/ADR-0001-return-employee-attribution.md); сотрудник,
оформивший возврат, не используется как fallback.

Directory и rating являются основными источниками страницы, а настройки участия — вспомогательным.
Сбой настроек не скрывает список и рейтинг; ошибка фонового обновления оставляет последние данные.
Ошибку изменения участия интерфейс показывает только в строке конкретного сотрудника. Нормальные
статусы «живой расчёт», неактивный профиль, отсутствие места и исключение из рейтинга не оформляются
как предупреждения. Финализированный snapshot имеет один компактный статус в шапке без отдельного
полноширинного success-баннера.
