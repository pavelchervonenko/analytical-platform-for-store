---
doc_schema: 1
doc_type: current
status: current
owner: operations
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/resource-limits.md
implementation_sources:
  - backend/src/main/resources/application.yml
  - backend/src/main/java/com/storeanalytics/common/config/ResourceLimitsProperties.java
  - backend/src/main/java/com/storeanalytics/common/config/BackgroundSchedulingConfiguration.java
  - deploy/compose.production.yml
  - deploy/Caddyfile
verification_sources:
  - backend/src/test/java/com/storeanalytics/common/config/ResourceLimitsPropertiesTest.java
  - backend/src/test/java/com/storeanalytics/BackgroundSchedulingArchitectureTest.java
  - backend/src/test/java/com/storeanalytics/common/web/RequestBodyLimitMvcTest.java
runtime_evidence: []
required_reviewers:
  - operations
  - backend
review_triggers:
  - resource-limit-change
  - scaling-change
  - load-profile-change
  - deployment-change
supersedes: []
superseded_by: null
---

# Ресурсные лимиты и масштабирование

## Назначение и границы

Документ фиксирует fail-fast ceilings и topology assumptions репозитория. Он не утверждает, что
конкретный production host имеет достаточный CPU/RAM или выдержал production-sized load test.

## Действующий контракт

- Caddy ограничивает request body и применяет connection/header/body timeouts.
- Backend валидирует HTTP header/body/form/swallow sizes, connection/keep-alive limits, thread,
  queue, parameter и database pool bounds при startup.
- Compose задаёт отдельные CPU, memory, PID, tmpfs и pool budgets для web, API, worker и migration.
- API и worker разделены: API отключает background workers, worker не публикуется наружу; migration
  использует отдельную роль и малый pool.
- Background workloads разнесены по bounded schedulers. Durable leases/advisory locks остаются
  cross-process guard, но не заменяют capacity planning.
- Session registry process-local. До shared session store поддерживается только одна API replica;
  иначе concurrent-session limit и revoke становятся несогласованными.

## Инварианты

- Сумма Hikari pools всех replicas, migration и operational reserve не должна превышать лимит БД.
- Увеличение API replicas запрещено до shared session registry и отдельной проверки revoke/login.
- Увеличение worker replicas требует доказательства lease/idempotency каждой включённой job.
- Значения в Compose — defaults/ceilings, а не evidence фактической нагрузки.

## Неполные данные и перегрузка

Request выше лимита отклоняется; saturation должна наблюдаться по HTTP/DB/scheduler metrics. Нельзя
лечить timeouts бесконтрольным увеличением pools: сначала проверяются БД, внешние API, backlog и
длительность транзакций.

## Расхождения и открытые решения

- Нет приложенного production-like load evidence с одновременно активными API и worker workloads.
- Не определён формальный capacity budget managed PostgreSQL и alert thresholds saturation.
- Горизонтальное масштабирование API заблокировано process-local session registry.

## Проверка

Configuration tests доказывают validation ceilings; architecture tests — раздельные schedulers;
request tests — bounded body. До изменения production budgets нужен staging load run с JVM,
Tomcat, Hikari, DB и queue metrics.

## Триггеры пересмотра

Изменение replicas, pools, Compose limits, request limits, scheduler concurrency, DB tier или load
profile требует повторного capacity review.
