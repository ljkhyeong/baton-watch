# BATON WATCH Handoff

## Current state

- The Java 21 / Spring Boot 4.1.0 monitoring MVP is implemented across six
  hexagonal Gradle modules.
- GET /api/v1/system/status and authenticated PUT/GET
  /api/v1/resource-monitors/{resourceReference} are implemented.
- Spring Security applies stateless service-token authentication to every
  non-status `/api/v1/**` request with context-path-aware matching.
- Application time comes from an injected UTC Clock.
- PostgreSQL stores revision-safe schedules, leases, immutable attempts/results,
  current derived health, and durable health-change events with pending/delivered
  state, retry timing, delivery attempts, and expiring leases.
- JDBC monitoring persistence is split along the application ports: monitor
  synchronization/projection/staleness and check claim/finalization/retention
  have separate adapters, while row mapping and caller-transaction event
  appending remain package-internal shared collaborators.
- The worker performs SSRF-safe, DNS-pinned outbound checks outside database
  transactions and handles staleness and bounded attempt retention. Monitor
  synchronization and redirect hops share the same static `TargetUrl` policy;
  legacy unsafe encodings can be rehydrated but are rejected before DNS or I/O.
- Target checks and event delivery share a neutral request-scoped Apache client,
  bounded deadline executor, pinned resolver, and bounded body discarder while
  retaining separate GET/redirect and POST/acknowledgement semantics.
- PRD-0004 direct delivery is implemented for one operator-configured BATON
  HTTPS callback: exact payload and idempotency header, separate bearer service
  authentication, public-global DNS pinning, no redirects, bounded resources,
  capped retries, and delivered-only retention. The sender boundary accepts
  only immutable event payload data; lease and retry metadata stay internal.
- A compatible BATON receiver with separate bearer authentication and an atomic
  immutable `eventId` inbox is implemented in the BATON repository. Its
  deployment and public WATCH-to-BATON integration are not yet verified.
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

- Gradle 9.2.1 `clean test --no-build-cache` run without the daemon: 233 tests
  passed with no skips, failures, or errors, including the Docker-backed
  PostgreSQL Testcontainers suite.
- Executable boot jar: passed.
- Outbound checker and callback adapter suite: 157 tests passed without a
  live-internet dependency.
- PostgreSQL 18.4 Testcontainers suite: 22 tests passed, including V1/V2
  migration, revision races, deterministic locked-row skipping, disjoint
  concurrent check claims, concurrent finalization idempotency, check and
  delivery lease recovery, atomic event rollback across finalization,
  synchronization, and staleness, retry boundaries, disjoint delivery claims,
  backlog state, and delivered-only retention.
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
- The public-staging preflight has an executable nine-case shell test covering
  its token separation, child-environment isolation, hostname shape, user curl
  configuration isolation, and HTTP-status handling. A live receiver or public
  delivery run has not yet been verified.
- The monitor API authentication boundary has a real embedded-server test under
  a non-empty servlet context path. It verifies the Bearer challenge, exact 401
  problem fields,
  fail-closed `/api/v1/**` handling, statelessness, public status access, and
  authenticated PUT without CSRF.

## Next useful slice

Run the controlled public-staging delivery exercise in the maintained runbook
using distinct operator-managed tokens and a one-shot acknowledgement-loss
ingress. Verify first delivery, same-`eventId` replay, one BATON inbox row,
backlog drain, and log redaction. After that, add dashboards and alerts for
backlog, oldest-event age, delivery outcomes, scheduler failures, and database
health; define secret rotation, egress policy, backup/migration, rollout,
rollback, and reconciliation procedures before production. Do not infer
deployment or an external alert from repository configuration, the preflight,
or the earlier local Compose smoke.
