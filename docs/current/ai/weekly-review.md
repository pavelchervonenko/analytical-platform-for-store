---
doc_schema: 1
doc_type: current
status: current
owner: ai
audience:
  - developer
  - operator
  - manager
last_verified: 2026-10-05
requirement_sources:
  - docs/archive/legacy-contracts/AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md
  - docs/archive/legacy-contracts/weekly-review-ai-management-rubric.md
implementation_sources:
  - frontend/src/insights/InsightsPreviewPage.tsx
  - frontend/src/insights/WeeklyReviewView.tsx
  - frontend/src/insights/weekly-review-presentation.ts
  - frontend/src/insights/weekly-review/WeeklyReviewContent.tsx
  - frontend/src/insights/weekly-review/ReviewDetailPanel.tsx
  - frontend/src/insights/weekly-review/weeklyReviewViewModel.ts
  - frontend/src/insights/weekly-review/weekly-review.css
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewAssembler.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyReviewService.java
  - backend/src/main/java/com/storeanalytics/interpretation/web/SellerWeeklyReviewController.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyReviewSnapshotPlanner.java
  - frontend/src/api/sellerWeeklyReviewContract.ts
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/SellerWeeklyReviewAiInputCompactor.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/SellerWeeklyReviewAiEnricher.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyV3Assembler.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyV3TeamPresenter.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyReviewFactsSource.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklySourceCoverageRepository.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklySourceStabilityRepository.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklySourceRevisionRepository.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklySourceIdentity.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyHistoricalIdentity.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyHistoricalIdentityFactsSource.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyHistoricalReadService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyAiSourceFence.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyHistoricalPlanningService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyHistoricalReviewService.java
  - backend/src/main/java/com/storeanalytics/interpretation/web/SellerWeeklyHistoricalReviewController.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyHistoricalMembership.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationRunner.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyIdentityFacts.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyIdentityFactsSource.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyTemporalFence.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyV3CandidateService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyV3ReadService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyV3ReadResult.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyV3PlanningService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyV3PlanningResult.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyV3BatchPlanningService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyV3BatchPlanningResult.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/AttachAttributionQualityRepository.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/AttachAttributionQuality.java
  - backend/src/main/java/com/storeanalytics/metrics/service/SellerHistoricalFactsService.java
  - backend/src/main/java/com/storeanalytics/metrics/service/SellerHistoricalComparisonFacts.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/SellerAttachRateRepository.java
  - backend/src/main/resources/db/migration/V95__add_temporal_seller_attach_provenance.sql
  - backend/src/main/resources/db/migration/V78__fence_seller_analytics_sources.sql
  - backend/src/main/resources/db/migration/V79__add_weekly_review_generation_state.sql
  - backend/src/main/resources/db/migration/V80__bound_warranty_fingerprint_context_to_document.sql
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewCoreProjector.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewQualityPolicyV1.java
  - backend/src/main/java/com/storeanalytics/quality/repository/PeriodQualityIssueRepository.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewSummaryPresenter.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewAttributionRepository.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewSnapshotStore.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewTeamEmployeeProjector.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiContract.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiOperatorService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiPreflightView.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiInputCompactor.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiSemanticValidator.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiRendererV25.java
  - backend/src/main/resources/db/migration/V46__add_weekly_review_ai_enrichments.sql
  - backend/src/main/resources/db/migration/V47__add_weekly_review_ai_generation_jobs.sql
  - backend/src/main/resources/db/migration/V48__harden_weekly_review_rollout.sql
  - backend/src/main/resources/db/migration/V94__preserve_weekly_ai_response_receipts.sql
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiJobStore.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiCompletionService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyHistoricalFactsSource.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationStore.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationProperties.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationBatchService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationScheduler.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationConfiguration.java
  - backend/src/main/resources/db/migration/V96__add_seller_weekly_preparation_backlog.sql
verification_sources:
  - frontend/src/test/fixtures/weekly-review-v2-return-processor-ready.json
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationConfigurationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationBatchServiceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyHistoricalReadServiceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyHistoricalIdentityFactsSourceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyHistoricalPreparationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyHistoricalFactsSourceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationStoreIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiCompletionServiceIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiBudgetReservationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/WarrantyFingerprintContextMigrationIntegrationTest.java
  - frontend/src/insights/WeeklyReviewView.test.tsx
  - frontend/src/api/sellerWeeklyReviewContract.test.ts
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/SellerWeeklyReviewAiTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/StoreKpiIntegrationTest.java
  - frontend/src/insights/weekly-review/weeklyReviewViewModel.test.ts
  - frontend/src/insights/weekly-review-presentation.test.ts
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewAssemblerTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyV3AssemblerTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyV3TeamPresenterTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyQualityPolicyTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklySourceStabilityRepositoryIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklySourceIdentityTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyIdentityFactsSourceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyTemporalFenceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyV3CandidateServiceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyV3ReadServiceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyV3PlanningServiceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyV3BatchPlanningServiceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/SellerWeeklyV3LoadIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/AttachAttributionQualityTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewStructureProjectorTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/AttachRateAggregateTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/SellerHistoricalAttachIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/SellerHistoricalFinancialFactsServiceIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/TemporalSellerAttachMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/warranty/WarrantyAttributionIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewServiceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewSnapshotStoreIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewTeamEmployeeProjectorTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewResponseContractTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiSchemaContractTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiSemanticValidatorTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiRendererV25Test.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiCompletionServiceIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiOperatorServiceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiJobStoreIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/web/WeeklyReviewAiOperationsSecurityIntegrationTest.java
runtime_evidence:
  - docs/history/audits/2026/09/WEEKLY_REVIEW_LOCAL_PRERELEASE_2026-09-14.md
required_reviewers:
  - ai-semantic
  - backend-data
  - security-privacy
review_triggers:
  - ai-contract-change
  - weekly-review-schema-change
  - weekly-review-publication-change
  - provider-payload-change
supersedes: []
superseded_by: null
---

# Weekly Review: legacy STORE и seller-контур

## Назначение и границы

Weekly Review — основной контракт страницы «ИИ-разбор». Он отделяет расчёт фактов от AI:
детерминированная часть строит полный отчёт и evidence, а optional AI-layer выбирает только
редакционные selector-ы. AI не пересчитывает KPI и не создаёт новые действия или числа.

Документ не утверждает, что planner или worker включены в конкретном окружении. Это проверяется по
[`project-state.md`](../project-state.md).

## Контур

### Seller API и совместимый cutover этапа A

Candidate-код добавляет GET `/api/stores/{storeId}/weekly-reviews/seller-current` и ADMIN POST
`/api/admin/seller-weekly-reviews/stores/{storeId}/generate`. Нужны parent weekly-review и отдельный
`app.interpretation.seller-weekly-review.enabled`; включение seller feature без parent отвергается
при startup. Это не утверждение о включении флагов в окружении и не разрешение на rollout.

GET только читает. Envelope различает `PREPARING` с `report=null`, `CURRENT` и `STALE` с прежним
совместимым v3 report. Freshness не заменяет quality state. POST использует stable-source/coverage/
identity fences, штатную session/CSRF и ADMIN authorization; возвращает идемпотентный 200 envelope,
а не обещает новую revision. Оба endpoints имеют `Cache-Control: private, no-store`.
При разрешённом расписании в worker/combined role seller planner заменяет старый v2 planner,
сохраняя bounded batch/cursor. Automatic publication включается только после отдельных gates.

Frontend cache key отделяет v3/SELLERS от v2. Только 404 отключённого seller endpoint разрешает
legacy fallback; auth, server, parsing errors и seller `PREPARING` не разрешают подмену STORE.
Legacy явно подписан как результат всего магазина. При `STALE` будущие действия скрыты,
исторические показатели/evidence сохранены. Scope и время расчёта показаны один раз в заголовке.
В результатах добавлены выручка допов и доля от чистой выручки продавцов. Раскрытие показывает
аксессуары/услуги и доли от выручки допов: это другой знаменатель. Отрицательные/нулевые компоненты
не изображаются диаграммой частей целого. Пустой рейтинг имеет явное объяснение; без подтверждённых
смен команда показывает деньги и может предложить личную финансовую проверку при достаточной
выборке завершённых продаж в обеих неделях. Оценка по часам и peer benchmark без подтверждённых
смен не строятся; отсутствие смен само по себе не понижает состояние всего v3-отчёта.
Отдельные OpenAPI schemas карточек и coverage v3 не подменяют v2; `ORDERS` входит только в v3.

