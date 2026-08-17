# Offline evaluation недельной ИИ-интерпретации

Этот каталог содержит воспроизводимый локальный gate для сравнения контрольного prompt v4 и структурного кандидата v15 на одном наборе
обезличенных weekly-сценариев. Проверка dataset и команда `shadow.sh plan` не обращаются к YandexGPT,
не читают production и не создают публикации или Telegram-события. Платные вызовы возможны только
через отдельную явно подтверждённую команду `shadow.sh run` с двумя лимитами бюджета.

## Состав

- `dataset-v2.json` — 26 сценариев и экспертные ожидания;
- `dataset-v2.schema.json` — схема самого evaluation dataset;
- `evaluate.py` — генерация полного scenario input и автоматическая проверка ответов;
- `shadow.sh` — безопасный plan/execute wrapper для точного production request path;
- `LlmEvalShadowRunner` в test sources backend — production compaction, schema specialization,
  preflight, бюджет и сохранение ответов без включения runner в боевой JAR;
- `test_evaluate.py` — положительные и отрицательные тесты gate;
- `manifest.example.json` — оставленный для обратной совместимости legacy-набор v1.

В каждом сценарии отдельно зафиксированы:

- входные факты, sufficiency, materiality, candidates и limitations;
- обязательные выводы;
- допустимые трактовки;
- запрещённые выводы;
- ожидаемые candidate-backed insights или team relationships;
- запреты при LIMITED/INSUFFICIENT;
- тематические метки покрытия.

Обязательные, допустимые и запрещённые выводы не проверяются поиском слов. Их оценивает человек по
рубрике: механическое совпадение текста дало бы ложное ощущение качества. Автоматически проверяются
строгие контрактные свойства.

## Подготовка окружения

Из корня репозитория:

```bash
python3 -m pip install -r scripts/llm-eval/requirements.txt
```

В штатном dev-окружении зависимость `jsonschema` уже может быть установлена.

## Проверка dataset без ответов модели

```bash
python3 scripts/llm-eval/evaluate.py
python3 -m unittest scripts/llm-eval/test_evaluate.py -v
```

Первый вызов валидирует dataset, собирает каждый provider input, проверяет его по input schema и
контролирует связность facts/evidence/candidates/limitations. Дополнительно проверяются production
форматы evidenceRef/metricCode и трёхзначный формат candidateRef, чтобы условные тестовые коды не
подменили настоящий snapshot-контракт. Нулевое число model responses в этом режиме ожидаемо.

Чтобы экспортировать 26 неизменяемых входов:

```bash
python3 scripts/llm-eval/evaluate.py \
  --export-inputs build/llm-eval/inputs
```

Экспорт является полным scenario input. Перед shadow-вызовом Java runner повторно проводит его
через настоящий `LlmProviderInputCompactor`, `LlmProviderRequestFactory` и динамическую
специализацию response schema. Это важно: production может удалить из полного сценария
второстепенные факты. Runner проверяет, что после production compaction v4 и v15 получают побайтово
одинаковый input, и сохраняет его SHA-256 в плане.

Конфигурации сравнения:

- `v4` — `weekly-interpretation-v4` + content schema v2;
- `v15` — `weekly-interpretation-v15` + content schema v3 с единым `primarySignal`.

V5–v12 сохранены как immutable-история prompt-only пилотов. V12 устранил причинные домыслы,
управленческие директивы и шаблонные действия, но повторял один STORE candidate в headline и insight.
V13 впервые вынес backend-selected STORE candidate в единый primarySignal и запретил его повтор во
вторичных insights. Контрольная платная пара показала, что это устранило повтор и ложный WORKLOAD,
но общий массив summaryBlocks всё ещё позволил модели заменить обязательный TEAM_OVERVIEW блоком
другого scope. V14 сохраняет канонический content schema v3, но задаёт обязательные teamOverview и
employeeHeadlines отдельными структурными полями. Контрольная пара v4/v14 подтвердила исправление
структуры, но отклонила v14 по качеству действия и смыслу teamOverview.
V15 сохраняет этот transport, но provider schema разрешает `teamOverview` ссылаться только на
TEAM evidence. Prompt требует отдельный командный вывод и ровно одну операцию с конкретным
результатом в каждом action. Evaluation gate независимо проверяет TEAM scope и близкий повтор
`primarySignal`/TEAM overview.
Контрольная пара v4/v15 подтвердила эти ограничения: v15 прошёл automatic gate без нарушений и
ручную проверку одного сценария, тогда как v4 получил четыре ожидаемых нарушения. Это разрешает
только переход к отдельно согласуемой полной матрице, но не canary или смену default.



Безопасный dry-run всей матрицы:

```bash
LLM_EVAL_RESPONSES_DIR=build/llm-eval/v4-v15-offline-20260817/responses \
LLM_EVAL_ARTIFACTS_DIR=build/llm-eval/v4-v15-offline-20260817/shadow \
  scripts/llm-eval/shadow.sh plan
```

