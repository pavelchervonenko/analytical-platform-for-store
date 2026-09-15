---
doc_schema: 1
doc_type: current
status: current
owner: frontend
audience:
  - developer
  - manager
last_verified: 2026-09-09
requirement_sources:
  - docs/current/product/business-metrics.md
  - docs/current/product/attach-rate.md
implementation_sources:
  - frontend/src/dashboard/OverviewPage.tsx
  - frontend/src/dashboard/OverviewManagementSections.tsx
  - frontend/src/employees/rating-ui.ts
verification_sources:
  - frontend/src/dashboard/OverviewPage.test.tsx
  - backend/src/test/java/com/storeanalytics/metrics/repository/AttachRateIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - frontend
  - product
review_triggers:
  - sales-structure-ui-change
  - attach-map-ui-change
  - classification-change
supersedes: []
superseded_by: null
---

# Структура продаж и карта допродаж

Структура из `/overview-metrics?scope=` относится к selected period и выбранному overview scope:

```text
Техника                 Дополнительная выручка
└── Телефоны            ├── Аксессуары
                        └── Услуги
```

Родитель и вложенные строки различаются фоном, отступом и соединительной линией. Служебные подписи
«Итого по категории», «В том числе» и «Подытог» в интерфейсе не показываются. Блок остается
постоянно открытым. `SELLERS` включает только `rankingEligible` сотрудников, `STORE` — весь
магазин; переключатель общий с верхними метриками и планом.

Attach-map использует `/kpi/attach-rates` для store benchmark и `/employee-ratings` для roster.
Residual = store facts минус показанный roster; это «вне рейтинга / без сотрудника», не сотрудник.

- `rate=null`, base<=0: «Нет продаж для расчёта».
- Нет положительного store benchmark: «Нет среднего по магазину».
- Employee base ниже rating threshold: «Недостаточно продаж».
- Технические quality counters и количество замечаний на manager overview не показываются;
  подробности доступны администратору в отдельном разделе качества данных.

14 метрик и premium protection определены в [product/attach-rate](../product/attach-rate.md).
Employee rows периода с возвратами ограничены ADR-0001; store benchmark — нет.
