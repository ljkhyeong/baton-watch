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
- A real local TLS handshake verifies that the shared client connects only to
  the approved pinned address while preserving the original HTTP Host and TLS
  SNI. A certificate trusted by the test client succeeds only for its DNS SAN;
  the same certificate under a mismatched hostname is classified as
  `TLS_FAILURE` before the server handles an HTTP request.
- Response-byte accounting never consumes a probe byte beyond the configured
  allowance. Unknown-length responses that reach the allowance are rejected
  conservatively, oversized and header-limit failures are classified as
  `RESPONSE_TOO_LARGE`, and failed responses are aborted without Apache draining
  the remaining body.
- Allocation-sensitive response, header, DNS/request executor, and queue
  settings have hard implementation ceilings validated at bootstrap and again
  in the external adapter before resource allocation. Production check,
  delivery, and maintenance batch settings have separate bootstrap ceilings
  enforced before lease arithmetic and service execution.
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
- Target checks, callback delivery, and maintenance run on separate named
  single-thread schedulers, so a slow callback batch cannot starve check polling
  or cleanup. Every scheduler inherits the 65-second graceful shutdown wait;
  local Compose grants the process 70 seconds before forced termination.
- Adapter-owned persistence transactions have a five-second JDBC statement
  deadline and a transaction-local one-second PostgreSQL row-lock timeout. They
  reject an existing outer Spring transaction before work starts, preserving
  the network-outside-transaction boundary.
- Boot's shared `JdbcTemplate` and auto-configured `JdbcClient` apply the same
  five-second statement deadline to non-transactional projection and event
  backlog reads.
- Separate-port Actuator health/Prometheus endpoints and low-cardinality check,
  scheduler, backlog, oldest-event-age, finalization, and delivery-outcome
  telemetry are configured; Compose does not publish the management port. No
  external alerting stack, frontend, broker, or production deployment is
  present.
- The GitHub Actions `Verify` job uses SHA-pinned official checkout, Java, and
  Gradle actions, requires Docker, runs a clean uncached test/build, and parses
  fresh JUnit XML to require all six PostgreSQL suites plus the production-root
  context smoke. Both Testcontainers entry points use the library's fail-closed
  Docker policy, so missing Docker is a test failure rather than a skip.

## Verification

- Gradle 9.2.1 `clean test :bootstrap:bootJar --no-build-cache`: 271 tests
  passed with no skips, failures, or errors, including the Docker-backed
  PostgreSQL Testcontainers suite.
- The real `BatonWatchApplication` root context started against a
  service-connected PostgreSQL 18.4 container with Flyway V1/V2, Spring
  Security, all three persistence adapters, outbound check and delivery
  clients, enabled delivery workers, and all three named schedulers. Its HTTP
  smoke proved public status access, unauthenticated PUT rejection without a
  write, authenticated INACTIVE synchronization, authenticated projection
  readback, and the persisted UNKNOWN/INACTIVE row without attempts or events.
- The same result-evidence parser used by CI verified seven required suites and
  33 tests: six PostgreSQL suites/32 tests and the one production-root smoke.
- Executable boot jar: passed.
- Spring Boot JDBC and transaction auto-configuration: passed with Boot-managed
  `JdbcTemplate`, `JdbcClient`, five-second query timeout, and
  `PlatformTransactionManager`, plus the WATCH-owned bounded PostgreSQL
  `TransactionOperations` wiring all three persistence adapters.
- Outbound checker and callback adapter suite: 169 tests passed without a
  live-internet dependency, including exact consumed-byte accounting,
  no-drain response abort, header count/line classification, resource ceiling
  boundaries, pinned-address TLS Host/SNI preservation, DNS SAN verification,
  and trusted-certificate hostname-mismatch classification.
- PostgreSQL 18.4 Testcontainers suite: 32 tests passed, including V1/V2
  migration, revision races, deterministic locked-row skipping for check and
  delivery claims, disjoint concurrent claims, concurrent finalization
  idempotency, delivery token/attempt stale rejection, batch check- and
  delivery-claim rollback, check and delivery lease recovery, atomic event
  rollback across finalization, synchronization, and staleness, retry
  boundaries, backlog state, delivered-only retention, row-lock timeout,
  transaction-deadline rollback, transaction-local setting restoration,
  bounded non-transactional projection and backlog reads, and outer-transaction
  rejection.
- Named scheduler context tests verify independent single-thread execution,
  owned thread prefixes, shutdown policies, and explicit routing of every
  scheduled method.
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
- The public-staging preflight sends malformed JSON without credentials and
  requires `401`, externally demonstrating rejection before a JSON parser
  error. Its executable nine-case shell test also fixes token separation,
  child-environment isolation, hostname shape, POST/content-type/body, user
  curl configuration, HTTPS-only/no-proxy/TLS/deadline/output controls, and
  HTTP-status handling. A live receiver or public delivery run has not yet
  been verified.
- The monitor API authentication boundary has a real embedded-server test under
  a non-empty servlet context path. It verifies the Bearer challenge, exact 401
  problem fields,
  fail-closed `/api/v1/**` handling, statelessness, public status access, and
  authenticated PUT without CSRF.

## Next useful slice

After the first `Verify / verify` run exists, configure it as a required status
check for protected changes. Then run the controlled public-staging delivery
exercise in the maintained runbook with distinct operator-managed tokens and a
one-shot acknowledgement-loss ingress. Verify first delivery, same-`eventId`
replay, one BATON inbox row, backlog drain, and log redaction. Dashboards,
alerts, secret rotation, egress, backup/migration, rollout, rollback, and
reconciliation remain required before production. Do not infer a deployment or
external alert from repository configuration or local smoke evidence.
