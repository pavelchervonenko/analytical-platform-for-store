---
doc_schema: 1
doc_type: working
status: draft
owner: backend
audience:
  - developer
  - operations
created_at: 2026-10-04
review_by: 2026-10-11
source_material:
  - docs/decisions/ADR-0004-seller-weekly-historical-membership.md
  - docs/decisions/ADR-0005-weekly-ai-activation-and-retries.md
  - docs/current/ai/weekly-review.md
  - docs/runbooks/weekly-review-ai.md
required_reviewers:
  - backend
  - product
  - operations
exit_target: current
---

# Production automation недельного ИИ-разбора

## Границы и решения

Владелец подтвердил переход к надёжному автоматическому режиму. Это не разрешение включить
постоянные платные вызовы, изменить финансовые формулы или объявить текущий состав продавцов
исторически достоверным. Действует ADR-0004. Изменения выполняются в отдельной ветке от
проверенной базы, без включения незавершённых пользовательских правок.

Владелец согласовал forward-only baseline при включении и необходимые ограниченные повторы
без коммерческого денежного потолка; решение записано в ADR-0005. До production cutover
проверить конкретную baseline-операцию и runtime circuit breakers. Разрешения на старые exact
canary не переносятся на другую неделю или новый снимок.
Автоматическое восстановление до доказанного baseline запрещено; первая ручная публикация
старого периода должна явно сохранять текущую, не temporal, семантику состава.

## Порядок пакетов и контрольные точки

1. **Границы недель и право на выполнение.** Ввести явный закрытый календарный период в timezone
   магазина. Не считать конец рабочего дня закрытием недели. Worker с истёкшим lease не может
   начать платную попытку или оживить lease heartbeat. Проверить границу lease, takeover,
   одновременный start, сохранение receipts и отсутствие повторных расходов.
   До temporal cutover отдельно укрепить действующий current-roster path: bounded retries,
   изоляция ошибок магазинов и бесплатное обновление checkpoint без подмены exact snapshot.
2. **Temporal seller facts.** Подключить единую document-level eligibility к финансовым,
   структурным и attach-проекциям. По ADR-0006 аналитический возврат получает сотрудника записи
   LiveSklad и membership на дату возврата; исходная продажа остаётся отдельным reconciliation fact.
   Сначала проверить независимость от payroll и гарантийных правил, не меняя их неявно.
   UNKNOWN не превращать в false. Исторический автор остаётся в итогах, но не получает future
   action после ухода. Сохранить parity при неизменном составе и существующие формулы.
3. **Периодный read/planner и durable backlog.** Хранить store/week, состояние, попытки подготовки,
   next evaluation и lease в БД. Восстанавливать пропуски после baseline ограниченными пачками,
   включая задержку через две границы недель. Источники проверяются теми же coverage/stability
   predicates. Неполные данные откладывают подготовку, а не публикуют неполный итог.
4. **ИИ и гонки актуальности.** Планировать только точный CURRENT snapshot. Изменение источника
   до provider attempt позволяет повторную бесплатную подготовку; exact approved path может
   подтвердить тот же immutable snapshot, но никогда не подменяет snapshot/хеши. Один automatic job на store/week,
   в том числе после исправлений; ограниченные retries по ADR-0005. После оплаченного ответа
   сохранить receipt, расход и результат проверки даже при устаревании. Не повторять запрос
   автоматически при UNKNOWN outcome и не создавать новый job для обхода retry cap.
5. **Бюджет и наблюдаемость.** Проверяемые технические лимиты с учётом незавершённых и
   UNKNOWN расходов; состояние ожидания источников отдельно от технической ошибки. Диагностика
   показывает store/week, коды причин и timestamps, без секретов/персональных payload. Уведомлять
   об actionable задержке/ошибке, не отправлять повторяющийся шум на каждом scheduler tick.
6. **Release gate.** После каждого пакета code review + тесты его инвариантов + сверка с планом.
   Перед выпуском полные backend/frontend/docs gates, миграционная rehearsal, проверка локального
   UI при изменении интерфейса. Затем отдельные production approval, verification и ограниченный
   paid canary. Только после них разрешается постоянная автоматическая публикация.

## Критерии готовности

- На новой неделе после успешной синхронизации появляется обзор последней полной недели.
- Restart, повторный scheduler и задержка источников не теряют пропущенные недели.
- Неизвестная история, открытая неделя и неполный coverage не выдаются за точные показатели.
- Параллельные worker/planner, истечение lease и source churn не создают дополнительный расход.
- Опубликованный отчёт остаётся immutable; исправления создают revision без платного автоповтора.
- Есть проверяемый runbook включения/остановки и понятный статус для нового разработчика.

## Прогресс

- План зафиксирован; production изменения и платные вызовы не выполнялись.
- Пакет 1: локально проверены календарная идентичность, lease/deadline heartbeat,
  attempt-count fence и сериализация runner. Code review не выявил изменения формул/transport.
  Финальный targeted набор: 38 tests, 0 failures, 0 skipped; включает PostgreSQL integration
  job store и completion. Checkstyle, operator security и 25 documentation tests проходят;
  strict documentation: 0 warnings. Это не полный backend/frontend release gate.
- Пакет 1, продолжение: ограниченные retries внутри одной weekly job, UNKNOWN outcome без
  автоматического повторного расхода, изоляция ошибок магазинов и бесплатное обновление
  checkpoint только для прежнего exact snapshot. Отличающаяся revision не подменяет job.
  Финальный targeted набор: 52 tests, 0 failures, 0 skipped; Checkstyle main/test проходит.
  Code review подтверждает: истёкший STARTED attempt остаётся UNKNOWN/FAILED, повторный
  scheduler не создаёт другую weekly job, exact guard не подменяет revision. Operator security,
  25 documentation tests и strict documentation (0 warnings) проходят. Полный release gate
  этим targeted набором не заменяется.
- Сохранение receipt при потере владельца после ответа, смена snapshot при source churn и durable
  backlog остаются в следующих пакетах; существующая atomic completion не доказывает эти случаи.
- Правило аналитического сотрудника возврата подтверждено владельцем и записано в ADR-0006.
  Независимая historical eligibility projection читает сотрудника записи LiveSklad на дату
  возврата. Не меняет сохранённый финансовый автор, не использует fallback на оригинал;
  собственный UNKNOWN не превращается в отсутствие участия. Подключение к всем агрегатам,
  новая policy/revision и исторический cutover ещё не реализованы; payroll-контракт сохраняется.
  Локальный PostgreSQL integration test проверяет 16 synthetic документов: разных авторов,
  orphan/late link, отсутствие source ID, unresolved ID с совпадающим manual employee,
  границу baseline и выключения участия, удаление документа и оригинала. Общий `employee_id`
  остаётся прежним; Checkstyle main/test и documentation gates проходят. Это проверка
  независимой projection, не всего нового финансового контура.
- Production не менялся; постоянная автоматическая публикация ещё не готова к включению.
- Решения о forward-only baseline и необходимых retries зафиксированы в ADR-0005; конкретный
  timestamp baseline и production-конфигурация ещё не активированы.