Optional seller AI использует отдельный prompt `weekly-interpretation-v26` и input schema5,
с неизменными selector schema1/content schema4. Allowlist содержит только seller-агрегаты, без
employee IDs/имён/карточек и raw rows. V2 сохраняет v25/input4. Job store фильтрует report version
до выбора latest snapshot и сверяет конфигурацией активный prompt при начале каждой provider
attempt; разные prompt/cache пары не смешиваются. Seller planning/preflight/
worker требуют exact CURRENT snapshot. После ответа worker повторяет freshness gate; stale ответ
не публикуется, receipt сохраняется с terminal `SNAPSHOT_NOT_CURRENT`. Read-time enrichment
проверяет точный input/content hash и оставляет все backend-owned факты, состав и действия
неизменными. Отказ чтения необязательных AI status/enrichment данных не скрывает уже готовый
deterministic report: ответ остаётся `CURRENT` с AI `UNAVAILABLE`, без изменения финансовых фактов.
При задержке AI pending states опрашиваются frontend. Существующие snapshots и опубликованные
prompt/schema версии не переписываются.

Приведённый ниже STORE provider flow относится к совместимому legacy v2 пути.

### Opt-in бесплатная историческая подготовка и backlog

Внутренний `SellerWeeklyHistoricalFactsSource` принимает точный Monday-start период, timezone
зафиксированного задания и время проверки. Он не заменяет старую неделю последней закрытой.
Store должен оставаться активным с той же timezone; неделя должна быть закрыта по локальной
полуночи, обе недели — непрерывно покрыты SALES/RETURNS/ORDERS и reconciled. Combined temporal
facts читаются вместе с coverage, stability и source revision в одной read-only RR-транзакции.
UNKNOWN history/author блокирует подготовку, не отбрасывает деньги и не включает current roster.
Отдельный тип результата не позволяет передать temporal facts в прежнюю current-roster assembly.
Temporal snapshot создаётся отдельным opt-in writer, описанным ниже.

`seller_weekly_backlog_state` хранит baseline/timezone и cursor; `seller_weekly_preparation_jobs`
хранит уникальные store/week, состояние, число бесплатных подготовок, next evaluation и lease.
Discovery ограничена страницей 1–52 недели, начинается только с полного authoritative сравнения,
идемпотентна и не затрагивает paid jobs. Нет baseline — нет cursor/заданий. После рестарта
сохраняются и пропуски, и ожидания через несколько границ недель. Изменение timezone/baseline
останавливает discovery/claim, а не переинтерпретирует прошлые даты.
Claim использует `SKIP LOCKED`; отдельный token защищает даже takeover с прежним owner.
Приоритет — время готовности/истечения lease, затем период: уже отложенная старая неделя
не обгоняет ещё не проверенное задание только из-за своей даты.
Истёкший lease не возобновляется heartbeat. `WAITING_SOURCES`/`WAITING_HISTORY` имеют backoff.
Короткие операции выполняются отдельными транзакциями. Lease проверяется по свежему Clock
после захвата блокировок, не только по времени начала запроса.
`FAILED` terminal. `SUCCEEDED` обозначает только привязку exact historical snapshot и актуального
checkpoint, не ИИ-публикацию; текущий current-roster snapshot не подходит. Короткая транзакция
привязки блокирует store/source revision, но paid attempt и публикация требуют своего повторного
atomic fence. Исторический assembler и бесплатный runner этой очереди реализованы локально,
и отдельный opt-in free scheduler подключён к этой очереди. Historical automatic AI planner
читает её точные `SUCCEEDED` bindings; платный worker остаётся отдельным контуром. Периодный read/free planner
реализованы отдельным additive путём, описанным ниже; прежний current-roster read не переключён.
Само наличие таблиц или runner не включает автоматический режим.

Historical AI discovery использует bounded ordered metadata page по period/preparation ID. Только
закрытая `SUCCEEDED` неделя с historical basis, сохранённым baseline/timezone и READY/PARTIAL
report может стать candidate. Exact/paid/terminal/deadline jobs и опубликованные enrichments
исключаются; это оптимизация, не замена writable enqueue fence. Каждый candidate повторно читается
по точному периоду: STALE/PREPARING/BLOCKED, другой snapshot ID или legacy basis откладывают enqueue,
без generation/fallback/provider call. Cooperative budget использует free preparation time budget;
ошибка одной недели не удерживает cursor. Cursor в памяти лишь ускоряет sweep: restart безопасно
повторяет discovery, а durable free jobs и unique automatic weekly job сохраняют пропущенные недели.
Planner сообщает количество созданных или бесплатно перепривязанных jobs, не число paid calls.
Если бесплатная повторная проверка подтвердила тот же immutable snapshot, automatic job с
нулём attempts и `FAILED/SNAPSHOT_NOT_CURRENT` может вернуться в `PENDING` с прежними ID,
deadline и call cap. Уже ожидающая задача не перезапускается; exact approval и любой paid attempt
по-прежнему запрещают такое восстановление. Ошибочная комбинация historical mode с legacy
report contract fail-closed, без fallback к legacy planner.
Bounded `requeueStaleSnapshots` возвращает устаревшую привязку в `PENDING` той же бесплатной
задачи, без перемотки cursor, нового store/week и вмешательства в paid jobs. Проверяются текущий
checkpoint/source revision, latest snapshot и истечение future-action горизонта при новой неделе;
повторный refresh уже ожидающей задачи no-op. Старый отчёт без future actions не переподготавливается
только из-за календаря; immutable payload, published enrichment и paid counters не меняются.

`app.interpretation.seller-weekly-preparation.enabled` по умолчанию false. Он включает только
бесплатный bounded discovery/refresh/preparation на WORKER/COMBINED, с отдельным serial scheduler.
API/MIGRATION не создают scheduler. Нужны parent weekly-review и seller features; baseline
по-прежнему записывается только отдельной approved forward-only операцией, не при startup/tick.
Существующий current-roster snapshot scheduler при этом не создаётся: две несовместимые identity
не должны конкурировать за одну revision chain. При preparation flag существующий AI planner
маршрутизируется только в historical backlog path, не вызывает current-roster reader/planner как
fallback. Для этого дополнительно нужны parent AI и planner flags; free flag сам их не включает.
Release preflight принимает historical preparation вместо legacy snapshot planner как deterministic
предусловие, но сохраняет parent/provider/budget guards. Manual exact worker независим.

Defaults candidate: 10 stores/page, 4 weeks/store/page, 25 stale jobs/refresh, 2 free claims/tick,
scan delay 1 minute, cooperative time budget 1 minute. Числовые/временные bounds проверяются
на startup и release preflight до migration, даже когда free flag выключен. Operator duration
values требуют целое число с единицей ms/s/m/h (включая верхний scan-delay bound `3600000ms`);
bare integers и ISO expressions не принимаются
preflight. Budget проверяется между операциями и не прерывает уже начатую RR preparation.
Short queue transactions имеют timeout 30 seconds. Due work, discovery и stale refresh по очереди
становятся первой фазой, чтобы медленная фаза не вытесняла другие на каждом tick.
Cursor store sweep в памяти — только оптимизация;
per-store week cursor/jobs в БД сохраняются после restart. Один ошибочный магазин не закрепляет
sweep cursor на себе и не скрывает следующий; его безопасный type логируется без exception message.
Неизменённый tick и обычное SOURCE/HISTORY ожидание не создают повторяющихся info-уведомлений.
Этот отдельный free contour не означает завершённый automatic AI/UI cutover или runtime approval.

### Opt-in исторический снимок и идентичность

