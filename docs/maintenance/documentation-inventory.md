---
doc_schema: 1
doc_type: current
status: current
owner: project
audience:
  - developer
last_verified: 2026-08-31
requirement_sources:
  - docs/maintenance/documentation-reform-plan.md
implementation_sources:
  - docs/maintenance/documentation-inventory.tsv
verification_sources:
  - docs/maintenance/documentation-inventory.tsv
runtime_evidence: []
required_reviewers:
  - information-architecture
review_triggers:
  - documentation-structure-change
  - inventory-scope-change
supersedes: []
superseded_by: null
---

# Инвентаризация документации

Статус: этап 1 завершен; реестр готов к использованию в следующих этапах.

Дата снимка: 2026-08-31.

## Область проверки

Реестр охватывает:

- все физические файлы в `docs/`, включая игнорируемые `.orig`;
- все отслеживаемые Markdown, AsciiDoc, reStructuredText и текстовые documentation-like файлы во
  всем репозитории;
- `AGENTS.md` как действующую инструкцию работы с репозиторием;
- co-located README, third-party notice и dependency manifest, если они являются частью
  воспроизводимости runtime или evaluation-инструмента.

Пользовательский каталог `.codex-prod-recovery/` не относится к документации, не просматривался и
не изменялся.

Полный построчный реестр находится в
[`documentation-inventory.tsv`](documentation-inventory.tsv). TSV выбран, чтобы последующие
проверки полноты могли выполняться автоматически без разбора свободного Markdown.

## Текущее состояние после этапа 10

- Реестр содержит 382 строки: 272 tracked-документа и 110 постоянных tombstone.
- В `docs/` находится 258 физических файлов, включая 238 Markdown-документов.
- В корне `docs/` остался только навигационный `README.md`.
- History содержит 39 evidence и один README; archive — 69 superseded-материалов и один README.
- Все 43 versioned prompt/schema/example защищены `runtime-keep` и immutable content diff.
- Strict inventory/metadata/link/anchor/orphan/backup/tombstone gate проходит с 0 предупреждений;
  adversarial unit suite содержит 25 тестов.

## Исторический снимок этапов 1–3

- исходный каталог `docs/` содержал 154 физических файла;
- 149 из них являлись отслеживаемыми документационными или runtime-артефактами;
- 5 файлов `.orig` игнорируются Git и требуют отдельного content diff;
- 111 файлов расположены непосредственно в корне `docs/`;
- `docs/prompts/` содержит 25 версионированных prompt;
- `docs/schemas/` содержит 18 JSON Schema и примеров;
- этап 1 добавил три файла в `docs/maintenance/`, поэтому реестр того этапа охватывал 157 файлов в
  `docs/` и 13 documentation-like файлов вне `docs/`, всего 170 строк без заголовка.
- этап 2 добавил policy, ownership и семь template-файлов; после их фиксации реестр охватывал
  166 файлов в `docs/` и 13 файлов вне `docs/`, всего 179 строк без заголовка.
- CI baseline и этап 3 добавили warning allowlist, канонический project-state и immutable release
  evidence. Реестр этапа 3 охватывал 169 файлов в `docs/` и 13 файлов вне `docs/`, всего 182
  строки без заголовка: 177 tracked и 5 ignored.

## Поля реестра

- `path` — путь от корня репозитория;
- `tracking` — `tracked`, `ignored` или постоянный tombstone `removed`;
- `kind` — фактический тип материала;
- `owner` — область, отвечающая за содержание;
- `migration_status` — состояние материала на момент реформы; это не нормативный front matter
  `status`;
- `action` — предварительное решение следующего этапа;
- `target` — предполагаемый канонический раздел или замена;
- `verification` — обязательная проверка перед изменением судьбы файла.

Решения в реестре предварительные. Значения `history`, `archive`, `split`, `consolidate` и
`delete-candidate` не разрешают автоматически переносить или удалять файл.

## Легенда решений

- `keep` — действующий документ остается канонической основой, но может получить новый путь и
  метаданные;
