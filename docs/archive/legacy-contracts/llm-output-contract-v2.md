---
doc_schema: 1
doc_type: archive
status: archived
owner: ai
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "docs/current/ai/README.md"
original_content_sha256: 28fc49a6f7e97d6620761540111078c83211c3ca567c5f095a9a4a4b33e07097
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/ai/README.md`.

# Плоский контракт недельной интерпретации v2

Статус на 2026-08-17: content schema v2 и prompt v4 являются текущей парой application defaults.
V5–v18 сохранены как immutable-версии выполненных и отклонённых shadow-пилотов. V15 отклонён
полной матрицей. Content schema v3 структурно обеспечивает единый `primarySignal`, а кандидат v19
использует store-only provider input и backend-owned employee/team presentation. V19 прошёл
26/26 automatic и 26/26 blinded manual gate и допущен только к отдельному canary.
Ни один shadow-результат не публиковался, production не изменялся и
Telegram-события не создавались.

Validation service выбирает стратегию по immutable contentSchemaVersion из durable job. v1, v2 и
v3 имеют независимые structural/semantic validators; неизвестная версия не может быть проверена
другой стратегией по умолчанию. Provider request factory принимает только согласованные пары
prompt/content schema, включая v2 с prompt v4–v12 и v3 с prompt v13–v19. Для v2
он передаёт полные плоские item schemas без shape pruning и ограничивает ссылки значениями exact
compact provider input. Если во входе нет relationship-кандидатов, provider schema требует
`teamRelationships.maxItems = 0`; иначе допустимые типы и верхняя граница выводятся из кандидатов.
Для v7–v19 каждый insight обязан использовать non-relationship candidateRef, а maxItems, kind и
theme ограничиваются фактическими backend candidates. Свободные insights с null candidateRef
запрещены. Для v9–v19 action count ограничен количеством non-relationship candidates.
Nullable-поля остальных коллекций остаются необязательными, backend-owned dataLimitations
исключаются.

Validator восстанавливает опущенные nullable-поля как explicit null и инъецирует точные limitations
до canonical structural validation. Для известного candidateRef backend канонизирует kind, theme,
scope, employeeRef, categoryCode и evidenceRefs из immutable CandidateSignal; повтор candidateRef
запрещён. Неподтверждённые team relationships и WORKLOAD без прямых workload-данных удаляются как
небезопасные необязательные элементы. v1 provider projection остаётся неизменной.

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
- Для каждого сотрудника обязателен HEADLINE. WORKLOAD необязателен и появляется только при
  содержательном влиянии нагрузки; для INSUFFICIENT и он не формируется.
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
5. Прогнать schema, semantic, publication, API и database integration suite без внешней сети —
   выполнено.
6. Добавить immutable prompt v5 и evaluation infrastructure — выполнено без активации.
7. Выполнить ограниченную shadow-пару v4/v5 — выполнено, обе версии отклонены gate.
8. Оформить immutable prompt v6 и усилить relationship/WORKLOAD guards — выполнено.
9. Выполнить контрольную shadow-пару v4/v6 — выполнено; v6 отклонён из-за candidate mismatch и
   технического employeeRef в narrative.
10. Оформить immutable prompt v7, candidate-only provider insights и backend-owned candidate
    normalization — выполнено без активации.
11. Выполнить контрольную shadow-пару v4/v7 — выполнено; v7 прошёл прежний structural gate, но
    ручной просмотр выявил две общие и близкие рекомендации.
12. Добавить versioned action-quality policy и immutable prompt v8 — выполнено локально без
    активации.
13. Проверить v8 offline на 26 сценариях и полном backend suite — выполнено.
14. Выполнить одну свежую контрольную пару v4/v8 — выполнено; v8 отклонён из-за неконкретного
    второго действия, а ручной просмотр дополнительно выявил неподтверждённый вывод о прибыльности.
15. Сохранить v8 как immutable-историю и локально усилить action cardinality и narrative dimension
    gate в новом versioned-кандидате v9 — выполнено без активации.
16. Проверить v9 targeted-тестами, независимым evaluation gate и offline preflight — выполнено;
    сохранённый ответ v8 теперь детерминированно отклоняется за неподтверждённую прибыльность и
    лишнее действие.
17. Выполнить одну контрольную пару v4/v9 — выполнено; v9 отклонён за неподтверждённую
    прибыльность, точный narrative-повтор и шаблонное действие, обнаруженное ручным просмотром.
18. Сохранить v9 как immutable-историю, усилить action-quality policy и оформить prompt v10 —
    выполнено локально без активации.
19. Проверить v10 targeted-тестами, повторной оценкой сохранённого v9 и offline preflight —
    выполнено.
20. Выполнить контрольную пару v4/v10 — выполнено; v10 отклонён после ручного просмотра за близкий
    narrative-повтор и управленческую директиву внутри insight.
21. Сохранить v10 как immutable-историю, добавить deterministic narrative-quality gate и оформить
    prompt v11 — выполнено локально без активации.
22. Проверить v11 targeted-тестами, повторной оценкой сохранённого v10 и offline preflight —
    выполнено.
23. Выполнить контрольную пару v4/v11 — выполнено; v11 прошёл прежний automatic gate, но отклонён
    ручным контролем за вложенный заголовок и неподтверждённые причинные гипотезы.
24. Сохранить v11 как immutable-историю, добавить containment/causal gate и оформить prompt v12 —
    выполнено локально без активации.
25. Проверить v12 targeted-тестами, повторной оценкой сохранённого v11 и offline preflight —
    выполнено.
26. Выполнить контрольную пару v4/v12 — выполнено; причинные домыслы исчезли, но v12 отклонён за
    обязательный повтор одного STORE signal в headline и insight.
27. Остановить prompt-only итерации и подтвердить структурную причину в provider schema, validator,
    projector и UI — выполнено.
28. Спроектировать один пользовательский источник главного сигнала: highest-priority STORE
    candidate поднимается в hero и не показывается второй раз как insight-card — выполнено.
29. Реализовать structural fix локально с отдельной версией контракта и regression suite —
    выполнено в content schema v3 и `WeeklyInterpretationV3ResponseValidator`.
30. После успешного local gate назначить новый prompt-кандидат — выполнено как immutable
    `weekly-interpretation-v13`.
31. Выполнить контрольную пару v4/v13 — выполнено; v13 отклонён за неправильный состав обязательных
    summary blocks.
32. Добавить structured provider transport без изменения canonical content v3 — выполнено в v14.
33. Выполнить контрольную пару v4/v14 — выполнено; v14 отклонён за неконкретное действие и
    STORE-сигнал вместо отдельного командного вывода.
34. Сохранить v14 как immutable-историю и оформить v15 с TEAM-only provider evidence,
    primary/team duplicate gate и усиленным action policy — выполнено локально без активации.
35. Выполнить одну контрольную пару v4/v15 — выполнено; кандидат прошёл automatic и ручной gate.
36. Выполнить полную v4/v15 матрицу — выполнено; v15 отклонён: 18/26 и 43 violations.
37. Оформить v16–v18 как immutable исправления воспроизводимых дефектов матрицы — выполнено.
38. Убрать person-level provider input/output и перенести employee/team presentation в backend —
    выполнено в privacy-reduced v19.
39. Выполнить полную v4/v19 матрицу и blinded review — выполнено; v19 прошёл 26/26 + 26/26.
40. Рассматривать только отдельный canary; default до его приёмки остаётся v4.
41. Оставить v1 reader на весь срок хранения исторических интерпретаций.

## Артефакты

- `schemas/weekly-interpretation-content-v2.schema.json`;
- `schemas/examples/weekly-interpretation-content-v2-ready.json`;
- `schemas/examples/weekly-interpretation-content-v2-insufficient-employee.json`;
- `prompts/weekly-interpretation-v4.md`;
- `prompts/weekly-interpretation-v5.md` — immutable-версия первого пилота;
- `prompts/weekly-interpretation-v6.md` — immutable-версия отклонённой контрольной пары;
- `prompts/weekly-interpretation-v7.md` — immutable-версия выполненной контрольной пары;
- `prompts/weekly-interpretation-v8.md` — immutable-версия отклонённой контрольной пары;
- `prompts/weekly-interpretation-v9.md` — immutable-версия отклонённой контрольной пары;
- `prompts/weekly-interpretation-v10.md` — immutable-версия отклонённой контрольной пары;
- `prompts/weekly-interpretation-v11.md` — immutable-версия отклонённой контрольной пары;
- `prompts/weekly-interpretation-v12.md` — immutable-версия отклонённой контрольной пары; causal
  guard прошёл, но обязательные headline/insight повторили единственный STORE signal;
- `schemas/weekly-interpretation-content-v3.schema.json` — канонический structural-контракт с
  единственным candidate-backed `primarySignal` и без STORE `HEADLINE`;
- `schemas/examples/weekly-interpretation-content-v3-ready.json`;
- `schemas/examples/weekly-interpretation-content-v3-insufficient-employee.json`;
- `prompts/weekly-interpretation-v13.md` — immutable отклонённый structural candidate;
- `prompts/weekly-interpretation-v14.md` — immutable отклонённый structured-summary candidate;
- `prompts/weekly-interpretation-v15.md` — immutable кандидат, отклонённый полной матрицей;
- `prompts/weekly-interpretation-v16.md` — matrix-hardening история;
- `prompts/weekly-interpretation-v17.md` — production-hardening история;
- `prompts/weekly-interpretation-v18.md` — deterministic narrative история;
- `prompts/weekly-interpretation-v19.md` — privacy-reduced кандидат, допущенный к canary;
- `../scripts/llm-eval/dataset-v2.json` — versioned сценарии, quality policy и human expectations;
- `../scripts/llm-eval/dataset-v2.schema.json` — схема dataset;
- `../scripts/llm-eval/evaluate.py` — локальный автоматический gate и нормализация provider transport.

Локальный DB/API gate закреплён тестом
`WeeklyV2ReadAndFanoutIntegrationTest`: production `WeeklySnapshotStore` сохраняет snapshot с
integrity hash, v2 interpretation читается через HTTP presentation, а fan-out создаёт ровно одну
durable Telegram delivery. Delivery остаётся `PENDING` с `attempt_count=0`, без
`provider_message_id` и `sent_at`; поэтому тест не обращается к Telegram Bot API. Повторная
обработка события не создаёт дубль. Dashboard и Telegram проверяются на одной immutable
interpretation revision и одном сохранённом content hash.

Content v3 сохраняет стабильную API/UI/Telegram presentation: `primarySignal` проецируется в hero
ровно один раз, а при отсутствии STORE candidate backend формирует нейтральный headline. Старые
v1/v2 документы продолжают читаться прежними versioned projectors.

Контрольная платная пара v4/v13 для accessory-gap выполнена 17 августа 2026 года: два успешных
вызова без retry стоили 5.672800 RUB. V13 устранил повтор primary/insight и ложный WORKLOAD, но
общий provider-массив summaryBlocks позволил создать запрещённый STORE HEADLINE вместо обязательного
TEAM OVERVIEW. Automatic gate отклонил v13; полная матрица и canary не запускались.

V14 сохраняет canonical content schema v3, но использует структурный provider transport:
backend-owned employees, отдельный teamOverview, точный объект employeeHeadlines и необязательные
supportingSummaries. Полный backend suite — 830 тестов без failures/errors/skipped; 39 Python-тестов,
Checkstyle main/test и offline plan также прошли. Контрольная платная пара v4/v14 для accessory-gap
выполнена 17 августа 2026 года: два успешных вызова без retry стоили 5.855200 RUB. Structural
transport устранил неверный состав summary blocks, однако automatic gate отклонил v14 за
неконкретное действие. Ручной просмотр дополнительно обнаружил повтор primary signal в
teamOverview вместо отдельного командного вывода. Полная матрица и canary не запускались.

V15 наследует canonical content schema v3 и structured transport v14. Для `teamOverview`
provider schema теперь формирует exact enum только из TEAM facts; evaluator отдельно запрещает
не-TEAM evidence и близкий повтор `primarySignal` независимо от evidence. Action policy
запрещает добавленные общие цели вроде анализа спроса, поиска причин и «принять меры» и требует
одну наблюдаемую операцию с конкретным результатом.

Сохранённый ответ v14 повторно оценён как v15 и детерминированно получает все три ожидаемых
нарушения: не-TEAM evidence в командном выводе, повтор primary/team и неконкретное действие.
Локально прошли 831 backend-тест, 42 Python-теста, Checkstyle main/test и offline plan из 52
запросов. Максимум матрицы v4/v15 — 717.176800 RUB, первой пары — 28.158400 RUB.

Контрольная пара accessory-gap выполнена двумя успешными вызовами без retry. V4 стоил
2.484800 RUB, v15 — 3.536800 RUB; вся пара — 6.021600 RUB. У v4 automatic gate обнаружил
четыре прежних нарушения. V15 прошёл без automatic-нарушений: один `primarySignal`, отдельный
TEAM-only `teamOverview`, ноль вторичных insights и WORKLOAD, конкретное действие с одним
результатом. Ручной просмотр подтвердил отсутствие повторов, причинных домыслов и технических
идентификаторов, а также соответствие обязательному category signal.

Полная v4/v15 матрица выполнена после согласования бюджета. V15 вернул все 26 ответов, но прошёл
только 18 сценариев и получил 43 automatic violations, поэтому отклонён.

V19 удаляет из provider input employee facts/refs/candidates и возвращает person-level presentation
backend. Полная v4/v19 матрица: 26/26 automatic pass, 0 violations. Blinded review: 26/26 manual
pass, средняя оценка 4,8/5, 0 missing/forbidden findings и 0 critical errors. Решение —
`CANDIDATE_ELIGIBLE_FOR_CANARY`.

До отдельного controlled canary и решения о rollout default prompt должен оставаться на v4.