Команда не требует ключа и не делает сетевых запросов. Отдельные каталоги обязательны, чтобы
runner не смешивал receipts разных immutable-матриц. Она формирует
build/llm-eval/v4-v15-offline-20260817/shadow/plan.json с 52 request hashes, размерами, token
estimates и верхней оценкой стоимости. При текущих локальных тарифных коэффициентах максимум всей
матрицы — 717.176800 RUB. Для первой пары accessory-gap максимум составляет 28.158400 RUB:
v4 — 12.368000 RUB, v15 — 15.790400 RUB. Это консервативные пределы, а не обещание фактической
стоимости; оба запроса пары используют одинаковый compacted provider input.

Контрольная пара v4/v15 выполнена 17 августа 2026 года после отдельного согласования: два успешных
вызова без retry стоили 6.021600 RUB. Следующая команда фиксирует выполненный запуск как историю;
повторять её без нового явного согласования нельзя:

```bash
export YANDEX_AI_FOLDER_ID='<folder-id>'
export LLM_EVAL_RESPONSES_DIR='build/llm-eval/v4-v15-control-20260817/responses'
export LLM_EVAL_ARTIFACTS_DIR='build/llm-eval/v4-v15-control-20260817/shadow'
export YANDEX_AI_MODEL_URI='gpt://<folder-id>/<versioned-model>'
export YANDEX_AI_API_KEY_FILE='/secure/path/yandex-api-key'
export CONFIRM_YANDEX_LLM_SHADOW='CALL_YANDEX_SHADOW'
scripts/llm-eval/shadow.sh run 2 30
```

Runner не повторяет неудачный вызов автоматически, не перезаписывает готовый ответ и при следующем
запуске пропускает уже завершённые пары. Отдельный повтор зафиксированной ошибки возможен только при
`LLM_EVAL_RETRY_FAILURES=RETRY`. Успешные ответы и безопасные receipts сохраняются раздельно:

```text
build/llm-eval/responses/<case-id>/<configuration-id>.json
build/llm-eval/shadow/receipts/<case-id>/<configuration-id>.json
build/llm-eval/shadow/failures/<case-id>/<configuration-id>.json
```


Каждый response или failure связан с точным `evaluationHash`, который включает prompt, compact
input, специализированную response schema и параметры генерации, но не model URI. Если metadata
отсутствует или hash изменился, runner останавливается и не считает старый ответ завершённым.
Артефакты платных пилотов v4/v5–v4/v15 сохраняются отдельно как неизменяемая история. Для каждой
следующей матрицы обязательны новые каталоги LLM_EVAL_RESPONSES_DIR и LLM_EVAL_ARTIFACTS_DIR,
чтобы не перезаписать запросы, ответы и receipts предыдущих версий.

API key, folder ID, prompt body, ответы, receipts и failure artifacts нельзя добавлять в git.

Перед проверкой runner повторяет детерминированную нормализацию backend v2/v3: восстанавливает
nullable-поля, нормализует candidate-owned поля primarySignal и insights, очищает employee targets
у широких STORE/TEAM actions и заменяет dataLimitations точным backend-owned набором. Для v14/v15
он также собирает канонические employees и summaryBlocks из teamOverview, employeeHeadlines и
supportingSummaries. Поэтому gate проверяет тот же документ, что и production-validator.

## Полный автоматический gate

Когда готовы все 52 ответа:

```bash
python3 scripts/llm-eval/evaluate.py \
  --responses-dir build/llm-eval/responses \
  --require-responses \
  --report build/llm-eval/report.json
```

Gate проверяет:

- versioned content schema v2 для v4 и content schema v3 для v15;
- точное множество сотрудников и analysisStatus;
- доступность каждого evidenceRef;
- exact соответствие `primarySignal` и каждого insight исходному candidate;
- exact соответствие team relationship backend-кандидату;
- точный нормализованный backend-owned набор limitations;
- обязательные и запрещённые summary/relationship blocks;
- отсутствие персональных выводов при недостаточной базе;
- отсутствие чисел и технических идентификаторов в model narrative;
- запрещённые формулировки, неконкретные действия и близкие дубли рекомендаций;
- соответствие слов о выручке и прибыльности измерениям собственных evidenceRefs;
- точные повторы narrative;
- отсутствие близкого повтора STORE headline/insight в v2, primarySignal/insight и
  primarySignal/TEAM overview в v3;
- только TEAM evidence в обязательном `teamOverview` кандидата v15;
- отсутствие управленческих директив во всех STORE и EMPLOYEE insights;
- отсутствие неподтверждённых возможных причин в summary и non-HYPOTHESIS insights;
- ограничения количества insights и общий лимит actions по числу non-relationship candidates;
- полноту матрицы v4/v15.

Правила конкретности и пороги близости versioned вместе с dataset. Текстовая близость сравнивается
только у действий с одинаковыми target, horizon и evidence. Разные конкретные управленческие
операции не штрафуются по сниженному порогу; пограничное смысловое качество остаётся частью
слепой ручной оценки.