`SellerWeeklyHistoricalIdentity` читает baseline, membership revision и интервалы в той же
RR-транзакции, что финансовые/attach факты. Оба cohort hash публичного membership обозначают
единую selection сравнения двух недель: baseline, store/timezone, границы периода и отсортированные
интервалы, обрезанные этими границами, включая состояния участия. Это не fingerprint сегодняшнего
списка сотрудников и не две разные выборки по неделям. Численность — union исторически eligible
участников; identity reader проверяет совпадение union с финансовым cohort.
`actionabilityRosterHash` отдельно описывает пересечение исторического состава с текущим roster.
Имена, суммы, причины изменений и внешние provider IDs в identity не входят. Source identity
дополнительно включает source/membership revisions и обе temporal attach policies.
Сегодняшний store-wide freshness timestamp не заменяет доказанный coverage старого периода.

Basis `HISTORICAL_DOCUMENT_MEMBERSHIP_V1` поддерживается backend codec и frontend parser отдельно
от `CURRENT_RANKING_AT_GENERATION`. Current-ranking parsing сохраняет требование одинаковых
selection/action hashes. Historical parsing допускает отдельный action hash, но сохраняет
единую selection обеих недель и запрет future action для `actionableNow=false`.
Historical assembly имеет отдельные `weekly-metrics-v10-sellers-historical` и
`weekly-snapshot-v18`; quality rules не изменены. Прежние версии prompt/input/schema и payload
не переписываются. Карточка ушедшего сотрудника сохраняется; восстановление давно пропущенной
недели не назначает новые действия ни команде, ни текущим сотрудникам на следующую неделю.
Для последней закрытой недели действует обычный actionability gate.

`persistHistoricalCandidate` сохраняет точный закрытый период под store → source publication
locks. После ожидания locks перепроверяет активность/timezone, source revision, неизменный baseline
и membership revision; устаревшие факты не вставляют snapshot/checkpoint. Snapshot и совместимый
checkpoint атомарны. Повтор равного semantic content переиспользует immutable snapshot, обновляя
checkpoint для проверенной source revision; имя и время проверки сами по себе не создают revision.
Это publication детерминированного snapshot, не atomic fence публикации ответа ИИ.

`SellerWeeklyPreparationRunner` за один вызов claims только одно задание. RR preparation не держит
внешнюю write-транзакцию; после чтения проверяется lease, затем writer и exact binding. Потеря lease
не позволяет отмечать успех или оживлять token. Source churn даёт бесплатный backoff на той же
задаче; UNKNOWN history/author остаётся отдельным ожиданием. Техническая/contract failure terminal
с sanitized code, без сохранения raw exception message. Runner сам не имеет AI dependency;
отдельный scheduler вызывает его только по free opt-in flag. Он не создаёт baseline, paid job
или provider call. Historical paid planning отдельным opt-in контуром проверяет exact CURRENT
ещё раз; release/runtime acceptance по-прежнему обязательны.

### Точный периодный historical read и free planner

Additive GET `/api/stores/{storeId}/weekly-reviews/seller-period?periodStart=YYYY-MM-DD` принимает
только Monday-start закрытую неделю в timezone магазина. Нужны те же parent/seller feature gates
и store-scoped authorization, что для seller-current; ответ `private, no-store`. Отсутствующий
параметр, неверный ISO date, не понедельник или открытый период дают HTTP 400; отсутствие магазина
для уполномоченного пользователя — 404. Endpoint не создаёт baseline, cursor, задачу, snapshot,
checkpoint или provider call. Frontend на этот endpoint автоматически не переключён.

Чтение использует одну read-only RR-транзакцию. Только exact latest historical snapshot указанной
недели может получить `CURRENT`: проверяются store activity/timezone, обе границы сравнения,
historical policy versions, compatible/latest ID checkpoint, его outcome и source revision.
Checkpoint из будущего не считается актуальным. Нет historical snapshot — `PREPARING` без отчёта;
current-ranking/STORE payload не подставляется. Исторический payload без актуального checkpoint,
baseline/coverage/stability или canonical identity остаётся неизменным с `STALE`.

Cheap metadata source читает historical interval union, current actionability intersection,
baseline/membership revision, exact coverage/stability, source revision и temporal attach policy,
без финансовых и attach агрегатов. Его canonical hash совпадает с heavy preparation hash.
Проверка идёт по checkpoint identity, а не старому embedded source hash immutable snapshot:
semantic reuse не требует переписывать payload. Новый локальный день сам по себе не делает старую
закрытую неделю stale. Если в ранее latest отчёте остались future actions, новая граница недели,
в том числе во время чтения, требует бесплатной revision с их удалением.

Внутренний `SellerWeeklyHistoricalPlanningService` оценивает только запрошенный period/timezone;
RR assessment/read и fenced RC writer не объединены внешней транзакцией. `CURRENT` — no-op;
иначе одна бесплатная подготовка, writer и reassessment. Source conflict/недоступная история
откладывают результат без immediate heavy retry и current-roster fallback. Planner не имеет
scheduler, HTTP write endpoint или AI dependency.

Seller AI freshness guard выбирает периодный path только по explicit historical basis; прежний
current-ranking path сохраняет latest-week restriction. Бесплатный refresh historical snapshot
может подтвердить тот же immutable ID или отказать; новая revision не заменяет ранее одобренный
snapshot/input/request. Read-time optional AI failure не скрывает deterministic report. Это
as-of RR freshness, не самостоятельное разрешение платного вызова. Локальный candidate теперь
имеет отдельный atomic source fence на startAttempt и completion и historical backlog AI planning;
runtime acceptance этой защиты остаётся незавершённым rollout gate.

`SellerWeeklyAiSourceFence` работает только внутри writable READ_COMMITTED транзакции. Порядок
locks совпадает с snapshot writer: store → source revision. После ожидания locks reader получает
неподделываемый package token текущей транзакции и использует отдельный fenced entry в общей
проверке identity, а не ранее открытый RR snapshot. Обычный historical identity entry по-прежнему
требует REPEATABLE_READ; plain RC, read-only и повторное использование token в другой транзакции
не допускаются. Проверяются
activity/timezone, compatible/latest checkpoint, policy/identity, coverage/stability, membership
и календарный action horizon. Legacy current-ranking сохраняет свой freshness protocol, temporal
report — exact historical period; optional enrichment storage в этой проверке не участвует.
Start attempt держит fence только до commit резервирования/attempt, не вокруг provider network.
Локальные transaction-only lock/statement timeouts ограничивают ожидание; глобальные настройки
БД не меняются. Execution не наследует внешнюю транзакцию, а fence отвергает read-only/RR context.
После budget/job locks повторно проверяются календарь и свежий Clock для lease/deadline; supplied
старый timestamp не оживляет seller claim. Completion сначала сохраняет независимую receipt,
затем под fence либо terminal-отклоняет stale response, либо атомарно завершает job и публикует
enrichment. Потеря lease откатывает обе записи, но не receipt/billing. Изменение источника после
этой точки может сделать опубликованный immutable отчёт STALE; оно не переписывает enrichment
и не разрешает новый automatic job для той же недели.
Automatic enqueue также перепроверяет exact CURRENT под store/source fence, прежде чем занять
единственный store/week job: изменение источника откладывает бесплатную подготовку без job.
Candidate явно хранит `planning_origin`: прежние jobs и manual approvals остаются `EXACT`,
не переклассифицируются по догадке и никогда не меняют snapshot. Новый `AUTOMATIC` привязан
уникальным ключом к store/week; provenance защищён DB constraint/trigger. Пока нет ни одного
attempt/receipt, enrichment или активного lease, тот же automatic job может бесплатно принять
свежий CURRENT snapshot этой недели. Deadline, max attempts, provider/model и job ID сохраняются;
увеличение retry cap или продление окна не является частью refresh. Свободный source failure
`SNAPSHOT_NOT_CURRENT` может быть переоценён, но иной terminal failure и истёкший deadline — нет.
После любого attempt, включая UNKNOWN, перепривязка и новая automatic job запрещены. Старый claim
не может начать попытку с прежним snapshot после rebind. Exact approvals сохраняют исходные hashes;
production activation этим механизмом не включается.
Seller enrichment integrity reader принимает explicit v26/schema-4 только в seller read path;
legacy readable prompt list и STORE selector/schema не расширяются. Успешный provider response
не должен откатывать публикацию из-за ошибочного применения legacy-only prompt allowlist.

