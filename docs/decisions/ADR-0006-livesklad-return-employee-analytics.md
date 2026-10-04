---
doc_schema: 1
doc_type: decision
status: accepted
owner: product
audience:
  - developer
  - operations
decision_date: 2026-10-05
implementation_status: partial
decision_sources:
  - user-instruction:conversation-2026-10-05-return-employee-from-livesklad
  - docs/maintenance/payroll-redesign.md
  - docs/maintenance/weekly-ai-production-automation-plan.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/sync/service/ReturnSyncPersistence.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/SellerHistoricalDocumentSelectionRepository.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/SellerHistoricalDocumentSelectionRepositoryIntegrationTest.java
required_reviewers:
  - product
  - integrations
  - backend
supersedes:
  - docs/decisions/ADR-0001-return-employee-attribution.md
superseded_by: null
---

# ADR-0006: Аналитический возврат относится к сотруднику записи LiveSklad

## Источник и область решения

Владелец явно выбрал «Сотрудник возврата из LiveSklad» в ответ на вопрос, кому должны
принадлежать показатели продавцов. Это заменяет целевое аналитическое правило ADR-0001,
но не является утверждением, что изменение уже реализовано или применено к сохранённым фактам.
Подтверждение согласуется с уже принятым D-006 в [зарплатном реестре](../maintenance/payroll-redesign.md):
аналитическая и зарплатная проекции возврата независимы.
Время решения записано по дате среды рабочего чата; исходная неделя ещё закрывается по timezone
каждого магазина, а не по дате чата.

## Решение

1. Аналитический финансовый вклад возврата относится к сотруднику, указанному в записи
   возврата LiveSklad. Продавец исходной продажи не подменяет этого сотрудника и не является
   fallback при отсутствии разрешённого сотрудника возврата.
2. Возврат входит в показатели своей датой, а не переносится в период исходной продажи.
   Выручка, количество и себестоимость сохраняют прежние знаки и значения. Суммы магазина и
   категорий от смены аналитического автора не меняются.
3. Связь с исходной продажей и её позициями остаётся для reconciliation, проверки происхождения
   и правил, которым действительно нужен оригинал. Отсутствие оригинала само по себе не
   оправдывает замену сотрудника возврата продавцом исходной продажи.
4. Для temporal seller selection нельзя продолжать наследовать сотрудника оригинала по
   ADR-0004. Membership аналитического автора проверяется на дату операции возврата; отсутствие
   доказанной истории остаётся UNKNOWN. Исходная продажа проверяется отдельно там, где она
   нужна для конкретного показателя, а не используется для подмены автора.
5. Это решение не меняет зарплатную атрибуцию или формулу и не отменяет специальных правил
   гарантийного attach. Такие контракты проверяются отдельно; аналитическое изменение не
   должно неявно перераспределить выплаты или гарантийную базу.

## Реализация и безопасный переход

В текущем проверяемом коде `ReturnSyncPersistence` всё ещё выбирает сотрудника оригинала.
Локальная экспериментальная historical projection теперь читает сотрудника возврата по
сохранённому `attach_source_employee_external_id`, с точным connection/source scope и собственным
timestamp возврата. Она не меняет общий `employee_id`, payroll или гарантийные allocations.
Отсутствующий/неразрешённый сотрудник остаётся UNKNOWN, без fallback на оригинал. Поздняя
привязка или удаление оригинала не меняют аналитического автора.

Projection ещё не подключена к финансовым/структурным/attach агрегатам. Это известное
расхождение с новым решением, а не подтверждённая работа нового правила в пользовательских KPI.

Перед реализацией проверить, где общий `employee_id` используется зарплатой, гарантией,
рейтингом, weekly и reconciliation. Если общий факт менять небезопасно, аналитическая
атрибуция должна быть независимой проекцией. Сотрудник сохраняется из авторитетного source
поля, а не угадывается по времени, имени или текущему составу.

Исторические документы требуют отдельной точной операции с preflight, provenance и
сверкой до/после. Published snapshots, payroll reports и provider receipts не переписываются.
Ни это решение, ни read-only аудит не разрешают массовую production переатрибуцию.

## Обязательные проверки

- Разные сотрудники продажи и возврата: аналитический минус получает сотрудник возврата.
- Orphan, late link, повторная синхронизация и удаление: автор не меняется из-за появления оригинала.
- Неизвестный сотрудник: явная неопределённость, без fallback на оригинал.
- Store/category money и signed quantities неизменны; гарантии и payroll сохраняют свои контракты.
- Temporal membership проверяет автора на дату возврата; история до baseline остаётся UNKNOWN.
- Старый immutable отчёт не меняется; новая revision явно отражает новый контракт.