JSON report содержит автоматические средние показатели по каждой конфигурации. Он не является
экспертной оценкой полезности.

Команды ручного review разделяют два независимых gate. Целостность требует все 52 читаемых ответа
и точного совпадения матрицы с dataset. Quality gate требует ноль автоматических нарушений и все
26 прошедших ответов у кандидата v15. Известные смысловые нарушения baseline v4 сохраняются в
метрике `baselineViolationCount` для сравнения, но сами по себе не блокируют слепую оценку v15;
отсутствующий или повреждённый baseline-ответ всегда блокирует её.

## Слепая ручная оценка

Текущую готовность можно проверить без записи файлов и без сетевых вызовов:

```bash
python3 scripts/llm-eval/review.py status
```

Пока ответы не получены, команда штатно показывает `readyForBlindedReview: false`, число
отсутствующих ответов и объект `reviewEligibility`. После полной матрицы и успешного
candidate-aware gate подготовить слепой пакет:

```bash
python3 scripts/llm-eval/review.py prepare
```

Команда создаёт в `build/llm-eval/review/` четыре неизменяемых артефакта:

- `packet.json` — факты, смысловые ожидания и варианты A/B без названий v4/v15;
- `scores.json` — форма, которую заполняет проверяющий;
- `assignments.json` — закрытая до завершения оценки карта A/B → v4/v15;
- `automatic-report.json` — результат обязательного автоматического gate.

В половине сценариев v4 назначается вариантом A, в половине — вариантом B. Распределение
детерминированно, но reviewer не должен открывать `assignments.json` до заполнения `scores.json`.
Пакет, карта, исходные ответы и оценки связаны SHA-256; изменение любого ответа после подготовки
обнаруживается при финализации.

Для каждого варианта в `scores.json` нужно:

1. заменить `reviewerId: "TODO"` на идентификатор проверяющего;
2. выставить целую оценку 1–5 по каждому измерению;
3. отметить каждый обязательный вывод как `COVERED` или `MISSING`;
4. отметить каждый запрещённый вывод как `ABSENT` или `PRESENT`;
5. отметить каждый тип critical error как `ABSENT` или `PRESENT`;
6. при необходимости пояснить решение в `notes`.

Допустимые выводы приведены в пакете как ориентир, но не оцениваются как обязательные. После
завершения формы выполняется раскрытие и расчёт решения:

```bash
python3 scripts/llm-eval/review.py finalize
```

Результаты сохраняются в `decision-report.json` и `decision-report.md`. Конфигурация проходит
ручной gate, только если каждый её ответ имеет среднюю оценку не ниже `passAverage`, не содержит
critical errors и запрещённых выводов, а также покрывает все обязательные выводы. V12, V13 и V14 не
допущены к canary. Контрольная платная пара v4/v13 от 17 августа 2026 года подтвердила устранение
в v13 повтора главного сигнала и ложного WORKLOAD, но выявила пропущенный TEAM_OVERVIEW и
запрещённый STORE HEADLINE. V13 отклонён. Пара v4/v14 подтвердила исправление provider-структуры,
но automatic gate отклонил v14 за неконкретное действие; ручной контроль дополнительно выявил
повтор primary signal в teamOverview. V15 локально закрывает оба остаточных дефекта отдельным
TEAM-only provider enum, самостоятельной scope-проверкой, контролем близкого повтора
primarySignal/teamOverview и более строгим action policy. Контрольная пара v4/v15 прошла:
у кандидата ноль automatic-нарушений, а ручной просмотр подтвердил отдельный TEAM-вывод и
конкретное одноцелевое действие. Полная матрица ещё не запускалась и требует отдельного
согласования до 50 оставшихся платных вызовов.
Кандидат дополнительно не должен уступать v4 по ручной доле прохождения, общей и каждой
dimension-оценке, automatic pass rate и candidate coverage; число повторов, неконкретных действий,
близких дублей рекомендаций, близких narrative-повторов, директивных insights и
неподтверждённых причин не должно увеличиться. Workload blocks и количество действий
выводятся для сравнения, но не считаются механическим критерием качества: меньше действий не
всегда лучше.

`CANDIDATE_ELIGIBLE_FOR_CANARY` означает только готовность к отдельному canary одного периода. Это
не разрешение менять default prompt, публиковать интерпретацию или отправлять её в Telegram.

## Изменение набора

Новый регрессионный случай добавляется только вместе с:

- обезличенным входом без имён, UUID и production identifiers;
- обязательными, допустимыми и запрещёнными выводами;
- тематической меткой;
- candidate/relationship/limitation expectation, если она применима;
- тестом runner, если случай вводит новое автоматическое правило.

Сценарии не должны содержать «идеальный ответ модели»: источник истины — факты и смысловые ожидания,
а не одна заранее выбранная формулировка.