```text
weekly-review facts
        ↓
deterministic weekly_review_snapshot
        ↓
bounded store-only provider input
        ↓
YandexGPT selector response
        ↓
structural + semantic validation
        ↓
backend-owned schema4 rendering
        ↓
immutable weekly_review_ai_enrichment
        ↓
GET /api/stores/{storeId}/weekly-reviews/current
```

Snapshot формируется отдельно от AI. Отчёт остаётся доступным в детерминированном виде, если AI
выключен, задержан, недоступен или ответ не прошёл проверку. В seller-v3 candidate автоматический
AI planner создаёт не более одного job на магазин и закрытую неделю независимо от исправлений
immutable snapshot, prompt/revision и конечного статуса прежнего job; автоматический job имеет
ограничение provider-попыток из конфигурации (одна или две), операции сериализуются блокировкой
магазина. Повтор по исправленной revision требует exact approved operator path.
Это ограничение постановки задач, а не утверждение об уже включённом платном режиме.

Legacy публичный путь сохраняет контракт v2 с показателями всего магазина. Отдельный flag-gated
путь собирает v3 по выбранным продавцам: core, структура, допы и финансовые
факты команды используют единый seller facts bundle. Его scope/evidence — `SELLERS`/`EMPLOYEE`,
действия на уровне агрегата — `TEAM`, а проверка доказанного личного снижения — `EMPLOYEE`;
Форма v2 transport и ранее сохранённые payload/hash не изменяются. Незаполненные смены ограничивают только workload-метрики и peer
benchmark: при полном источнике и отсутствии других quality limitations v3 имеет `READY`.
Личное финансовое действие возможно только при не менее шести завершённых продажах в каждой
сравниваемой неделе и материальном отрицательном изменении; смены не используются как суррогат
активности или эффективности. При неполном обязательном source coverage персональные карточки скрыты,
а `BLOCKED` не публикует финансовые значения. Лимит 100 карточек не отбрасывает суммы остальных
выбранных продавцов: v3 хранит `totalCount`, `displayedCount` и финансовый остаток. Это не разрешение
на автопубликацию. V3 writer проверен на цепочке v2→v3→v2→v3: V78 уже
коалесцирует изменения seller-источников до одной revision на магазин за транзакцию.
V87 добавляет событие source change при поздней вставке immutable catalog sale role snapshot:
его INSERT меняет attach-проекцию после исходной продажи, даже если строка продажи не обновлялась.
При миграции V87 однократно инвалидируются checkpoint-ы существующих магазинов,
чтобы отчёт, созданный до новой семантики V86, не оставался CURRENT без пересчёта.
Reader читает revision вместе с фактами в REPEATABLE_READ, а writer под блокировкой
отклоняет устаревший input.
Проверена и гонка с изменением источника между сверкой revision и insert snapshot. Внутренний
candidate service заново читает весь facts bundle при таком конфликте и прекращает попытки после
трёх повторов; canonical source identity включает revision, cohort, недели, coverage, freshness
и версии формул без имён и финансовых значений. Writer дополнительно сверяет по текущим часам
магазина дату ожидаемой полноты и пару отчётных недель: смена локальных суток/недели между чтением
facts и записью snapshot вызывает полный повтор, даже если revision БД не менялась. Под store lock
timezone facts также сверяется с authoritative timezone магазина: новая revision не делает input
с timezone, прочитанной до её смены, корректным. Несовпадение отклоняется до snapshot/checkpoint.
Это пока не
полный протокол публикации: внутренний mutable v3 checkpoint уже фиксирует проверенную identity,
revision и совместимый snapshot атомарно при `CREATED` и `REUSED`; неуспешная попытка его не
продвигает. Внутренний read-only `assessForPlanning` сверяет checkpoint с последней ревизией,
локальным днём и заново вычисленной canonical identity в одной `REPEATABLE_READ` транзакции.
Он различает `PREPARING`, `CURRENT`, `STALE` и сохраняет доступ к предыдущему совместимому
snapshot при `STALE`; откат последней ревизии на v2 не выдаёт v3 за актуальный. Проверка читает
полный metadata input identity без финансовых агрегатов, attach quantities и карточек команды;
source revision сама по себе не заменяет cohort, coverage, freshness, runtime formula versions
и temporal fence. Canonical bytes/hash сохранены. Её вызывают отдельный flag-gated v3 planner
и seller API, но не действующий v2 reader. При `CURRENT` planner возвращает `UNCHANGED`
без генерации и записи checkpoint. При `PREPARING/STALE` генерация допускается только при
`STABLE` и полном непрерывном `SUCCESS` coverage `SALES/RETURNS/ORDERS` для обеих недель;
gate проверяется заново при каждой из максимум трёх попыток. Иначе возвращается `DEFERRED`,
включая незавершённые изменения после `CANCELLED` sync: отмена не откатывает уже записанные окна.
Для восстановления требуется более поздний successful reconciliation затронутого источника/периода,
а не просто старое `SUCCESS` coverage или успех другого магазина. Несколько более поздних окон
могут вместе закрыть затронутый интервал, но gap или окна с неизвестными границами не доказывают reconciliation.
Для metadata-фаз job (`STORES/EMPLOYEES`) достаточно более позднего полного successful job той же
connection независимо от запрошенного финансового периода. Пока reconciliation не доказан,
генерация откладывается без нового snapshot или продвижения identity. После записи, deferral
или исчерпания source-conflict повторов freshness оценивается заново: успешная запись сама
по себе не обещает `CURRENT`.
Для заведомо нестабильного/неполного источника candidate service сначала читает лёгкие metadata
и откладывает generation без финансовых запросов. Успешный preflight не разрешает запись:
полный facts bundle проверяет gate заново в своей RR, hash строится только из полных facts,
writer сохраняет revision/timezone/temporal fences. Metadata проверяются при каждой retry;
ошибка metadata query передаётся вызывающему коду, не превращается в `DEFERRED`.
Обычные ошибки БД не маскируются как deferral. Planner запрещает охватывающую транзакцию, чтобы
RR-чтения и короткая fenced RC-запись оставались раздельными. Это не включает публикацию,
автоматический обход магазинов или AI enqueue. Для активации этапа A остаются gates стабильности источника,
нагрузки, совместимости scheduler/public v3, AI и пользовательского чтения. Исторический membership
и его publication-deadline freshness относятся к отдельному этапу B; этап A не обеспечивает
forward-only изменение состава. Следующие правила
персонального блока описывают действующий
публичный v2, а не целевое историческое membership v3.
Имя сотрудника остаётся в v3 payload для отображения, но исключено из semantic content hash:
одно лишь переименование не должно создавать новую финансовую ревизию.

При attach v4 seller-reader получает только store-wide потенциальные риски атрибуции из
`AttachAttributionQualityRepository`, без повторного расчёта магазинных числителей/знаменателей.
Правила pending warranties и неизвестных авторов возврата совпадают с прежним store path;
unassigned ordinary returns материализуются один раз за запрос. Эти счётчики не становятся
seller-ошибками: seller quantities и собственные classification/condition counters сохраняются,
предварительность применяется только к потенциально затронутым метрикам. Quality reader временно
отключает JIT через `SET LOCAL` в своей/охватывающей транзакции и восстанавливает прежнее значение
после успешного чтения; при ошибке настройка сбрасывается rollback. Это не session/global tuning;
тест с одним доступным физическим соединением проверяет восстановление caller `on/off` и отсутствие
утечки после ошибки. Store attach path, published v2, версии формул и глобальные настройки
PostgreSQL этим изменением не затронуты. Synthetic load проверяет 130 продавцов/19200 item rows:
число SQL round-trips не растёт по одному на продавца, лимит 100 карточек сохраняет точный остаток
финансовых фактов. Внутренний CURRENT scan использует отдельный `SellerWeeklyIdentityFactsSource`,
без вызова полного facts reader. Hash metadata и полного facts bundle сверяются на synthetic load,
отдельный golden фиксирует прежние canonical bytes. Это не production SLA: более широкий
нагрузочный gate и локальный authenticated shadow parity остаются отдельными release gates.

