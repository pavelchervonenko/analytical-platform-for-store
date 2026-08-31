---
doc_schema: 1
doc_type: evidence
status: historical
owner: product
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS_WITH_LIMITS
verdict_scope: "Preserved legacy evidence; commands and runtime claims require current verification."
source_of_truth:
  - "docs/current/product/README.md"
original_content_sha256: c8193df932314aba68c306ff67390d4b82f0fc00caf815836735e9eaba2e7cbd
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/product/README.md`.

# Уточнение области ручной классификации

Дата проверки: 2026-08-20.

Товар `Яндекс Станция Макс бежевый` был вручную классифицирован оператором через
форму «Неразмеченные товары». Это действие успешно назначает зарплатную категорию
`TECH_TIER_2` с первого числа выбранного месяца.

Форма вызывает `/api/admin/payroll-category-assignments/bulk` и не создает
аналитическое назначение `ProductCategoryAssignment`. Поэтому она не меняет
`sales_document_items.analytics_category_id` и не устраняет аналитическую категорию
`UNMAPPED` у уже загруженных продаж.

Следствие: товар классифицирован для расчета зарплаты, но остается неразмеченным в
аналитических KPI. Для структуры продаж ему отдельно требуется аналитическая
категория `PODS_WATCH_OTHER_DEVICE` и безопасное переобогащение существующих фактов.

Интерфейс должен явно различать «Зарплатную категорию» и «Аналитическую категорию»
либо выполнять одно согласованное назначение обеих категорий с предварительным
просмотром и аудитом изменений.
