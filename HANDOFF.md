# BATON WATCH Handoff

## Current state

- The Java 21 / Spring Boot 4.1.0 monitoring MVP is implemented across six
  hexagonal Gradle modules.
- GET /api/v1/system/status and authenticated PUT/GET
  /api/v1/resource-monitors/{resourceReference} are implemented.
- Application time comes from an injected UTC Clock.
- PostgreSQL stores revision-safe schedules, leases, immutable attempts/results,
  current derived health, and durable health-change events.
- The worker performs SSRF-safe, DNS-pinned outbound checks outside database
  transactions and handles staleness and bounded attempt retention.
- Health-change event delivery remains unimplemented. No frontend or broker is
  present.

## Verification

- Gradle 9.2.1 full test run with task cache bypassed: 142 tests passed.
- Full Gradle build and executable boot jar: passed.
- Safe checker suite: 106 tests passed without live-internet dependency.
- PostgreSQL 18.4 Testcontainers suite: 10 tests passed, including migration,
  revision race, lease recovery, stale/duplicate finalize, atomic events, stale
  boundary, and retention.
- Executable boot jar and clean Docker multi-stage build: passed.
- Isolated Compose smoke: PostgreSQL became healthy, Flyway V1 applied, status
  returned 200, missing authentication returned 401, active sync returned 200
  with UNKNOWN, the asynchronous check reached SUCCESS/HEALTHY, stale revision
  returned 409, and an IP-literal target returned 422.
- Smoke database contained one monitor, attempt, result, and health-change event.
- Smoke logs contained none of the exercised URL, host, query, or resource
  reference values. Graceful shutdown and datasource close completed.
- docker compose config --quiet: passed. Smoke containers, network, and test
  volume were removed afterward.
- All six project-local skills passed quick_validate.py after documentation
  synchronization.

## Next useful slice

Adopt an event delivery contract: fixed destination or broker choice,
authentication, idempotency, retry/backoff, delivery leases, retention, and
operator visibility. Add low-cardinality worker metrics and alerts before a
production rollout. Do not infer deployment from the local Compose smoke.
