# Плоский контракт недельной интерпретации v2

Статус на 2026-08-06: schema, prompt, backend validation, provider projection, dashboard projection и deterministic Telegram renderer v2/v4 добавлены как следующий, ещё не активированный контракт. Production defaults остаются на content v1 и prompt v3 до завершения полной локальной приёмки. Добавление и локальная проверка контракта не выполняют YandexGPT-вызов и не создают Telegram-событие.

Validation service выбирает стратегию по immutable contentSchemaVersion из durable job. v1 и v2 имеют независимые structural/semantic validators; неизвестная версия не может быть проверена другой стратегией по умолчанию.
Provider request factory принимает только согласованные пары v1/v3 и v2/v4. Для v2 он передаёт полные плоские item schemas без shape pruning, ограничивает ссылки значениями manifest, делает nullable-поля необязательными и исключает backend-owned dataLimitations. Validator восстанавливает опущенные nullable-поля как explicit null и инъецирует точные limitations до canonical structural validation. v1 provider projection остаётся неизменной.

Read path выбирает projection по immutable contentSchemaVersion. Исторический v1 продолжает читаться в прежней форме; плоский v2 адаптируется к стабильной presentation-модели без выдуманных fallback-текстов. Необязательные отсутствующие секции передаются как null и скрываются клиентом. Командные employeeRef не используются фронтендом для сопоставления: backend добавляет display names из того же immutable snapshot.

Telegram fan-out получает contentSchemaVersion через interpretation analysis job и выбирает детерминированный v1/v2 renderer. v2-сообщение использует только сохранённый content payload, показывает ревизию, сотрудников, обмен опытом, до четырёх действий и понятные ограничения, не выводя evidence refs и технические competency/category codes.


## Зачем нужен v2

Реальный контрольный прогон показал, что YandexGPT принимает технически сокращённую глубокую схему v1, но неверно восстанавливает скрытые вложенные формы. В частности, категория становилась динамическим объектом с техническими кодами в ключах, а typed insight и action подменялись общим narrative block. Validation retry корректно запускался, но повторял тот же класс структурных ошибок.

v2 убирает неоднозначную вложенность. Все аналитические элементы представлены плоскими массивами и связываются явными полями:

| Коллекция | Назначение |
|---|---|
| employees | точное множество сотрудников и backend-owned analysisStatus |
| summaryBlocks | короткие секции магазина, команды и сотрудников |
| insights | сильные стороны, зоны внимания, риски, категории и допродажи |
| actions | полный список управленческих действий |
| teamRelationships | лидеры, улучшение и обмен опытом |
| dataLimitations | точная копия backend-owned ограничений с понятным summary |

Кабинет группирует элементы по scope, employeeRef, section и categoryCode. Технический categoryCode остаётся metadata и не должен попадать в пользовательский текст.

## Инварианты

- Старые опубликованные документы v1 не мигрируются и продолжают читаться через v1 projection.
- Новая job неизменно фиксирует promptVersion и contentSchemaVersion; worker выбирает контракт по этим значениям.
- manifest.employeeRefs и analysisStatus принадлежат backend. Модель не может добавлять, удалять или переименовывать сотрудников.
- Для каждого сотрудника обязательны ровно HEADLINE и WORKLOAD. Для INSUFFICIENT этим персональная аналитика ограничивается.
- STORE и TEAM используют employeeRef=null; EMPLOYEE требует существующий employeeRef.
- Все evidenceRefs, categoryCode, candidateRef и competencyCode проверяются относительно того же immutable snapshot.
- dataLimitations нормализуются из manifest и не считаются свободным выводом модели.
- Narrative не содержит чисел и технических идентификаторов; проверенные значения продолжает отображать backend.
- Telegram строится из уже опубликованной интерпретации и не обращается к LLM отдельно.

## Этапы активации

1. Contract foundation: schema v2, prompt v4, примеры и registry — выполнено.
2. Version-aware structural и semantic validator с сохранением v1 strategy — выполнено.
3. V2 provider schema projection без удаления обязательной аналитической формы — выполнено.
4. Добавить version-aware dashboard projection и deterministic Telegram renderer — выполнено.
5. Прогнать schema, semantic, publication, API и database integration suite без внешней сети — выполнено.
6. Выполнить один платный staging call с заранее утверждённым бюджетом — следующий этап.
7. Оценить результат по эталонным примерам; только после acceptance переключить defaults на v2/v4.
8. Оставить v1 reader на весь срок хранения исторических интерпретаций.

## Артефакты

- `schemas/weekly-interpretation-content-v2.schema.json`;
- `schemas/examples/weekly-interpretation-content-v2-ready.json`;
- `schemas/examples/weekly-interpretation-content-v2-insufficient-employee.json`;
- `prompts/weekly-interpretation-v4.md`.

Локальный DB/API gate закреплён тестом
`WeeklyV2ReadAndFanoutIntegrationTest`: production `WeeklySnapshotStore` сохраняет snapshot с
integrity hash, v2 interpretation читается через HTTP presentation, а fan-out создаёт ровно одну
durable Telegram delivery. Delivery остаётся `PENDING` с `attempt_count=0`, без
`provider_message_id` и `sent_at`; поэтому тест не обращается к Telegram Bot API. Повторная
обработка события не создаёт дубль. Dashboard и Telegram проверяются на одной immutable
interpretation revision и одном сохранённом content hash.

До этапа переключения feature flags и defaults должны оставаться выключенными либо указывать на v1/v3.