- `rewrite` — назначение сохраняется, содержание нужно сверить и переписать;
- `consolidate` — полезное содержание переносится в один канонический документ;
- `split` — документ смешивает несколько типов знания и должен быть разделен;
- `history` — immutable evidence уже произошедшего события;
- `archive` — полезный ненормативный контекст или superseded design;
- `runtime-keep` — версионированный машинный контракт, не обычная документация;
- `delete-candidate` — удаление допустимо только после diff и подтверждения переноса полезного
  содержимого.

## Подтвержденные риски, которые влияют на порядок работ

1. Корневой `README.md`, `docs/README.md`, `PROJECT_HANDOFF.md`, rollout/status и operations
   документы одновременно публикуют разные production release и schema.
2. Июльское восстановление возвратов в одних материалах помечено ожидающим выполнения, а в более
   позднем evidence — завершенным. Ошибочная операторская трактовка может привести к повторной
   recovery-операции.
3. Старый LLM-контур и weekly-review v2/v25 выглядят как один действующий контур, хотя имеют разные
   endpoints, flags и contracts.
4. Денежная, зарплатная и attach-rate классификации не описаны единым продуктовым контрактом.
5. Deployment, migration recovery, backup/restore и observability распределены между несколькими
   документами; часть заявленных процедур и сигналов не подтверждена практической проверкой.
6. `docs/prompts/` и `docs/schemas/` копируются в backend image. Массовая файловая реорганизация без
   изменения build paths сломает packaged contracts.
7. Пять `.orig` отличаются от текущих файлов. Git уже хранит историю, но перед удалением необходимо
   доказать, что уникальные фрагменты не потеряются.

## Критерий завершения этапа 1

Этап завершен, когда:

- каждый файл из области проверки представлен ровно одной строкой TSV;
- нет неизвестных владельцев и пустых решений;
- каждый `delete-candidate` имеет обязательный diff-gate;
- runtime prompts/schemas явно защищены от обычной чистки;
- два независимых reviewer-а не находят пропущенных файлов или опасной ошибочной классификации;
- существующие документы не перемещены и не удалены.

## Результат проверки

Результат этапа 1:

- Полнота: `PASS` — 170 уникальных записей, пропусков, лишних путей и дубликатов нет.
- Структура TSV: `PASS` — восемь заполненных полей в каждой строке.
- Tracking: `PASS` — tracked/ignored соответствует состоянию Git после staging этапа.
- Risk review: `PASS` — замечаний уровня P0/P1 после исправления карты решений нет.
- Runtime safety: `PASS` — все 43 prompt/schema/example имеют `runtime-keep`.
- Delete safety: `PASS` — все пять `delete-candidate` являются `.orig` и требуют
  `backup-fragment-map`, content diff и reviewer sign-off.

Реестр не утверждает, что исходное содержание уже актуально. Он задает проверяемый порядок, в
котором это содержание будет сверяться, консолидироваться и переноситься.

Результат этапа 2:

- Полнота: `PASS` — 179 уникальных записей: 166 файлов в `docs/` и 13 вне `docs/`.
- Tracking: `PASS` — 174 tracked и 5 намеренно ignored файлов совпадают с Git.
- Metadata: `PASS` — front matter действующих maintenance-документов и YAML всех семи шаблонов
  корректно разбираются и соответствуют lifecycle policy.
- Links/format: `PASS` — локальные ссылки maintenance-пакета существуют, `git diff --check`
  замечаний не выявил.
- Product/template review: `PASS` — requirement, implementation, verification и runtime evidence
  разделены; ADR и ограниченный canary verdict не выдают решение или частичную проверку за rollout.
- Operations/information-architecture review: `PASS` — working lifecycle отделен от draft status,
  production-state отложен до runtime-этапа 3, а составные runbook gates и risk matrix проверены.

Результат CI baseline и этапа 3:

- Полнота: `PASS` — 182 записи, включая единственный project-state и immutable `pilot.27` evidence.
- Protection: `PASS` — base-aware tombstone, runtime-artifact и production-runbook gates прошли
  adversarial review; fake evidence и удаление строки реестра блокируются.
- Clean checkout: `PASS` — 14 unit tests, metadata/link/inventory check и `git diff --check`.
- Runtime boundary: `PASS` — release/schema/digests/flags имеют дату наблюдения и не копируются в
  README, архитектуру или runbook.
- Recovery safety: `PASS` — старые handoff/status inline помечают июльскую recovery завершённой и
  запрещают повторный запуск.
