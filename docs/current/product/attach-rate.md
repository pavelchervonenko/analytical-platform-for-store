---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/attach-rate-api.md
  - docs/analytics-business-rules-draft.md
implementation_sources:
  - backend/src/main/resources/db/migration/V38__attach_rate_units_methodology.sql
  - backend/src/main/java/com/storeanalytics/metrics/service/AttachRateService.java
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeRatingService.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics/repository/AttachRateIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/AttachRateServiceTest.java
  - backend/src/test/java/com/storeanalytics/common/database/CareClassificationMigrationIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - product
  - backend
review_triggers:
  - attach-rate-change
  - classification-change
  - return-attribution-change
supersedes:
  - receipt-cooccurrence-attach-rate-methodology
superseded_by: null
---

# Attach-rate v3

Методика `attach-rate-v3` поштучная и не требует доп и технику в одном чеке:

```text
N = sold add-on units - returned add-on units
B = sold base-device units - returned base-device units
AttachRate = max(0, N) / B * 100%, if B > 0
AttachRate = null, if B <= 0
```

| Код | Числитель | База |
|---|---|---|
| `CASE_APPLE_IPHONE` | Чехлы iPhone | Все iPhone |
| `CHARGER_CABLE` | Зарядки/кабели телефонов | Все телефоны |
| `GLASS_IPHONE` | Стёкла iPhone | Все iPhone |
| `GLASS_CAMERA_IPHONE` | Защита камеры iPhone | Все iPhone |
| `FILM_PHONE` | Плёнки телефонов | Все телефоны |
| `SETUP_SERVICE` | Настройка | Телефоны + MacBook + PlayStation |
| `CASE_SAMSUNG` | Чехлы Samsung | Новые и Б/У Samsung |
| `GLASS_SAMSUNG` | Стёкла Samsung | Новые и Б/У Samsung |
| `GLASS_CAMERA_SAMSUNG` | Защита камеры Samsung | Новые и Б/У Samsung |
| `ACCESSORY_PODS_WATCH` | Аксессуары AirPods/Watch | AirPods + Apple Watch |
| `ACCESSORY_IPAD` | Аксессуары iPad | iPad |
| `WARRANTY_GENERIC_USED` | Гарантии Б/У | Б/У iPhone + Samsung |
| `WARRANTY_GENERIC_NEW` | Гарантии новых | Новые/ASIS iPhone + новые Samsung |
| `PREMIUM_PROTECTION` | Название совпало с `privilege care|ultimate care|elite care` | iPhone и Samsung new/used; iPad; MacBook/другие Mac; AirPods; Apple Watch; headphones; PlayStation |

Это точное текущее поведение `attach_rate_item_facts_v3`, а не нормализованная product-категория.
Поэтому Elite/Privilege, классифицированные как гарантия, всё равно попадают в attach numerator по
названию. Напротив, строка с `Premium`, но без одного из трёх regex-названий, может иметь
аналитическую категорию `PREMIUM_PROTECTION` и при этом не войти в одноимённый attach numerator.
`OTHER_PHONE`, `OTHER_DEVICE` и Dyson не входят в denominator, но used iPhone/Samsung входят.

Это подтверждённое семантическое расхождение между V32/auto-classification и V38 attach projection.
Тест полностью доказывает Ultimate Care и широкую базу, но не отдельные Privilege/Elite/Premium
варианты. До согласования с заказчиком показатель нельзя описывать как «категория протекции»:
это отдельная name-based attach-метрика.

Store benchmark использует все факты магазина. Employee rows — отображаемый roster; residual
показывает «вне рейтинга / без сотрудника». Для rating employee attach участвует при базе не меньше
`3` и положительном store benchmark.

`rate=null` — «нет продаж для расчёта», отсутствующий benchmark — «нет среднего по магазину», база
ниже порога — «недостаточно продаж». Quality counters сопровождают unmatched numerator,
ambiguous warranty и unknown condition.

Store rate не зависит от employee attribution; employee rate периода с возвратами ограничен
[ADR-0001](../../decisions/ADR-0001-return-employee-attribution.md). Integration tests покрывают 14
строк, units, возвраты, clamp и quality.