Fingerprint-контекст warranty source/target теперь агрегируется только для соответствующего
документа через LATERAL, а не через полный document-context GROUP BY внутри каждой проверки
allocation. Это отдельная добавочная миграция; прежние ordered MD5 bytes, validity predicates,
deferred constraints и аналитические/финансовые формулы сохраняются. Изменение общей warranty
view действует также на действующие v4 readers, поэтому требует migration parity regression
и полного backend check, а не только seller unit tests. Synthetic mixed workload содержит
320 решений / 432 allocations; отдельный extreme scenario — 320 / 2560. Это не production latency
или bulk-write SLA. Query-count benchmark использует database-wide pg_stat_statements:
во время измерения нельзя выполнять диагностические SELECT в той же test DB.

Для локальных испытаний добавлен внутренний, **неподключённый к расписанию**
`SellerWeeklyV3BatchPlanningService`: последовательный обход активных LiveSklad-магазинов по UUID
через существующий target repository. Бюджет задаётся явно: 1–100 магазинов, страница 1–25 targets,
1 ms–5 min монотонного elapsed time. В памяти остаются одна страница targets и результат одного
магазина; наружу возвращаются только counters, причина остановки и cursor последнего обработанного
магазина, без facts/snapshot graphs. Проверка бюджета происходит перед fetch и каждым магазином:
это cooperative deadline, не отмена текущей транзакции и не hard wall-time/heap SLA.
`STORE_LIMIT` не делает дополнительный fetch: при точном cap следующий resume может вернуть
`EXHAUSTED`. `DEFERRED` означает только source/coverage deferral одиночного planner; ошибки БД
и конфигурации распространяются вызывающему коду. Охватывающая транзакция запрещена.
При failure уже завершённые магазины могут иметь локальные snapshots; повтор прежнего cursor
безопасен через unchanged/content-reuse paths. Набор targets не зафиксирован на весь sweep:
добавление/активация UUID до cursor попадёт в следующий полный sweep с `null`, не в resume.
Это не публичный API, scheduler activation, AI enqueue или разрешение на production rollout.
Synthetic batch/concurrent-bulk/manual-allocation проверки измеряют конкретную локальную среду;
thread allocated bytes — объём выделений текущего потока, не retained/peak heap всего процесса.
Проверки не включают глобальные JVM/PostgreSQL настройки или отключение source fences.
Внутренний v3 дополнительно проверяет непрерывное `SUCCESS`-покрытие обеих недель по SALES,
RETURNS и ORDERS: позиции заказов могут входить в продавческие SALE-документы. Дыра между sync
окнами, `PARTIAL_SUCCESS`, активный или неустранённый failed sync блокируют числовую выдачу
кандидата. ORDERS пока обозначается отдельным `ORDERS_COVERAGE_INCOMPLETE` limitation, чтобы не
расширять enum публичного v2 OpenAPI. Это защита полноты; revision fence отдельно защищает
согласованность уже прочитанных фактов с записью snapshot.

Legacy v2 подтверждает атрибуцию точным DB `changed_at`, прочитанным в той же `REPEATABLE_READ`
транзакции, что и facts. `calculatedAt` остаётся только временем расчёта/отображения и не является
watermark. `attach_snapshot_checks.checked_through` хранит наблюдавшийся marker; его сравнивают
на равенство, не `>`/`greatest` с application clock. Отсутствие marker записывается как `-infinity`.
Решение, зафиксированное после чтения facts, не подтверждается задним числом. Старый checkpoint
с wall-clock временем при несовпадении требует повторного расчёта; неизменное содержимое сохраняет
snapshot ID/hash без лишней revision. Опубликованные payload, формулы и migrations не меняются.
Это legacy invalidation protocol; v3 по-прежнему использует отдельный monotonic source revision fence.

Персональный блок сотрудников использует тот же roster продавцов, что и рейтинг: активный
сотрудник, активное назначение и `participatesInRanking=true`. Сотрудники вне рейтинга не попадают
ни в персональные карточки Weekly Review, ни в командный benchmark. Для появления сотрудника в
карточках также нужна активность хотя бы в одном из двух сравниваемых недельных периодов.

Когда в любой из двух недель гарантийный attach-rate предварителен из-за неразобранного конфликта,
соответствующее сравнение в карточке сотрудника недоступно: значения и выборки не передаются в
персональные выводы, а снижение не создаёт действие для руководителя. Финансовые показатели
сотрудника при этом остаются доступными. После решения конфликта новый snapshot пересчитывает
показатель. Для v4 наличие возвратов без назначенного сотрудника консервативно ограничивает все
персональные сравнения attach-rate за затронутую неделю.
Во внутреннем seller-v3 неизвестная атрибуция возврата также делает предварительным только
затронутый код attach-rate, хотя числитель и знаменатель остаются выбранными по продавцам.
Отдельно финансовый reader считает включённые документы RETURN без source employee и RETURN
с source employee, который не разрешён в той же LiveSklad connection, двумя разными причинами:
`RETURN_EMPLOYEE_MISSING` и `RETURN_EMPLOYEE_UNRESOLVED`. Удалённые строки и полностью `EXCLUDE`
документы не создают финансового ограничения. Эти store-wide неопределённости
не добавляются к seller-суммам и не блокируют отчёт. При наличии хотя бы одной v3 становится
`PARTIAL`: затронутые return/net/profit, структура, допы и личные финансовые выводы получают
`LIMITED`, а SALE revenue/count и средняя продажа остаются доступными. Менеджер видит адресное
ограничение без персонального обвинения; общий заголовок не утверждает, что неделя лучше
или хуже, пока чистый итог предварителен. Известный source employee учитывается независимо
от доступности оригинала; сама поздняя привязка не подменяет аналитического автора. После
разрешения сотрудника новый snapshot может стать `READY`. Новая candidate-семантика использует
`weekly-snapshot-v17` / `weekly-quality-v11` и `weekly-metrics-v9-sellers-return-processor`;
ранее сохранённые v3 и legacy v2 остаются неизменяемыми и читаемыми.
Счётчики неизвестных сотрудников возврата и pending warranty описывают потенциальный риск по всему магазину,
а не количество ошибок конкретного продавца; окончательный вывод по ним не формируется.

### Roster и исторические snapshots

V91 уже хранит интервалы участия после явно утверждённого baseline; отдельная document-level
проекция eligibility проверена синтетически, но текущий seller-v3 facts reader пока её не использует.
Локальный внутренний `SellerHistoricalFinancialFactsService` применяет эту selection к денежным
employee/category агрегатам и документным totals сразу для двух последовательных полных недель.
Вся подготовка выполняется в одной `REPEATABLE_READ` транзакции. Baseline должен покрывать начало
предыдущей недели; открытый период, отсутствующий интервал или неизвестный автор возврата
возвращают явную preparation failure, а не нулевой/урезанный исторический итог. Roster — объединение
исторически eligible участников обеих недель, включая ушедших; нулевые строки сохраняются для
сопоставления. Отдельный immutable `currentActionEmployeeIds` содержит только пересечение с
текущим составом и не меняет исторические суммы. Финансовая basis обозначена
`HISTORICAL_DOCUMENT_MEMBERSHIP_V1`; общий cohort fingerprint сам по себе не доказывает temporal
семантику. Financial-only результат остаётся отдельным типом. Внутренний opt-in
`SellerHistoricalFactsService` дополняет его attach и return-quality в той же RR-транзакции,
возвращая `SellerHistoricalComparisonFacts`, а не готовый публичный отчёт.

Temporal attach требует v4 policy и читает отдельную projection `seller_attach_item_facts_v1`
с document/item/time provenance. Обычная продажа и возврат проверяют membership своего автора
на timestamp собственной операции; сотрудник возврата разрешается в точном LiveSklad scope,
без fallback. Специальная гарантийная allocation и её device base сохраняют прежнего продавца,
дату и количества целевой продажи; membership проверяется на timestamp этой продажи.
Отсутствующая история/автор или eligible attach-author вне финансового cohort останавливают
подготовку, а не silently drop строку. Legacy v3 fallback запрещён. Старые attach views,
allocations и правила классификации не переписываются; store-wide preliminary quality не
превращается в персональное обвинение.

