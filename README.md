# BATON WATCH

BATON WATCH is an independent Java/Spring service for asynchronous health checks
of BATON RoleResource URL snapshots.

## Current implementation

The implemented service currently provides:

- GET /api/v1/system/status
- authenticated PUT and GET /api/v1/resource-monitors/{resourceReference}
- revision-safe ACTIVE/INACTIVE monitor synchronization
- PostgreSQL schedules, leases, immutable attempts/results, current health, and
  durable health-change events with delivery leases and retry state
- isolated single-thread lanes for target checks, callback delivery, and
  maintenance, plus bounded JDBC statement and transactional row-lock waits
- a background checker with DNS pinning, SSRF and redirect defenses, bounded
  time/headers/bytes, hard-capped runtime resource settings, stale projection
  handling, and bounded retention
- at-least-once delivery to one configured BATON HTTPS callback, with a fixed
  payload, event-ID idempotency, DNS pinning, no redirects, capped exponential
  retries, and delivered-event retention
- low-cardinality check counters and Spring scheduled-execution timers plus
  event-delivery backlog, oldest-age, finalization, and bounded-outcome metrics
- a hexagonal six-module Gradle layout
- focused domain, application, HTTP, outbound-policy, and PostgreSQL integration
  tests, plus a fail-closed GitHub Actions check that proves the PostgreSQL and
  full production-context suites actually executed

Delivery is disabled until an operator supplies the callback and its separate
service token. Pending events remain durable while delivery is disabled or the
callback is unavailable. WATCH has no frontend or broker, and repository
artifacts are not evidence of a production deployment or external alerts.

## Technology and modules

- Java 21
- Spring Boot 4.1.0
- Gradle 9.2.1

Production dependencies point inward:

bootstrap -> adapters -> application -> domain

The modules are domain, application, adapter-in-web, adapter-out-persistence, adapter-out-external, and bootstrap.

## Build and run

The repository includes a Gradle wrapper pinned to 9.2.1. Docker must be
available for the full test task; PostgreSQL integration tests are intentionally
not allowed to pass by being skipped.
`SPRING_JDBC_TEMPLATE_QUERY_TIMEOUT` may override the five-second shared JDBC
statement deadline and must remain a whole-second duration of at least one
second.

~~~bash
./gradlew clean test :bootstrap:bootJar --no-build-cache
~~~

For the recommended local container run, copy the environment template and
replace the database and monitor-API placeholder secrets before starting.
Replace the separate delivery token as well when enabling its callback:

~~~bash
cp .env.example .env
docker compose up --build
curl http://localhost:8080/api/v1/system/status
~~~

Compose starts WATCH and a private PostgreSQL 18.4 service. The database port
and WATCH management port 8081 are not published; the latter serves Actuator
health and Prometheus metrics inside the runtime network. To synchronize a
monitor:

~~~bash
curl -X PUT http://localhost:8080/api/v1/resource-monitors/role-resource-123 \
  -H 'Authorization: Bearer replace-with-your-token' \
  -H 'Content-Type: application/json' \
  -d '{"sourceRevision":1,"monitoringState":"ACTIVE","targetUrl":"https://example.com/"}'
~~~

BATON must call synchronization only after its own transaction commits. A later
revision may use `INACTIVE` with no `targetUrl` to stop future checks while
retaining bounded history.

To activate health-change delivery, set these values in `.env` before starting
Compose:

~~~dotenv
WATCH_EVENT_DELIVERY_ENABLED=true
WATCH_EVENT_DELIVERY_ENDPOINT=https://baton.example.com/api/v1/internal/resource-health-events
WATCH_EVENT_DELIVERY_TOKEN=replace-with-a-separate-32-character-token
~~~

The endpoint must be an absolute public-global HTTPS URL on port 443, without
user-info, query, fragment, or an IP-literal host. BATON must authenticate the
bearer token and durably deduplicate the `Idempotency-Key`/`eventId` before
acknowledging with 2xx. See the delivery contract for the exact payload and
retry behavior.

## Maintained documents

- [Product baseline](docs/PRD/0001_product-baseline/spec.md)
- [API contract](docs/PRD/0002_api-contract/spec.md)
- [Monitoring MVP](docs/PRD/0003_monitoring-mvp/spec.md)
- [Health-change event delivery](docs/PRD/0004_health-change-event-delivery/spec.md)
- [Microservice boundary ADR](docs/ADR/0001_microservice-boundary/adr.md)
- [MVP storage and execution ADR](docs/ADR/0002_monitoring-mvp-storage-and-execution/adr.md)
- [Direct HTTPS event delivery ADR](docs/ADR/0003_health-change-event-delivery/adr.md)
- [Cloudflare Tunnel staging deployment runbook](docs/runbooks/staging-deployment.md)
  — the included staging artifacts are not evidence of a live, authenticated,
  or externally verified deployment
- [Public staging delivery validation runbook](docs/runbooks/public-staging-event-delivery.md)
- [Active handoff](HANDOFF.md)
