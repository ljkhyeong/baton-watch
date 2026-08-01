# BATON WATCH Handoff

## Current state

- The Java 21 / Spring Boot 4.1.0 monitoring MVP is implemented across six
  hexagonal Gradle modules.
- GET /api/v1/system/status and authenticated PUT/GET
  /api/v1/resource-monitors/{resourceReference} are implemented.
- Application time comes from an injected UTC Clock.
- PostgreSQL stores revision-safe schedules, leases, immutable attempts/results,
  current derived health, and durable health-change events with pending/delivered
  state, retry timing, delivery attempts, and expiring leases.
- The worker performs SSRF-safe, DNS-pinned outbound checks outside database
  transactions and handles staleness and bounded attempt retention.
- PRD-0004 direct delivery is implemented for one operator-configured BATON
  HTTPS callback: exact payload and idempotency header, separate bearer service
  authentication, public-global DNS pinning, no redirects, bounded resources,
  capped retries, and delivered-only retention.
- Delivery is disabled by default. Undelivered events remain durable while it
  is disabled or BATON is unavailable.
- Scheduler shutdown waits up to 65 seconds so the bounded worst-case delivery
  batch can drain; local Compose grants the process 70 seconds before forced
  termination.
- Separate-port Actuator health/Prometheus endpoints and low-cardinality check,
  scheduler, backlog, oldest-event-age, finalization, and delivery-outcome
  telemetry are configured; Compose does not publish the management port. No
  external alerting stack, frontend, broker, or production deployment is
  present.

## Verification

- Gradle 9.2.1 `clean test --no-build-cache` run without the daemon: 219 tests
  passed with no failures, errors, or skips.
- Executable boot jar: passed.
- Outbound checker and callback adapter suite: 157 tests passed without a
  live-internet dependency.
- PostgreSQL 18.4 Testcontainers suite: 16 tests passed, including V1/V2
  migration, revision races, check and delivery lease recovery,
  stale/duplicate finalization, atomic events, retry boundaries, disjoint
  delivery claims, backlog state, and delivered-only retention.
- Executable boot jar and clean Docker multi-stage build: passed.
- Isolated Compose delivery smoke: PostgreSQL became healthy, Flyway V1 and V2
  applied, the application status and separate management health endpoints were
  UP, active sync returned UNKNOWN, and the asynchronous check reached
  SUCCESS/HEALTHY.
- With delivery disabled, the smoke database contained one PENDING event with
  delivery attempt zero and no lease; the Prometheus delivery backlog gauge was
  `1.0`, matching the database count.
- Smoke logs contained none of the exercised target URL, resource reference, or
  bearer token values. The test containers stopped cleanly, and their network
  and PostgreSQL volume were removed afterward.
- docker compose config --quiet: passed. Smoke containers, network, and test
  volume were removed afterward.
- All six project-local skills passed quick_validate.py after documentation
  synchronization.

## Next useful slice

Implement the matching BATON receiver with atomic `eventId` deduplication, then
run a controlled end-to-end delivery and replay test using operator-managed
secrets and public ingress. After that, add dashboards and alerts for backlog,
oldest-event age, delivery outcomes, scheduler failures, and database health;
define secret rotation, egress policy, backup/migration, rollout, rollback, and
reconciliation procedures before production. Do not infer deployment or an
external alert from repository configuration or the earlier local Compose
smoke.