Opt-in historical presenter сохраняет финансовую карточку ушедшего продавца с явной пометкой
«не в текущей команде», `actionableNow=false` и без future action. Current-roster presentation
сохраняет прежнее поведение. Historical identity/assembly и free backlog runner уже доступны
отдельному внутреннему пути; additive membership parsing поддерживает оба basis. Это ещё не
historical current-screen cutover: основной current reader остаётся current-roster; отдельный
opt-in free scheduler использует temporal backlog и исключает competing legacy snapshot scheduler.
Additive exact-period GET/free planner и opt-in automatic historical paid planning соединены с preparation.
Текущий Overview и first-manual
path не переключены. Payroll/saved employee и snapshots не переписываются. Нужны automatic
current-reader cutover, runtime acceptance atomic AI source fence и release gate до включения автоматики.
Следовательно, описанный ниже roster остаётся current-roster,
не восстановленным историческим составом. Автоматическое восстановление пропущенной недели
пока не включено; exact historical period можно читать отдельным additive GET без generation/AI.

Roster вычисляется во время формирования snapshot, а не при каждом открытии страницы. Активностью
считается хотя бы одна завершённая продажа, ненулевая чистая выручка или смена в текущей либо
предыдущей неделе. Сотрудник, добавленный после отчётной недели и не имеющий активности в обеих
неделях, в такой отчёт не попадает даже после включения флага рейтинга.

Payload snapshot, включая состав и имена сотрудников, хранится неизменяемо. Изменение назначения,
активности или `participatesInRanking` не переписывает уже сохранённый отчёт и не фильтрует его на
read path. Поэтому историческая revision может содержать сотрудника, который сейчас исключён из
рейтинга, либо не содержать сотрудника, добавленного позднее.

Повторная генерация той же завершённой недели создаёт следующую immutable revision только если
содержимое изменилось; при том же content hash возвращается существующая revision. После начала
новой недели обычная генерация нацелена уже на последнюю завершённую неделю — произвольный
исторический период endpoint не принимает. Включён ли автоматический planner в production,
фиксируется только в [`project-state.md`](../project-state.md).

## Активный контракт

| Элемент | Версия | Источник |
|---|---|---|
| Prompt | `weekly-interpretation-v25` | [`weekly-interpretation-v25.md`](../../prompts/weekly-interpretation-v25.md) |
| Provider input | schema 4 | [`weekly-review-ai-input-v4.schema.json`](../../schemas/weekly-review-ai-input-v4.schema.json) |
| Provider output | selection schema 1 | [`weekly-review-ai-selection-v1.schema.json`](../../schemas/weekly-review-ai-selection-v1.schema.json) |
| Published content | schema 4 | [`weekly-review-ai-content-v4.schema.json`](../../schemas/weekly-review-ai-content-v4.schema.json) |
| Deterministic metrics policy | `weekly-metrics-v8-return-processor` | `WeeklyReviewPolicyV1` |
| Deterministic snapshot policy | `weekly-snapshot-v14` | `WeeklyReviewPolicyV1` |
| Data-quality policy | `weekly-quality-v9` | `WeeklyReviewPolicyV1` |

Эти версии описывают код ветки, не состояние production. Аналитический return author отделён
от общего сохранённого `employee_id` по ADR-0006. Metadata и полный v3 reader используют одну
новую policy identity: прежний checkpoint не становится CURRENT только из-за неизменной source
revision. Legacy planner также проверяет policy versions перед переиспользованием. Опубликованные
снимки, prompts и schemas не переписываются, новая генерация создаёт новую immutable revision.

Backend читает опубликованные schema4 enrichments в порядке `v25`, `v24`, `v23`, `v22`. Worker
создаёт только активную пару `v25/schema4`. Read compatibility не означает, что старые версии
снова допустимы для генерации.

### Детерминированное управленческое представление

`WeeklyReviewSummaryPresenter` формирует один категориальный итог: неделя лучше, слабее, изменилась
разнонаправленно либо существенно не изменилась. Категория учитывает все материальные KPI со
состоянием `READY`; ограниченные и недоступные KPI не меняют направление вывода. Итог не повторяет
точные значения четырёх KPI; при наличии материального фактора он добавляет одну главную зону
внимания, а при её отсутствии — один положительный сигнал. `summary.effect` описывает общий
результат, а не тон добавленного фактора.

Корневое действие берётся из первого элемента backend-списка и должно однозначно соответствовать
фактору по `metricCode` и evidence. Оно остаётся проверкой, а не сохраняемой задачей: API уже
передаёт операцию, числовой ориентир, горизонт и способ проверки.

Presentation model не повторяет однозначно связанный с корневым действием фактор второй
равноправной карточкой в `Что изменилось`: числа и evidence остаются доступны через основание
действия, а остальные материальные факторы сохраняются в списке. Если связь неоднозначна, frontend
ничего не скрывает. Полное отсутствие исходных факторов и отсутствие только вторичных факторов —
разные состояния: во втором случае секция скрывается без ложного сообщения о спокойной неделе.
Deterministic действие по росту возвратов сформулировано как операция менеджера —
`Проверить чеки и причины возвратов`. Изменение persisted wording выпущено новой policy
`weekly-snapshot-v12`. Бизнес-правила по нулевой себестоимости и возвратам без доступной исходной
продажи выпущены новой policy `weekly-snapshot-v13`; прежние immutable snapshots не
переписываются и остаются читаемыми.

В персональном блоке чистая выручка означает вклад сотрудника. `peerComparison` означает только
сравнение выручки в час с медианой минимум трёх сотрудников, у которых достаточно продаж, смен и
часов в обеих сравниваемых неделях. Старый сохранённый payload с
`peerComparison.metricCode=NET_REVENUE` принимается frontend для совместимости, но не показывается
как сравнение эффективности. Frontend дополнительно требует `participatesInBenchmark=true` и
готовую достаточную метрику `REVENUE_PER_HOUR`; несовместимый peer payload не отображается.

Незаполненные или недостаточные смены ограничивают только `SHIFT_COUNT`, `WORKED_HOURS` и
`REVENUE_PER_HOUR`. Такие workload-метрики получают адресное состояние `UNAVAILABLE`/`LIMITED`, но
сами по себе не переводят сотрудника, команду или весь отчёт в `LIMITED`/`PARTIAL`, не создают
сотруднику приоритет и не порождают действие. Достаточные sales-выводы при этом сохраняются.

## Provider boundary

`WeeklyReviewAiInputCompactor` принимает только `READY` или `PARTIAL` report и проецирует:

- store-level summary outcome;
- store-level factors и список допустимых selector-ов;
- store-level actions с backend-owned `title`, `check` и evidence references;
- только доступные store-level evidence values.

Employee scope и employee public IDs в input запрещены. Модель возвращает selector-ы для summary и
каждого фактора. Она не возвращает свободный пользовательский текст, KPI, action title/check или
новые evidence references.

### Operator preflight и exact approval

Authenticated admin endpoint
`GET /api/admin/weekly-review-ai/snapshots/{snapshotId}/preflight` строит exact provider request
network-free и без enqueue. Он доступен при выключенных generation/worker flags, чтобы решение о
включении не требовало предварительной записи. Ответ содержит только:

- snapshot/store IDs, revision, завершённый период, report state и content hash;
- активные prompt/input/selection/content versions;
- provider code и конечный сегмент versioned model URI, но не folder ID или полный URI;
- canonical input/request hashes, размеры bounded input и верхнюю оценку tokens/cost;
- текущий daily cost, технические limits и состояние exact job/enrichment;
- структурный privacy verdict `PASS_STORE_ONLY_SCHEMA`.

Ответ не содержит compacted input, названия магазина, employee scope, имена, provider response или
credentials. Privacy verdict доказывает только store-only форму schema4: отдельного общего scrubber
для backend-owned подписей всё ещё нет, поэтому verdict не заменяет security/privacy approval.

API runtime намеренно не получает provider API key. Поле `providerCredentialCheck=WORKER_ONLY`
означает, что preflight проверил schema, versioned model, context и budget, а наличие credential
проверяется отдельным root read-only audit и повторно worker-ом перед outbound request.

`POST /api/admin/weekly-review-ai/snapshots/{snapshotId}/generate` требует body с exact snapshot,
input и request hashes из свежего preflight, одобренным числом provider calls, точной верхней
стоимостью и валютой. Пустой body возвращает `428`; устаревшее или конфликтующее approval — `412`.
Snapshot row lock и уникальность job закрывают гонку между повторными enqueue. Для первого canary
разрешается только один provider call, даже если технический предел конфигурации выше.

## Validation и rendering

1. Input сериализуется канонически, проверяется packaged input schema и получает SHA-256.
2. Provider response проверяется selection schema.
3. Semantic validator требует точный набор факторов, разрешённые selector-ы и корректные роли
   positive/negative focus.
4. `WeeklyReviewAiRendererV25` формирует итоговый schema4 текст из backend-owned facts.
5. Итог снова проходит content schema и semantic checks.
6. Completion в одной транзакции сохраняет enrichment и завершает attempt/job.

Для `PARTIAL` backend явно добавляет ограничение, что вывод основан только на доступной части
данных. Каждая quality-проблема привязана к конкретным block IDs и metric codes. Неполная
аналитическая классификация ограничивает только структуру продаж и attach, но не чистую выручку,
валовую прибыль, команду или сотрудников. `SALE_PAYMENT_MISMATCH` и
`RETURN_PAYMENT_MISMATCH` остаются диагностикой источника, но не ограничивают weekly
`NET_REVENUE`: цены, скидки и способы проведения оплаты находятся в ответственности CRM.
`RETURN_CASH_TRANSACTION_MISMATCH` также не создаёт limitation, когда сумма активных app payments
точно равна сумме latest raw `detail.cash`; без такого доказательства правило остаётся fail closed и
ограничивает чистую выручку. Остальные проблемы согласованности продаж/возвратов ограничивают
чистую выручку, её разложение и основанный на ней главный вывод, но не переносятся на независимые
метрики. Действительно отсутствующая себестоимость ограничивает валовую прибыль, маржу и зависящий
от них главный вывод. Нулевая себестоимость является допустимым бизнес-значением: открытое `INFO`
и diagnostic counter сохраняются, но limitation не создаётся и состояние прибыли, маржи или всего
отчёта не понижается. Отсутствующая исходная продажа либо позиция у части возвратов также не
считается нарушением согласованности итогов магазина; сумма возврата уже входит в чистую выручку.
Отдельно, если отсутствует или не разрешается сотрудник записи возврата LiveSklad, legacy-блок
команды показывает нейтральную оговорку о неизвестном авторе, не создавая page-level warning
и не подменяя автора продавцом исходной продажи. Сама по себе отсутствующая связь с оригиналом
не означает неизвестного сотрудника возврата.
Если `PARTIAL` возник только внутри структуры или команды, assembler добавляет такой блок в общую
сводку качества даже при отсутствии корневого quality limitation. Frontend объединяет корневые и
локальные тексты в одной панели ограничений, а у затронутого главного вывода показывает короткий
маркер доверия вместо повторения полного предупреждения.
Несовместимый enrichment игнорируется; детерминированный отчёт остаётся источником ответа.

## Неизменяемость и повторный запуск

### Fencing перед платной попыткой

Начало provider attempt атомарно проверяет status, владельца, неистёкший lease, deadline и
счётчик attempts именно полученного claim. Один claim нельзя повторно использовать для второго
запроса, даже если retry cap допускает две попытки; повтор требует нового claim. Потеря lease
останавливает worker до provider call, не переводя задачу другого владельца в FAILED.
Heartbeat не оживляет истёкший lease, не сокращает действующий и не продлевает его за deadline.
Один локальный runner сериализует runNext, но heartbeat работает независимо от выполнения.

Закрытая неделя имеет явную календарную идентичность Monday–Sunday в timezone магазина;
closure наступает в полночь следующего понедельника, включая DST. Для будущего temporal
сравнения authoritative baseline должен покрывать начало предыдущей недели, не только текущей.
Сам этот календарный тип не включает period backlog или temporal aggregates.

Продуктовое уточнение baseline/retries записано в
[ADR-0005](../../decisions/ADR-0005-weekly-ai-activation-and-retries.md). Оно не включает
автоматический planner на production. При его последующем включении seller enqueue использует
настроенный retry cap; это не разрешение повторять UNKNOWN запрос.

Retryable отказ с известным outcome или невалидный semantic ответ может дать следующую attempt
в той же задаче, с backoff и прежним deadline. UNKNOWN outcome либо истечение lease после
startAttempt завершают задачу без автоматического платного повтора, даже при доступном retry cap.
Если lease истёк до первой attempt, допускается новый claim без расхода. Ошибка подготовки одного
магазина логируется безопасным failure_type и не останавливает сканирование остальных магазинов.

Перед подготовкой provider request worker может бесплатно повторно оценить последнюю закрытую
неделю. Это снимает STALE, вызванный изменением source revision без изменения содержимого, только
если writer переиспользовал тот же immutable snapshot и exact freshness повторно подтверждена.
Другой snapshot ID не подставляется в существующую задачу, включая exact approved job. Старый
период не инициирует генерацию новой недели. Read-only preflight/isCurrent и проверка после
ответа ничего не генерируют; перед startAttempt повторяется чистая проверка актуальности.

`weekly_review_ai_enrichments` имеет уникальность по snapshot/prompt/schema и DB trigger против
update/delete. Повторная запись с теми же input/content hashes идемпотентна; другое содержимое для
того же ключа отклоняется.

Завершённые attempts защищены от изменения. `weekly_review_ai_jobs` остаются изменяемыми
lifecycle-записями для lease, retry и terminal state. Новая редакция отчёта создаёт новый snapshot
и новый immutable enrichment, а не переписывает старый.

В локальном кандидате полученный provider response и итог валидации отдельно сохраняются в
`weekly_review_ai_response_receipts` транзакцией `REQUIRES_NEW` до публикации/retry transition.
Один attempt имеет одну неизменяемую receipt, привязанную к job/attempt number/request/input
hashes; одинаковый повтор записи идемпотентен, конфликт содержимого или billing metadata
отвергается. Receipt может добавиться после recovery, не меняя завершённую UNKNOWN attempt.
Публикация и retry требуют живого lease, deadline и точного attempt count. При потере права
публикация откатывается, receipt и известный расход сохраняются, чужая/terminal job не оживает.
Учёт RUB использует receipt вместо дублирующей attempt cost, не складывает их; неизвестная
стоимость продолжает резервировать оценку. Атомарный budget guard сериализует также записи
receipt. Сбой самого валидатора фиксируется безопасным `VALIDATION_EXECUTION_FAILED`, без
автоматического платного повтора. Это локальная реализация, не runtime acceptance или активация.
Crash процесса/БД до durable записи ответа остаётся UNKNOWN: нельзя восстановить несуществующую
receipt или считать расход нулевым. Все provider payloads остаются в защищённой БД, не в логах,
API диагностике, документах или evidence.

## Read path и frontend fallback

`WeeklyReviewService` сначала читает latest snapshot завершённой недели, затем пытается применить
первый совместимый опубликованный enrichment. При отсутствии enrichment возвращается тот же
deterministic response с состоянием AI: `DISABLED`, `PREPARING`, `DELAYED`, `UNAVAILABLE` или
`NOT_APPLICABLE`.

Frontend показывает legacy weekly insight только когда новый endpoint не имеет сохранённого ответа
и вернул `404`/`null`. Legacy явно помечается как предыдущий формат, чтобы пользователь не принял
его за новый Weekly Review. Ошибка transport/schema/server не включает legacy: frontend показывает
ошибку загрузки и действие повтора.

Ошибка фонового обновления уже показанного v25 snapshot не переключает пользователя на legacy:
сохраняется последняя версия с компактной заметкой. Это compatibility fallback всего weekly-review,
а не fallback отдельного AI слоя.

### Presentation contract

Первый содержательный экран имеет фиксированный manager-first порядок:

1. компактный header с последней завершённой неделей, периодом сравнения и временем обновления;
2. общий вывод и одна приоритетная проверка рядом на desktop и друг под другом на узких экранах;
3. четыре KPI: чистая выручка, валовая прибыль, маржа и средняя продажа.

На desktop карточки итога и приоритетной проверки растягиваются до одной высоты текущей grid-строки
без фиксированной высоты; их действия выровнены по нижней границе. На tablet/mobile они идут друг
под другом и сохраняют естественную высоту.

Проверка показывает backend-owned название, числовой ориентир, критерий и следующую полную неделю.
Дополнительные проверки свёрнуты внутри того же action-блока. Название «Что проверить на этой
неделе» сохраняется, пока в продукте нет task-state с назначением и выполнением.

Frontend показывает `Дополнено ИИ` только когда опубликованный summary действительно имеет
`generatedBy=AI_ENHANCED` и `aiEnhancement.state=READY`. Для детерминированного отчёта отдельная
подпись источника не показывается; отсутствие AI enrichment не маскирует детерминированный отчёт
как ошибку и не меняет порядок бизнес-блоков.

После KPI расположены независимые вторичные секции: до трёх факторов в «Что изменилось», свёрнутая
«Структура продаж» без графика и «Команда». Связанный с primary action фактор принадлежит верхнему
decision-блоку и не повторяется здесь. В спокойном READY вместо пустых больших блоков показывается
короткое нейтральное сообщение.

Заголовок команды показывает одно сообщение `N из M требуют проверки`, после него без отдельной
серой сводной плашки расположены максимум три карточки с реальным `ATTENTION`. Карточка не повторяет
общий статус: она содержит конкретную причину, ориентир и короткие действия `Почему сотрудник в
списке` и `Открыть сотрудника`; доступные имена действий включают имя сотрудника. Frontend не
выбирает первого сотрудника автоматически. Метрики, evidence и сравнение эффективности открываются
по запросу. Сотрудники `LIMITED`, `POSITIVE` и `STABLE` не занимают основной экран.

Evidence, формула чистой выручки, ограничения и подробность сотрудника открываются единым
`ReviewDetailPanel`: правой панелью на desktop и bottom sheet на mobile. Одновременно существует
один detail context. Панель закрывается кнопкой, `Escape` или backdrop, удерживает фокус, скрывает
фон от accessibility tree и возвращает фокус на исходный триггер.

Для `PARTIAL` header показывает нейтральный статус, а под ним находится одна сводка качества.
Надёжные KPI и секции сохраняются; адресная ссылка у затронутой метрики объясняет только её
ограничение. Локальные ограничения структуры и команды входят в ту же сводку и доступны из своей
секции. Если backend не сформировал проверяемое действие, `PARTIAL` не обещает, что дополнительная
проверка не нужна: экран предлагает сначала уточнить ограничения. Нормальный `READY` не получает
success-плашку. `BLOCKED` скрывает длинный недостоверный
отчёт и показывает причину с исправлением; для недоступного менеджеру исправления панель называет
администратора или ответственного за загрузку данных без ложной кнопки действия. `PREPARING`
остаётся отдельным состоянием прогресса.

Текущее значение маржи приходит из backend по формуле `grossProfit / netRevenue × 100%`.
Изменение маржи показывается как абсолютная разница в процентных пунктах, а не как относительный
процент между двумя значениями маржи.

На mobile KPI остаются сеткой 2×2, decision-блоки идут последовательно, структура превращается в
двухколоночные строки, а detail panel становится нижним листом. В плотном сценарии сначала видны
один вторичный фактор и один сотрудник; остальные из уже ограниченных трёх раскрываются кнопками
`Ещё N изменений` и `Ещё N сотрудников` в том же DOM. На tablet/desktop эти элементы сразу видны
полностью. Интерактивные элементы сохраняют доступную область нажатия; страница не создаёт
горизонтальный overflow. Графиков в этой версии нет, поскольку weekly-review endpoint не
предоставляет согласованный временной ряд.

## Telegram boundary

Публикация schema4 enrichment не создаёт `notification_events`. Текущий weekly Telegram fanout
читает legacy `llm_interpretations` и поддерживает schemas 1–3. Прямого schema4 bridge нет; нельзя
объявлять weekly Telegram частью v25 до отдельной реализации и E2E/poison-event tests.

## Ошибки и неполные данные

- Quality counters для Weekly Review вычисляются только по текущей и предыдущей сравниваемым
  неделям. Открытая store-wide проблема вне этих периодов не переводит отчёт в `PARTIAL`.
- `BLOCKED` snapshot не передаётся AI и получает `NOT_APPLICABLE`.
- Невалидный provider response не публикуется.
- Budget, deadline, request-size и context-window violations завершаются fail-closed.
- Ошибка чтения weekly-review endpoint не маскируется legacy-представлением.
- Ошибка чтения отдельного enrichment логируется без раскрытия payload; следующий candidate может
  быть проверен, после чего остаётся deterministic fallback.
- `PARTIAL` допускает AI только при наличии deterministic outcome и явно сохраняет ограничение.
- Отсутствие смен не является store-wide quality issue и не должно скрывать доступные sales-выводы.
- Нулевая себестоимость не является quality limitation Weekly Review; отсутствие себестоимости
  остаётся ограничением.
- Отсутствие исходной продажи или позиции у части возвратов не входит в store-level consistency
  count и не меняет известного сотрудника возврата. В legacy v2 неизвестный сотрудник записи
  LiveSklad объясняется внутри блока команды; в seller-v3 отсутствующий либо неразрешённый
  сотрудник возврата получает отдельное metric-scoped ограничение без блокировки всего отчёта.
- У сотрудника, уже попавшего в список по независимому sales-сигналу, отсутствие time-оценки
  объясняется локально: `Часть смен не заполнена — оценка по часам недоступна`. Эта подпись не
  меняет attention, action или report state.

## Расхождения и открытые решения

- В input нет employee scope, но текстовые `factor.title`, `evidence.label`, `action.title` и
  `action.check` не проходят отдельный PII scrubber/allowlist.
- Schema4 Telegram publication bridge отсутствует.
- Production enablement, очередь и последний successful enrichment нельзя выводить из кода;
  требуется sanitized runtime evidence.

## Проверка

Новый legacy-v2 assembler с самостоятельным return-processor author использует отдельный golden
`frontend/src/test/fixtures/weekly-review-v2-return-processor-ready.json`; точные serialization bytes
проверяются backend assembler test, а transport parsing — frontend contract test. Прежний
`weekly-review-v2-ready.json` не переписывается и остаётся compatibility fixture для старых codec
и frontend consumers. Изменение policy headers не разрешает переписывать published snapshots.

Contract tests проверяют resource versions, input/selection/content schemas, semantic selector
rules и renderer. Integration tests проверяют immutable persistence, budget reservation,
job lifecycle и атомарное завершение. Полноценное подтверждение сборки требует clean `bootJar` и
сверки packaged hashes с manifest; локальный `build/resources` не является доказательством.

## Триггеры пересмотра

Новая версия prompt/schema, изменение selector vocabulary, compactor, renderer, read-order,
enrichment immutability, AI state, frontend fallback или Telegram publication обновляет этот
документ в том же PR.

## Изменения атрибуции attach v4

Гарантийные конфликты ограничивают только затронутые attach-метрики. Для v4 общий счётчик
неоднозначных гарантий не делает остальные attach-строки неполными. Версии metricsPolicy и
qualityPolicy получают суффикс `-attach-v4`; форма опубликованных старых контрактов сохраняется.

Решение гарантии записывает durable invalidation магазина в своей транзакции. Материальное
изменение источника также помечает существующие обзоры. `current` и planner проверяют изменение
после расчёта/последней проверки конкретного снимка; создают новую детерминированную ревизию
или подтверждают неизменившийся content hash. Проверка отделена от immutable payload.
Автоматического платного AI-вызова в сохранении решения нет; enrichment старого snapshot ID
не используется для нового снимка. Опубликованные месячные/годовые отчёты не переписываются.
